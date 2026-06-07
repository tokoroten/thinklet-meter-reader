package com.example.meterreader.mdns

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * 汎用 mDNS / DNS-SD アドバタイザ（jmDNS + MulticastLock）。**アプリ非依存・単体で流用可能**。
 *
 * `<hostLabel>.local` の A レコードと `_<service>._tcp` サービスを LAN に広告し、同一LANのクライアントから
 * `http://<hostLabel>.local:<port>/` で名前アクセスできるようにする（画面の無い端末で「IPが分からない」問題を解く）。
 *
 * 設計方針:
 *  - アプリ固有値（ポート/ホスト名/サービス種別/インスタンス名/TXT/ログTAG）は**すべてコンストラクタ注入**。本クラスは特定アプリに依存しない。
 *  - ホスト名・サービス名は `() -> String` で都度評価するので、実行時の名前変更に追従できる（変更後に reregister() を呼ぶ）。
 *  - 衝突時 jmDNS が末尾に `-2` 等を付けるため、**確定後の名前**を `committedName` と `onRegistered` で返す。
 *    ただし Android では jmDNS のマルチキャスト受信が不安定で**自動リネームは保証されない**ため、
 *    複数台運用では各端末に**最初から一意なホスト名**を与えること（衝突を起こさない設計を推奨）。
 *
 * 依存: jmDNS（`org.jmdns:jmdns`）。権限: `INTERNET`, `CHANGE_WIFI_MULTICAST_STATE`。
 * スレッド: 公開メソッドは任意スレッドから呼んでよい（内部の単一スレッドで逐次実行）。
 *
 * 流用手順: このファイルをコピーし `package` 行を変更するだけ。
 *
 * 使用例:
 * ```
 * val mdns = MdnsAdvertiser(
 *     context, port = 8080,
 *     hostLabel = { config.deviceName },          // 例 "meter-6841"
 *     serviceName = { "Meter Reader" },           // DNS-SD ブラウザ表示名（任意）
 *     txt = mapOf("path" to "/"),                 // TXT レコード（任意）
 *     logTag = "MeterReader",
 *     onRegistered = { name -> /* name = "meter-6841.local" */ },
 * )
 * mdns.start()        // 起動時
 * mdns.reregister()   // ネットワーク変更／ホスト名変更時
 * mdns.stop()         // 破棄時
 * ```
 */
class MdnsAdvertiser(
    context: Context,
    private val port: Int,
    private val hostLabel: () -> String,
    private val serviceType: String = "_http._tcp.local.",
    private val serviceName: () -> String = { hostLabel() },
    private val txt: Map<String, String> = emptyMap(),
    private val logTag: String = "MdnsAdvertiser",
    private val onRegistered: (String) -> Unit = {},
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var jmdns: JmDNS? = null
    private val exec = Executors.newSingleThreadExecutor()

    /** 確定ホスト名 `"<label>.local"`（衝突時は `-2` 付き）。未登録時は空文字。 */
    @Volatile var committedName: String = ""
        private set

    /** 広告を開始（site-local IPv4 取得 → jmDNS 登録）。 */
    fun start() { exec.execute { register() } }

    /** ネットワーク変更・ホスト名変更時に呼ぶ（作り直して再登録）。 */
    fun reregister() { exec.execute { register() } }

    /** 広告を停止し、MulticastLock/スレッドを解放する。 */
    fun stop() {
        exec.execute { teardown(); runCatching { lock?.release() }; lock = null }
        exec.shutdown()
    }

    private fun register() {
        try {
            teardown()
            val label = hostLabel().trim()
            if (label.isEmpty()) { Log.w(logTag, "mDNS: empty host label; skip"); return }
            val addr = siteLocalIpv4() ?: run { Log.w(logTag, "mDNS: no site-local IPv4; skip"); return }
            if (lock == null) {
                lock = wifi.createMulticastLock("$logTag-mdns").apply { setReferenceCounted(true); acquire() }
            }
            val j = JmDNS.create(addr, label)
            val info = if (txt.isEmpty())
                ServiceInfo.create(serviceType, serviceName(), port, "")
            else
                ServiceInfo.create(serviceType, serviceName(), port, 0, 0, txt)
            j.registerService(info)
            jmdns = j
            committedName = j.hostName.removeSuffix(".")
            Log.i(logTag, "mDNS registered: http://$committedName:$port (addr=${addr.hostAddress})")
            onRegistered(committedName)
        } catch (e: Exception) {
            Log.w(logTag, "mDNS register failed", e)
        }
    }

    private fun teardown() {
        jmdns?.let { runCatching { it.unregisterAllServices() }; runCatching { it.close() } }
        jmdns = null
    }

    /** site-local IPv4（wlan0 等）を1つ返す。無ければ null。 */
    private fun siteLocalIpv4(): InetAddress? {
        runCatching {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && a.isSiteLocalAddress) return a
                }
            }
        }
        return null
    }
}
