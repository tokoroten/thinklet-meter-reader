package com.example.meterreader.geo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 端末GPSの軽量トラッカ（**GMS非依存＝素の `LocationManager`**）。**アプリ非依存・単体で流用可能**。
 *
 * 前面表示中に GPS(+NETWORK) プロバイダを購読して**最新の測位を保持**し、撮影など任意のタイミングで
 * [current] によりスナップショット（緯度/経度/精度/測位時刻）を取得する。画面の無い端末で
 * 「巡回しながら撮影 → どこで撮ったか」を記録する用途に使う。
 *
 * - THINKLET LC01 は `GPS_PROVIDER` が利用可能（実機確認済み: feature `location.gps`, providers `gps,network`）。
 * - 権限 `ACCESS_FINE_LOCATION` が必要。未付与なら [start]/[current] は安全に no-op（後から付与で再 [start] 可）。
 * - 屋内など測位できない場面では `current()==null` か、`accuracy`/`time` が古い値になり得る（鮮度は呼び出し側で判断）。
 *
 * 流用手順: このファイルをコピーし `package` 行を変更するだけ。権限 `ACCESS_FINE_LOCATION` をマニフェストに追加。
 */
class LocationTracker(
    context: Context,
    private val minTimeMs: Long = 1000L,   // 測位コールバックの最小間隔
    private val minDistM: Float = 0f,      // 測位コールバックの最小移動距離(m)
    private val logTag: String = "LocationTracker",
) : LocationListener {

    private val appCtx = context.applicationContext
    private val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var last: Location? = null
    @Volatile private var started = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 購読開始（任意スレッドから可）。権限が無ければ何もしない。冪等。 */
    fun start() {
        if (started || !hasPermission()) return
        started = true
        runCatching {
            val looper = Looper.getMainLooper()
            val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (gps) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, minDistM, this, looper)
            if (net) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTimeMs, minDistM, this, looper)
            seedLastKnown()   // 既知の最終測位で即時シード（コールド起動直後でも値を返せる）
            Log.i(logTag, "location updates requested (gps=$gps net=$net)")
        }.onFailure { started = false; Log.w(logTag, "start failed: ${it.message}") }
    }

    /** 購読停止。 */
    fun stop() {
        if (!started) return
        started = false
        runCatching { lm.removeUpdates(this) }
    }

    /** 現在の最良測位（トラッキング最新と getLastKnownLocation の新しい方）。無ければ null。任意スレッド可。 */
    fun current(): Location? {
        if (!hasPermission()) return null
        seedLastKnown()
        return last
    }

    private fun seedLastKnown() {
        if (!hasPermission()) return
        runCatching {
            val cands = listOfNotNull(
                runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
                runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
            )
            val best = cands.maxByOrNull { it.time } ?: return
            if (best.time > (last?.time ?: 0L)) last = best
        }
    }

    // ---- LocationListener ----
    override fun onLocationChanged(loc: Location) {
        if (loc.time >= (last?.time ?: 0L)) last = loc   // GPS/NETWORK 混在のため時刻で新しい方を採用
    }
    // API27 では下記もインタフェースの抽象メソッド。未実装だと実機で AbstractMethodError になるため明示実装する。
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
