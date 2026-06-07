package com.example.meterreader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.meterreader.geo.LocationTracker
import com.example.meterreader.mdns.MdnsAdvertiser
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Meter Reader — メータ（水道/ガス/電力/圧力/温度…）を一人称カメラで撮影し、OpenAI(Vision)＋
 * Structured Outputs で数値化→TTS読み上げ→内蔵HTTPで履歴閲覧する THINKLET 向けアプリ。
 *
 * 操作（画面/タッチの無い端末向け）:
 *  - 音量Down: 撮影→OpenAIで読み取り→読み上げ＋記録
 *  - 音量Up  : 直前の読み取り値を再読み上げ
 *  - 設定（APIキー/モデル/ヒント）は内蔵HTTPの /config から投入し filesDir に保存。
 *
 * デバッグ: adb で画像を push → ブロードキャストで撮影と同じ推論パイプラインに流せる（下記 DebugReceiver）。
 */
@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private val camExec = Executors.newSingleThreadExecutor()
    private val netExec = Executors.newSingleThreadExecutor()   // OpenAI 通信（カメラを止めない）
    private val reading = AtomicBoolean(false)                  // 二重読み取り防止

    @Volatile private var latestBitmap: Bitmap? = null          // 最新の解析フレーム（撮影元）
    private var lastFrameMs = 0L
    private var lastPreviewPubMs = 0L

    // 露出制御。自動時はブレ抑制のため上限露光を固定して明るさ適応（qrop_qr 流用）。
    // 設定で露光時間(µs)とISOを指定すると手動固定（長秒・低ゲイン＝低ノイズが可能）。
    private var camCtl: Camera2CameraControl? = null
    private var curExp = 8_000_000L
    private var isoFixed = 1550
    private var expLo = 500_000L          // 自動時の下限
    private var expHi = 16_000_000L       // 自動時の上限（ブレ抑制 1/62s）
    private var lastExpMs = 0L
    // センサーのフルレンジ（手動指定のクランプ＆WebUI表示用）
    private var sensorExpLo = 250_000L
    private var sensorExpHi = 100_000_000L
    private var isoMin = 100
    private var isoMax = 1550
    private var manualApplied = false     // 直近で手動値を適用済みか（自動へ戻す検知用）

    private lateinit var previewView: ImageView
    private lateinit var shotView: ImageView

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val config by lazy { Config(applicationContext) }
    private val openai by lazy { OpenAiClient(config) }
    private val httpServer by lazy { MeterHttpServer(config, File(filesDir, "history"), HTTP_PORT) }
    // バーコード/QR 検出（端末内バンドル＝GMS非依存, 全フォーマット）。顧客ID/メーターIDの読み取りに使用
    private val barcodeScanner = BarcodeScanning.getClient()
    @Volatile private var lastReading: OpenAiClient.MeterReading? = null
    private var httpUrl = ""
    // 音量Up のマルチタップ判定（THINKLETの物理ボタンは長押しでキーリピートが出ないため、回数で区別）
    private val mainHandler = Handler(Looper.getMainLooper())
    private var upTaps = 0

    // mDNS（<deviceName>.local で名前アクセス）。汎用 MdnsAdvertiser にアプリ固有値を注入するだけ。
    private val mdns by lazy {
        MdnsAdvertiser(
            applicationContext,
            port = HTTP_PORT,
            hostLabel = { config.deviceName },        // 例 "meter-6841"（/config で変更可）
            serviceName = { "Meter Reader" },         // DNS-SD ブラウザ表示名
            txt = mapOf("path" to "/"),
            logTag = TAG,
            holdWifiLock = true,   // Wi-Fi省電力を無効化しmDNS応答の取りこぼしを抑える（据置給電運用前提）
            onRegistered = { name -> onMdnsRegistered(name) },
        )
    }

    // 撮影時の GPS 記録（GMS非依存の素のLocationManager）。巡回点検で「どこで撮ったか」を残す
    private val location by lazy { LocationTracker(applicationContext, logTag = TAG) }

    // オフラインキュー：WiFi未接続/通信失敗時に撮影画像をためて、接続時に自動で再認識する
    private val queueDir by lazy { File(filesDir, "queue") }
    private val connManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    @Volatile private var processingQueue = false
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "network available -> process offline queue + re-register mDNS")
            netExec.execute { processQueue() }
            mdns.reregister()
        }
    }

    // adb から画像を流し込んで推論させるデバッグ用レシーバ（動的登録）。
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val path = intent?.getStringExtra("path")?.takeIf { it.isNotBlank() }
                ?: File(getExternalFilesDir(null), DEBUG_FILE).absolutePath
            val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            if (bmp == null) {
                Log.w(TAG, "debug: decode failed: $path")
                announce("デバッグ画像を読み込めません")
                return
            }
            Log.i(TAG, "debug read: $path (${bmp.width}x${bmp.height})")
            runReading(bmp, "debug")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 画面なし運用：スリープ防止
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        shotView = findViewById(R.id.shot)

        config.load()
        httpServer.load()    // 永続化済みの撮影履歴を復元
        httpServer.start()
        httpUrl = httpServer.urls().firstOrNull { !it.startsWith("http://localhost") }
            ?: "http://localhost:$HTTP_PORT (USB: adb forward)"
        Log.i(TAG, "HTTP: ${httpServer.urls().joinToString("  /  ")}")
        ui(accessText("Meter Reader 起動（音量Down=撮影 / 各URL+/config で設定）"))

        tts = TextToSpeech(this, { st ->
            ttsReady = (st == TextToSpeech.SUCCESS)
            Log.i(TAG, "TTS init=$ttsReady engine=${runCatching { tts?.defaultEngine }.getOrNull()}")
            if (ttsReady) announce(if (config.hasKey()) "メーターリーダー準備完了" else "APIキーが未設定です。設定ページで入力してください")
        }, TTS_ENGINE)

        registerReceiver(debugReceiver, IntentFilter(ACTION_DEBUG_READ))
        runCatching { connManager.registerDefaultNetworkCallback(netCallback) }   // 接続復帰でキュー処理＋mDNS再登録
        netExec.execute { processQueue() }   // 起動時に保留分があれば処理
        mdns.start()   // mDNS 登録（<deviceName>.local）
        httpServer.onConfigChanged = { mdns.reregister() }   // /config でデバイス名変更→mDNS再登録

        val needLoc = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val req = if (needLoc) arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
                      else arrayOf(Manifest.permission.CAMERA)
            ActivityCompat.requestPermissions(this, req, 1)
            ui("CAMERA権限待ち（install -g 推奨）"); return
        }
        if (needLoc) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
        else location.start()   // 既に許可済みなら即購読開始（未許可時は付与コールバックで開始）
        startCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        var camGranted = false
        for (i in permissions.indices) {
            if (grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED) continue
            when (permissions[i]) {
                Manifest.permission.CAMERA -> camGranted = true
                Manifest.permission.ACCESS_FINE_LOCATION -> location.start()
            }
        }
        if (camGranted) startCamera()
    }

    // タッチの無い端末向け：音量Down=撮影 / 音量Up=（1回）直前値の再読み上げ・（2回以上）アクセス先URL読み上げ。
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { if (event.repeatCount == 0) capture(); return true }
            KeyEvent.KEYCODE_VOLUME_UP -> { if (event.repeatCount == 0) onVolUpTap(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }

    /** 音量Up を1回ぶん数え、ウィンドウ経過後に回数で動作を確定（1回=再読み上げ / 2回以上=URL案内）。 */
    private fun onVolUpTap() {
        upTaps++
        Log.i(TAG, "vol-up tap: $upTaps")
        mainHandler.removeCallbacks(upTapFinalize)
        mainHandler.postDelayed(upTapFinalize, MULTI_TAP_WINDOW_MS)
    }

    private val upTapFinalize = Runnable {
        val n = upTaps; upTaps = 0
        Log.i(TAG, "vol-up taps finalized: $n")
        if (n >= 2) speakAccessUrl() else repeatLast()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val rs = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(rs)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(camExec) { proxy -> onFrame(proxy) }
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                applyFastShutter(camera)
                Log.i(TAG, "camera bound")
            } catch (e: Exception) { Log.e(TAG, "camera bind failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(proxy: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMs < FRAME_MS) return   // プレビューは間引き（照準合わせには十分）
            lastFrameMs = now
            val bmp = proxyToBitmap(proxy)
            maybeAdjustExposure(bmp, now)
            latestBitmap = bmp
            showPreview(bmp)
            if (now - lastPreviewPubMs > PREVIEW_PUB_MS) {
                lastPreviewPubMs = now
                httpServer.setPreview(jpegBytes(scaleToEdge(bmp, PREVIEW_HTTP_EDGE), 70))
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame error", e)
        } finally {
            proxy.close()
        }
    }

    /** 音量Down: 最新フレームを撮影として確定し読み取りへ。 */
    private fun capture() {
        val bmp = latestBitmap
        if (bmp == null) { announce("カメラ準備中です"); return }
        runReading(bmp, "button")
    }

    /** 音量Up 短押し: 直前の読み取り値を再読み上げ。 */
    private fun repeatLast() {
        val r = lastReading
        if (r == null) announce("まだ読み取りがありません") else speakReading(r)
    }

    /** mDNS 登録完了：サーバ表示用に名前を渡し、HUD/ログにも反映。 */
    private fun onMdnsRegistered(name: String) {
        httpServer.setMdnsName(name)
        httpUrl = "http://$name" + if (HTTP_PORT == 80) "" else ":$HTTP_PORT"
        Log.i(TAG, "mDNS: $httpUrl")
        runOnUiThread { ui(accessText()) }
    }

    /** 音量Up 2回以上: アクセス先を音声・画面で案内。mDNS名のみ（無い時だけIPにフォールバック）。 */
    private fun speakAccessUrl() {
        val name = mdns.committedName
        val portSuffix = if (HTTP_PORT == 80) "" else ":$HTTP_PORT"     // 80 は省略
        val ipFallback = if (name.isBlank()) {
            httpServer.urls().firstOrNull { !it.startsWith("http://localhost") }
                ?.removePrefix("http://")?.substringBefore(":")
        } else null
        val shown = when {
            name.isNotBlank() -> "http://$name$portSuffix"
            ipFallback != null -> "http://$ipFallback$portSuffix"
            else -> "ネットワーク未接続"
        }
        Log.i(TAG, "speakAccessUrl (vol-up multi-tap): $shown")
        runOnUiThread { ui(accessText()) }
        val portSpoken = if (HTTP_PORT == 80) "" else "。ポート ${HTTP_PORT}"
        val spoken = when {
            name.isNotBlank() -> "アクセス先。${spellHost(name)}$portSpoken"
            ipFallback != null -> "アクセス先。${spellDigits(ipFallback)}$portSpoken"
            else -> "ネットワークに接続していません"
        }
        announce(spoken)
    }

    /** "m0427.local" → 「エム、ゼロ、ヨン、ニ、ナナ、ドット、ローカル」（数値ではなく桁で読む）。 */
    /** 現在の IP ベースの URL（非localhost）。無ければ null。 */
    private fun ipUrl(): String? =
        httpServer.urls().firstOrNull { !it.startsWith("http://localhost") }

    /** 現在の mDNS URL（`<name>.local`）。未登録なら null。 */
    private fun mdnsUrl(): String? {
        val n = mdns.committedName
        return if (n.isBlank()) null
        else "http://$n" + if (HTTP_PORT == 80) "" else ":$HTTP_PORT"
    }

    /** 画面HUD用「アクセス先」: mDNS と IP を両方（取得できた分だけ）併記する。 */
    private fun accessText(head: String = "アクセス先"): String = buildString {
        append(head)
        val md = mdnsUrl(); val ip = ipUrl()
        if (md != null) append("\n mDNS: $md")
        if (ip != null) append("\n IP:   $ip")
        if (md == null && ip == null) append("\n （ネットワーク未接続）")
    }

    private fun spellHost(host: String): String {
        val label = host.substringBefore(".")
        val body = label.map { c -> if (c in '0'..'9') DIGITS_JA[c - '0'] else if (c == 'm' || c == 'M') "エム" else c.toString() }
        return body.joinToString("、") + "、ドット、ローカル"
    }

    /** "192.168.0.107" → 「イチ、キュウ、ニ、ドット、…」。 */
    private fun spellDigits(s: String): String =
        s.map { c -> if (c in '0'..'9') DIGITS_JA[c - '0'] else if (c == '.') "ドット" else c.toString() }.joinToString("、")

    /** 撮影/デバッグ共通の読み取りパイプライン：縮小→JPEG→OpenAI→記録→読み上げ。 */
    private fun runReading(src: Bitmap, source: String) {
        if (!config.hasKey()) { announce("APIキーが未設定です。設定ページで入力してください"); return }
        if (!reading.compareAndSet(false, true)) { announce("処理中です"); return }
        netExec.execute {
            try {
                val shot = scaleToEdge(src, MAX_UPLOAD_EDGE)
                val jpeg = jpegBytes(shot, 85)   // クリーンな画像を OpenAI へ（枠は描かない）
                runOnUiThread { shotView.setImageBitmap(shot); ui("読み取り中…（$source, ${jpeg.size / 1024}KB）") }
                announce("読み取り中")
                // ML Kit でバーコード/QR を同時検出（顧客ID/メーターIDの可能性が高い）。元解像度で読む。
                val hits = detectCodes(src)
                val codes = hits.map { it.text }
                if (codes.isNotEmpty()) Log.i(TAG, "codes: ${codes.joinToString(" / ")}")
                // 検出領域を可視化した画像を画面表示＆履歴用に作る（OpenAI送信はクリーンな方）
                val shotShown = if (hits.isNotEmpty()) drawCodeBoxes(shot, hits, shot.width.toFloat() / src.width) else shot
                if (hits.isNotEmpty()) runOnUiThread { shotView.setImageBitmap(shotShown) }
                Log.i(TAG, "reading: source=$source jpeg=${jpeg.size}B model=${config.model} codes=${codes.size}")
                val ts = System.currentTimeMillis()
                val loc = location.current()   // 撮影時の GPS スナップショット（無ければ null）
                // オフラインなら OpenAI を呼ばずキューへ（接続復帰時に自動再認識）
                if (!isOnline()) {
                    enqueue(jpeg, ts, source, loc)
                    runOnUiThread { ui("オフラインのためキューに保存（接続時に自動認識）") }
                    announce("オフラインです。接続したら自動で読み取ります")
                    return@execute
                }
                val res = openai.read(jpeg, codes)
                when (res) {
                    is OpenAiClient.Result.Ok -> {
                        val r = res.reading
                        lastReading = r
                        val thumb = jpegBytes(scaleToEdge(shotShown, HISTORY_EDGE), 80)   // 履歴サムネ（枠付き）
                        httpServer.publish(r, thumb, ts, source, codes,
                            loc?.latitude, loc?.longitude, loc?.accuracy?.toDouble(), loc?.time)
                        Log.i(TAG, "RESULT ${r.rawJson}")
                        val idLine = if (codes.isNotEmpty()) "\nID: " + codes.joinToString(", ") else ""
                        runOnUiThread { ui(formatForScreen(r) + idLine) }
                        speakReading(r)
                    }
                    is OpenAiClient.Result.Err -> {
                        if (res.retryable) {   // 通信系の一時障害 → キューへ
                            enqueue(jpeg, ts, source, loc)
                            runOnUiThread { ui("通信できないためキューに保存（接続時に自動認識）: ${res.message}") }
                            announce("通信できません。接続したら自動で読み取ります")
                        } else {
                            Log.w(TAG, "read error: ${res.message}")
                            runOnUiThread { ui("エラー: ${res.message}") }
                            announce(res.message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "runReading failed", e)
                runOnUiThread { ui("内部エラー: ${e.message}") }
                announce("内部エラーが発生しました")
            } finally {
                reading.set(false)
            }
        }
    }

    private fun formatForScreen(r: OpenAiClient.MeterReading): String {
        val v = r.valueText.ifBlank { r.value?.toString() ?: "?" }
        val u = r.unit ?: ""
        val ok = if (r.readingOk) "OK" else "要確認"
        val conf = (r.confidence * 100).toInt()
        return "$v $u  [$ok ${conf}%  ${r.meterType}/${r.displayType}]" + if (r.notes.isNotBlank()) "\n${r.notes}" else ""
    }

    /** 結果を日本語で読み上げ。読めた時は値＋単位、ダメな時は理由。 */
    private fun speakReading(r: OpenAiClient.MeterReading) {
        val text = if (r.readingOk || r.valueText.isNotBlank() || r.value != null) {
            val v = r.valueText.ifBlank { r.value?.toString() ?: "" }
            (v + " " + spokenUnit(r.unit)).trim().ifBlank { "値を読み取れませんでした" }
        } else {
            "読み取れませんでした。" + r.notes.take(40)
        }
        announce(text)
    }

    private fun spokenUnit(u: String?): String = when (u?.trim()?.lowercase()) {
        null, "" -> ""
        "m3", "m³", "㎥", "立方メートル" -> "立方メートル"
        "kwh" -> "キロワットアワー"
        "wh" -> "ワットアワー"
        "kw" -> "キロワット"
        "v" -> "ボルト"
        "a" -> "アンペア"
        "mpa" -> "メガパスカル"
        "kpa" -> "キロパスカル"
        "pa" -> "パスカル"
        "bar" -> "バール"
        "l" -> "リットル"
        "℃", "c", "°c", "度" -> "度"
        else -> u
    }

    /** 状態アナウンス（日本語固定・即読み上げ）。未導入TTSなら無音で継続。 */
    private fun announce(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        t.setLanguage(Locale.JAPANESE)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "announce")
    }

    // ---- カメラ露出（qrop_qr 流用：ブレ抑制のため上限露光を固定し明るさで自動調整）----

    private fun applyFastShutter(camera: Camera) {
        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val manual = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            Log.i(TAG, "manualSensor=$manual expRange=$expRange isoRange=$isoRange")
            if (manual && expRange != null && isoRange != null) {
                sensorExpLo = expRange.lower; sensorExpHi = expRange.upper
                isoMin = isoRange.lower; isoMax = isoRange.upper
                expLo = max(expRange.lower, MIN_EXP_NS)
                expHi = min(expRange.upper, MAX_EXP_NS)
                isoFixed = isoRange.upper
                curExp = 12_000_000L.coerceIn(expLo, expHi)
                camCtl = Camera2CameraControl.from(camera.cameraControl)
                // WebUI(/config)で有効範囲を表示できるようにセンサー諸元を渡す
                httpServer.setCameraCaps(sensorExpLo / 1000, sensorExpHi / 1000, isoMin, isoMax)
                applyExposure()
                Log.i(TAG, "exposure caps: exp[${sensorExpLo / 1000}-${sensorExpHi / 1000}us] iso[$isoMin-$isoMax]; auto start=${curExp / 1000}us")
            } else {
                val es = camera.cameraInfo.exposureState
                if (es.isExposureCompensationSupported) {
                    val step = es.exposureCompensationStep
                    val stepEv = step.numerator.toDouble() / step.denominator
                    val idx = Math.round(-1.0 / stepEv).toInt().coerceIn(es.exposureCompensationRange.lower, es.exposureCompensationRange.upper)
                    camera.cameraControl.setExposureCompensationIndex(idx)
                }
                Log.i(TAG, "manual非対応 → 露出補正で代替")
            }
        } catch (e: Exception) { Log.w(TAG, "applyFastShutter failed", e) }
    }

    private fun applyExposure() {
        camCtl?.setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, curExp)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoFixed)
                .build()
        )
    }

    private fun maybeAdjustExposure(bmp: Bitmap, now: Long) {
        camCtl ?: return
        if (now - lastExpMs < 350) return
        lastExpMs = now

        // 手動指定（露光µs＞0 かつ ISO＞0）：センサー範囲にクランプして固定。長秒・低ゲインでノイズ低減可。
        val manualUs = config.exposureUs
        val manualIso = config.iso
        if (manualUs > 0 && manualIso > 0) {
            val e = (manualUs * 1000).coerceIn(sensorExpLo, sensorExpHi)
            val iso = manualIso.coerceIn(isoMin, isoMax)
            if (!manualApplied || e != curExp || iso != isoFixed) {
                curExp = e; isoFixed = iso; manualApplied = true; applyExposure()
                Log.i(TAG, "manual exposure: ${e / 1000}us ISO=$iso")
            }
            return
        }

        // 自動（適応）：手動から戻った直後は ISO を上限に戻す。明るさで露光を上下（ブレ上限内）。
        if (manualApplied) { isoFixed = isoMax; manualApplied = false }
        val b = meanBrightness(bmp)
        val newExp = when {
            b < 85 -> (curExp * 1.5).toLong()
            b > 145 -> (curExp * 0.7).toLong()
            else -> curExp
        }.coerceIn(expLo, expHi)
        if (newExp != curExp) { curExp = newExp; applyExposure() }
    }

    private fun meanBrightness(bmp: Bitmap): Double {
        val s = Bitmap.createScaledBitmap(bmp, 32, 24, true)
        val px = IntArray(32 * 24); s.getPixels(px, 0, 32, 0, 0, 32, 24); s.recycle()
        var sum = 0L
        for (p in px) sum += ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff))
        return sum.toDouble() / (px.size * 3)
    }

    // ---- 画像ユーティリティ ----

    private fun proxyToBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val buf = plane.buffer.apply { rewind() }
        val wPad = plane.rowStride / plane.pixelStride
        val bmp = Bitmap.createBitmap(wPad, proxy.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return if (wPad != proxy.width) Bitmap.createBitmap(bmp, 0, 0, proxy.width, proxy.height) else bmp
    }

    /** 長辺を edge 以下に縮小（拡大はしない）。 */
    private fun scaleToEdge(src: Bitmap, edge: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= edge) return src
        val scale = edge.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    private fun jpegBytes(bmp: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    /** 検出した1コード：表示テキスト("FORMAT: value")＋元画像座標での領域（四隅 or 矩形）。 */
    private data class CodeHit(val text: String, val box: Rect?, val corners: Array<Point>?)

    /** ML Kit で画像内のバーコード/QRを検出（同期・ワーカースレッドから）。テキストと領域を返す。 */
    private fun detectCodes(bmp: Bitmap): List<CodeHit> = try {
        val res = Tasks.await(barcodeScanner.process(InputImage.fromBitmap(bmp, 0)))
        res.mapNotNull { b ->
            val v = b.rawValue ?: b.displayValue
            if (v.isNullOrBlank()) null else CodeHit("${barcodeFormatName(b.format)}: $v", b.boundingBox, b.cornerPoints)
        }
    } catch (e: Exception) {
        Log.w(TAG, "barcode detect failed", e); emptyList()
    }

    /**
     * 検出したコードの領域を画像に可視化（黄枠＋半透明塗り＋IDラベル）。
     * 座標は元画像(src)基準なので scale = 出力幅/src幅 で表示画像へ合わせる。OpenAI送信画像には描かない。
     */
    private fun drawCodeBoxes(shot: Bitmap, hits: List<CodeHit>, scale: Float): Bitmap {
        val out = shot.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val stroke = Paint().apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = max(3f, out.width / 220f); isAntiAlias = true }
        val fill = Paint().apply { color = Color.argb(48, 255, 235, 59); style = Paint.Style.FILL }
        val bg = Paint().apply { color = Color.argb(170, 0, 0, 0) }
        val txt = Paint().apply { color = Color.YELLOW; textSize = max(22f, out.width / 38f); isAntiAlias = true }
        for (h in hits) {
            val path = Path()
            val corners = h.corners
            if (corners != null && corners.size == 4) {
                corners.forEachIndexed { i, p -> val x = p.x * scale; val y = p.y * scale; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                path.close()
            } else h.box?.let { b ->
                path.addRect(b.left * scale, b.top * scale, b.right * scale, b.bottom * scale, Path.Direction.CW)
            }
            c.drawPath(path, fill); c.drawPath(path, stroke)
            // ラベル（ID）。枠の左上に黒帯＋黄文字。長すぎる場合は切り詰め。
            val ax = (corners?.minOf { it.x }?.times(scale)) ?: (h.box?.left?.times(scale)) ?: 0f
            val ay = (corners?.minOf { it.y }?.times(scale)) ?: (h.box?.top?.times(scale)) ?: 0f
            val label = if (h.text.length > 30) h.text.take(29) + "…" else h.text
            val tw = txt.measureText(label); val th = txt.textSize
            val ly = (ay - 8f).coerceAtLeast(th + 4f)
            c.drawRect(ax - 3f, ly - th, ax + tw + 8f, ly + 7f, bg)
            c.drawText(label, ax, ly, txt)
        }
        return out
    }

    private fun barcodeFormatName(fmt: Int): String = when (fmt) {
        Barcode.FORMAT_QR_CODE -> "QR"
        Barcode.FORMAT_DATA_MATRIX -> "DataMatrix"
        Barcode.FORMAT_AZTEC -> "Aztec"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_CODE_128 -> "Code128"
        Barcode.FORMAT_CODE_39 -> "Code39"
        Barcode.FORMAT_CODE_93 -> "Code93"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_EAN_13 -> "EAN13"
        Barcode.FORMAT_EAN_8 -> "EAN8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_ITF -> "ITF"
        else -> "Code"
    }

    private fun showPreview(bmp: Bitmap) {
        val shown = if (bmp.width > 1080) Bitmap.createScaledBitmap(bmp, 1080, bmp.height * 1080 / bmp.width, true) else bmp
        runOnUiThread { previewView.setImageBitmap(shown) }
    }

    private fun ui(msg: String) { runOnUiThread { (findViewById<TextView>(R.id.text)).text = msg } }

    // ---- オフラインキュー（WiFi未接続/通信失敗時に撮影をためて接続時に自動処理）----

    /** インターネット到達可能な有効ネットワークがあるか。 */
    private fun isOnline(): Boolean {
        val n = connManager.activeNetwork ?: return false
        val caps = connManager.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 撮影画像（OpenAI送信用のクリーンJPEG）をキューに保存。接続時に processQueue で再認識する。
     *  撮影時のGPSも一緒に保存し、後で再認識しても元の撮影位置が履歴に残るようにする。 */
    private fun enqueue(jpeg: ByteArray, ts: Long, source: String, loc: Location? = null) {
        runCatching {
            queueDir.mkdirs()
            File(queueDir, "$ts.jpg").writeBytes(jpeg)
            val o = org.json.JSONObject().put("ts", ts).put("source", source)
            if (loc != null) o.put("lat", loc.latitude).put("lon", loc.longitude)
                .put("acc", loc.accuracy.toDouble()).put("locTs", loc.time)
            File(queueDir, QUEUE_JSONL).appendText(o.toString() + "\n")
            Log.i(TAG, "queued offline capture ts=$ts source=$source loc=${loc != null}")
        }.onFailure { Log.w(TAG, "enqueue failed", it) }
        httpServer.setQueued(queueCount())
    }

    private fun queueCount(): Int =
        runCatching { File(queueDir, QUEUE_JSONL).readLines().count { it.isNotBlank() } }.getOrDefault(0)

    /** キューを順に再認識。成功で履歴へ。通信系失敗が出たら残りを保持して中断（次の接続時に再開）。 */
    private fun processQueue() {
        if (processingQueue) return
        if (!isOnline() || !config.hasKey()) return
        val f = File(queueDir, QUEUE_JSONL)
        if (!f.exists()) return
        val items = runCatching {
            f.readLines().filter { it.isNotBlank() }.map { org.json.JSONObject(it) }
        }.getOrElse { emptyList() }
        if (items.isEmpty()) { runCatching { f.delete() }; httpServer.setQueued(0); return }

        processingQueue = true
        try {
            announce("保留中の${items.size}件を読み取ります")
            val keep = ArrayList<org.json.JSONObject>()
            var i = 0
            var ok = 0
            while (i < items.size) {
                val o = items[i]
                val ts = o.getLong("ts")
                val source = o.optString("source", "queued")
                if (!isOnline()) { keep.add(o); i++; continue }
                val img = File(queueDir, "$ts.jpg")
                val bytes = runCatching { img.readBytes() }.getOrNull()
                if (bytes == null) { i++; continue }   // 画像欠落は破棄
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val hits = if (bmp != null) detectCodes(bmp) else emptyList()
                val codes = hits.map { it.text }
                when (val res = openai.read(bytes, codes)) {
                    is OpenAiClient.Result.Ok -> {
                        val thumbSrc = if (bmp != null && hits.isNotEmpty()) drawCodeBoxes(bmp, hits, 1f) else bmp
                        val thumb = if (thumbSrc != null) jpegBytes(scaleToEdge(thumbSrc, HISTORY_EDGE), 80) else bytes
                        httpServer.publish(res.reading, thumb, ts, "$source+queued", codes,
                            if (o.has("lat") && !o.isNull("lat")) o.optDouble("lat") else null,
                            if (o.has("lon") && !o.isNull("lon")) o.optDouble("lon") else null,
                            if (o.has("acc") && !o.isNull("acc")) o.optDouble("acc") else null,
                            if (o.has("locTs") && !o.isNull("locTs")) o.optLong("locTs") else null)
                        lastReading = res.reading
                        runCatching { img.delete() }
                        ok++; i++
                    }
                    is OpenAiClient.Result.Err -> {
                        if (res.retryable) { for (j in i until items.size) keep.add(items[j]); break }  // 通信不良→残し中断
                        else { runCatching { img.delete() }; i++ }   // 恒久エラー（401等）は破棄
                    }
                }
            }
            // 残りで queue.jsonl を書き直し（無ければ削除）
            if (keep.isEmpty()) runCatching { f.delete() }
            else runCatching { f.writeText(keep.joinToString("") { it.toString() + "\n" }) }
            httpServer.setQueued(keep.size)
            if (ok > 0) announce("${ok}件を読み取りました")
            Log.i(TAG, "queue processed: ok=$ok remaining=${keep.size}")
        } finally {
            processingQueue = false
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(debugReceiver) }
        runCatching { mainHandler.removeCallbacks(upTapFinalize) }
        runCatching { connManager.unregisterNetworkCallback(netCallback) }
        runCatching { location.stop() }
        runCatching { mdns.stop() }
        httpServer.stop(); tts?.stop(); tts?.shutdown()
        runCatching { barcodeScanner.close() }
        camExec.shutdown(); netExec.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MeterReader"
        private const val TTS_ENGINE = "ai.fd.josee.app.tts"   // Fairy Josee（オフライン日英TTS）。未導入なら無音継続
        private const val HTTP_PORT = 8080   // 80 等の特権ポートは非root/旧カーネルのため bind 不可(EACCES)。8080 を使用
        private const val FRAME_MS = 66L          // プレビュー更新間隔（≈15fps）
        private const val PREVIEW_PUB_MS = 500L   // HTTP照準ライブの更新間隔
        private const val PREVIEW_HTTP_EDGE = 720
        private const val MAX_UPLOAD_EDGE = 1280  // OpenAIへ送る画像の長辺上限（トークン/遅延を抑制）
        private const val HISTORY_EDGE = 800      // 履歴に保存するサムネ画像の長辺
        private const val MIN_EXP_NS = 250_000L     // 1/4000s
        private const val MAX_EXP_NS = 16_000_000L  // 1/62s（ブラー抑制上限）

        // adb デバッグ：画像を push → このアクションで撮影と同じ推論に流す。
        const val ACTION_DEBUG_READ = "com.example.meterreader.DEBUG_READ"
        const val DEBUG_FILE = "debug.jpg"   // 既定パス: getExternalFilesDir(null)/debug.jpg
        private const val QUEUE_JSONL = "queue.jsonl"   // オフラインキューのメタ（filesDir/queue/）
        private const val MULTI_TAP_WINDOW_MS = 450L    // 音量Up マルチタップの確定待ち時間
        private val DIGITS_JA = arrayOf("ゼロ", "イチ", "ニ", "サン", "ヨン", "ゴ", "ロク", "ナナ", "ハチ", "キュウ")
    }
}
