package com.example.meterreader

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject
import java.io.File

/**
 * アプリ設定（OpenAI 接続情報＋読み取りヒント）。
 * 画面の無い THINKLET 向けに、設定は内蔵HTTPの /config ページから投入する。
 *
 * 保存先は **Android Keystore 由来のマスターキーで暗号化された SharedPreferences**
 * （`EncryptedSharedPreferences`）。キー値も値も AES で暗号化され、ディスク上に平文では残らない。
 * APIキーはログ・/state.json には**実体を出さない**（マスク表示のみ）。
 *
 * 旧バージョンの平文 `filesDir/config.json` があれば、初回ロード時に暗号化ストアへ移行し、
 * 平文ファイルは削除する（ディスク上の平文キーを消去）。
 */
class Config(private val appContext: Context) {

    @Volatile var apiKey: String = ""
        private set
    @Volatile var model: String = DEFAULT_MODEL
        private set
    @Volatile var endpoint: String = DEFAULT_ENDPOINT
        private set
    @Volatile var hint: String = ""
        private set
    @Volatile var exposureUs: Long = 0L   // 露光時間(マイクロ秒)。0=自動
        private set
    @Volatile var iso: Int = 0            // ISO感度。0=自動（exposureUs と両方>0 で手動固定）
        private set
    @Volatile var deviceName: String = "meter"   // mDNS ホスト名 <name>.local。/config で変更可（既定 meter）
        private set

    private val prefs: SharedPreferences? by lazy { createPrefs() }

    /** Keystore 由来のマスターキーで暗号化された SharedPreferences を生成。失敗時は null（メモリ保持のみ）。 */
    private fun createPrefs(): SharedPreferences? = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences init failed; 設定はこのセッション中のみ保持されます", e)
        null
    }

    /** 起動時に呼ぶ。旧平文ファイルがあれば移行→削除し、暗号化ストアから読み込む。 */
    fun load() {
        val p = prefs ?: run { Log.w(TAG, "secure store unavailable; defaults only"); return }
        migrateLegacyIfNeeded(p)
        apiKey = p.getString(K_API, "") ?: ""
        model = (p.getString(K_MODEL, "") ?: "").ifBlank { DEFAULT_MODEL }
        endpoint = (p.getString(K_ENDPOINT, "") ?: "").ifBlank { DEFAULT_ENDPOINT }
        hint = p.getString(K_HINT, "") ?: ""
        exposureUs = p.getLong(K_EXP, 0L)
        iso = p.getInt(K_ISO, 0)
        val storedName = p.getString(K_NAME, "") ?: ""
        deviceName = if (storedName.isBlank()) {
            // 既定名は端末固有に一意化（Android の jmDNS は衝突自動回避が不確実なため、最初から重複させない）。
            val def = "meter-%04d".format(kotlin.random.Random.nextInt(10000))
            p.edit().putString(K_NAME, def).apply()
            Log.i(TAG, "deviceName generated: $def")
            def
        } else sanitizeName(storedName)
        Log.i(TAG, "config loaded (encrypted): model=$model endpoint=$endpoint hint='${hint.take(40)}' exp=${exposureUs}us iso=$iso name=$deviceName key=${maskedKey()}")
    }

    /** /config の POST から呼ぶ。空文字キーは「未変更」とみなして既存キーを保持する。 */
    fun update(apiKey: String?, model: String?, endpoint: String?, hint: String?, exposureUs: Long? = null, iso: Int? = null, deviceName: String? = null) {
        if (!apiKey.isNullOrBlank()) this.apiKey = apiKey.trim()
        if (!model.isNullOrBlank()) this.model = model.trim()
        if (!endpoint.isNullOrBlank()) this.endpoint = endpoint.trim()
        if (hint != null) this.hint = hint.trim()   // ヒントは空文字でのクリアを許可
        if (exposureUs != null) this.exposureUs = exposureUs.coerceAtLeast(0L)   // 0=自動
        if (iso != null) this.iso = iso.coerceAtLeast(0)                          // 0=自動
        if (deviceName != null) this.deviceName = sanitizeName(deviceName)        // mDNS名（不正文字は除去, 空は meter）
        save()
        Log.i(TAG, "config updated: model=${this.model} endpoint=${this.endpoint} exp=${this.exposureUs}us iso=${this.iso} name=${this.deviceName} key=${maskedKey()}")
    }

    /** mDNS/DNSラベルとして安全な名前へ正規化（英小文字/数字/ハイフン, 1〜40, 空なら meter）。 */
    private fun sanitizeName(s: String): String {
        val cleaned = s.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '-' }
            .joinToString("").trim('-').take(40)
        return cleaned.ifBlank { "meter" }
    }

    private fun save() {
        val p = prefs ?: return
        p.edit()
            .putString(K_API, apiKey).putString(K_MODEL, model)
            .putString(K_ENDPOINT, endpoint).putString(K_HINT, hint)
            .putLong(K_EXP, exposureUs).putInt(K_ISO, iso)
            .putString(K_NAME, deviceName)
            .apply()
    }

    /** 旧平文 config.json があれば暗号化ストアへ取り込み、平文ファイルを削除する。 */
    private fun migrateLegacyIfNeeded(p: SharedPreferences) {
        val legacy = File(appContext.filesDir, LEGACY_FILE)
        if (!legacy.exists()) return
        runCatching {
            val o = JSONObject(legacy.readText())
            val e = p.edit()
            o.optString("apiKey").takeIf { it.isNotBlank() }?.let { e.putString(K_API, it) }
            o.optString("model").takeIf { it.isNotBlank() }?.let { e.putString(K_MODEL, it) }
            o.optString("endpoint").takeIf { it.isNotBlank() }?.let { e.putString(K_ENDPOINT, it) }
            if (o.has("hint")) e.putString(K_HINT, o.optString("hint"))
            e.commit()   // 直後に読むので同期コミット
            Log.i(TAG, "migrated legacy config.json -> encrypted store")
        }.onFailure { Log.w(TAG, "legacy migrate failed", it) }
        runCatching { legacy.delete() }   // 平文キーをディスクから消去
    }

    fun hasKey(): Boolean = apiKey.isNotBlank()

    /** 表示用にマスクしたキー（末尾4桁のみ）。実体は決して返さない。 */
    fun maskedKey(): String = if (apiKey.length >= 4) "****" + apiKey.takeLast(4) else if (apiKey.isEmpty()) "(未設定)" else "****"

    companion object {
        private const val TAG = "MeterReader"
        private const val PREFS_NAME = "secure_config"   // shared_prefs/secure_config.xml（暗号化済み）
        private const val LEGACY_FILE = "config.json"     // 旧平文ファイル（移行後に削除）
        private const val K_API = "apiKey"
        private const val K_MODEL = "model"
        private const val K_ENDPOINT = "endpoint"
        private const val K_HINT = "hint"
        private const val K_EXP = "exposureUs"
        private const val K_ISO = "iso"
        private const val K_NAME = "deviceName"
        const val DEFAULT_MODEL = "gpt-5"
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    }
}
