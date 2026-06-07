package com.example.meterreader

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 端末内蔵の軽量HTTPサーバ（依存ライブラリなし＝GMS非依存を維持）。
 * メータ読み取りの **撮影画像＋認識結果** をディスクに永続化し、ブラウザで履歴閲覧できる。
 *
 * 永続化レイアウト（historyDir = filesDir/history）:
 *   history/records.jsonl  … 1行=1撮影の認識結果（value/unit/type/conf/notes/source/raw/ts）
 *   history/<ts>.jpg       … その撮影のサムネ画像
 * → アプリ/端末を再起動しても履歴は残る。上限 MAX_RECORDS を超えた古い分は画像ごと削除。
 *
 * エンドポイント:
 *  GET  /             ライブ（照準）＋最新値＋直近テーブル（自動更新）
 *  GET  /history      撮影履歴ギャラリー（画像＋認識結果カード, 自動更新, CSV/クリア）
 *  GET  /history.jpg?ts=<ts>  各レコードの撮影画像
 *  GET  /shot.jpg     最新の撮影画像 / GET /preview.jpg 照準用ライブ
 *  GET  /config       設定フォーム / POST /config 設定をJSONで受領
 *  GET  /state.json   ビュー用JSON（最新値＋直近＋設定状態。キーはマスク）
 *  GET  /records.json 全履歴JSON（raw・hasImage 含む） / GET /records.csv CSV
 *  GET  /clear        記録（画像含む）を全消去
 */
class MeterHttpServer(
    private val config: Config,
    private val historyDir: File,
    private val port: Int = 8080,
) {

    data class Rec(
        val ts: Long, val valueText: String, val value: Double?, val unit: String?,
        val meterType: String, val displayType: String, val confidence: Double,
        val ok: Boolean, val notes: String, val source: String, val raw: String,
        val hasImage: Boolean, val codes: List<String>,   // 検出した顧客ID/メーターID（バーコード/QR）
    )

    private val records = CopyOnWriteArrayList<Rec>()
    @Volatile private var latest: Rec? = null
    @Volatile private var previewJpeg: ByteArray? = null   // 照準用ライブ（最新解析フレーム, メモリのみ）
    // カメラの有効範囲（WebUIの露光/ISO入力の目安表示用）。MainActivity から設定。
    @Volatile private var capExpLoUs = 0L
    @Volatile private var capExpHiUs = 0L
    @Volatile private var capIsoMin = 0
    @Volatile private var capIsoMax = 0
    @Volatile private var queued = 0   // オフラインキューの保留件数
    @Volatile private var mdnsName = ""   // mDNS の確定ホスト名（<name>.local, 衝突時は -2 付き）。表示用
    var onConfigChanged: (() -> Unit)? = null   // /config 保存後に呼ばれる（mDNS再登録など）
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    init { runCatching { historyDir.mkdirs() } }

    fun start() {
        if (running) return
        running = true
        pool.execute { acceptLoop() }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    /** 起動時に呼ぶ。永続化済み履歴（records.jsonl）をメモリへ復元。 */
    fun load() {
        val f = File(historyDir, JSONL)
        if (!f.exists()) return
        runCatching {
            val loaded = ArrayList<Rec>()
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching {
                    val o = JSONObject(line)
                    val ts = o.getLong("ts")
                    loaded.add(
                        Rec(
                            ts = ts,
                            valueText = o.optString("valueText"),
                            value = if (o.isNull("value")) null else o.optDouble("value"),
                            unit = if (o.isNull("unit")) null else o.optString("unit").takeIf { it.isNotBlank() },
                            meterType = o.optString("meterType", "unknown"),
                            displayType = o.optString("displayType", "unknown"),
                            confidence = o.optDouble("confidence", 0.0),
                            ok = o.optBoolean("ok", false),
                            notes = o.optString("notes"),
                            source = o.optString("source"),
                            raw = o.optString("raw"),
                            hasImage = File(historyDir, "$ts.jpg").exists(),
                            codes = o.optJSONArray("codes")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
                        )
                    )
                }
            }
            loaded.sortBy { it.ts }
            records.clear(); records.addAll(loaded)
            latest = records.lastOrNull()
            Log.i(TAG, "history loaded: ${records.size} records from $JSONL")
        }.onFailure { Log.w(TAG, "history load failed", it) }
    }

    /** 1回の読み取り（撮影/デバッグ問わず）ごとに、画像をディスク保存＋メタを永続化して1件追加。 */
    fun publish(r: OpenAiClient.MeterReading, jpeg: ByteArray?, ts: Long, source: String, codes: List<String> = emptyList()) {
        var hasImage = false
        if (jpeg != null) {
            hasImage = runCatching { File(historyDir, "$ts.jpg").apply { writeBytes(jpeg) }; true }
                .getOrElse { Log.w(TAG, "image save failed", it); false }
        }
        val rec = Rec(ts, r.valueText, r.value, r.unit, r.meterType, r.displayType,
            r.confidence, r.readingOk, r.notes, source, r.rawJson, hasImage, codes)
        records.add(rec)
        latest = rec
        prune()
        persist()
    }

    /** 照準合わせ用に最新の解析フレームを差し込む（撮影とは無関係に常時更新, メモリのみ）。 */
    fun setPreview(jpeg: ByteArray) { previewJpeg = jpeg }

    /** カメラの有効範囲を設定（WebUI の露光/ISO 入力の目安表示用）。 */
    fun setCameraCaps(expLoUs: Long, expHiUs: Long, isoMin: Int, isoMax: Int) {
        capExpLoUs = expLoUs; capExpHiUs = expHiUs; capIsoMin = isoMin; capIsoMax = isoMax
    }

    /** オフラインキューの保留件数を設定（UI表示用）。 */
    fun setQueued(n: Int) { queued = n }

    /** mDNS ホスト名を設定（UI表示用）。 */
    fun setMdnsName(name: String) { mdnsName = name }

    fun counts(): Int = records.size

    fun clear() {
        records.clear(); latest = null
        runCatching { historyDir.listFiles()?.forEach { it.delete() } }
    }

    /** 上限超過分を古い順に画像ごと削除。 */
    private fun prune() {
        while (records.size > MAX_RECORDS) {
            val old = records.removeAt(0)
            runCatching { File(historyDir, "${old.ts}.jpg").delete() }
        }
    }

    /** records.jsonl を現在のリストから書き直す（数百行なので全書き換えで十分）。 */
    private fun persist() {
        runCatching {
            val sb = StringBuilder()
            for (r in records) sb.append(recJson(r, includeRaw = true)).append('\n')
            File(historyDir, JSONL).writeText(sb.toString())
        }.onFailure { Log.w(TAG, "history persist failed", it) }
    }

    fun urls(): List<String> {
        val out = ArrayList<String>()
        out.add("http://localhost:$port (adb forward tcp:$port tcp:$port 経由)")
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in Collections.list(ni.inetAddresses)) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress)
                        out.add("http://${addr.hostAddress}:$port")
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun acceptLoop() {
        val s = bindWithRetry() ?: return
        server = s
        Log.i(TAG, "HTTP listening on :$port  ->  ${urls().joinToString("  /  ")}")
        while (running) {
            val sock = try { s.accept() } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed", e); break
            }
            pool.execute { handle(sock) }
        }
    }

    private fun bindWithRetry(): ServerSocket? {
        var attempt = 0
        while (running && attempt < BIND_RETRIES) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(port))
                return s
            } catch (e: Exception) {
                attempt++
                Log.w(TAG, "bind :$port failed (try $attempt/$BIND_RETRIES): ${e.message}")
                try { Thread.sleep(BIND_RETRY_MS) } catch (_: InterruptedException) { return null }
            }
        }
        Log.e(TAG, "HTTP server gave up binding :$port")
        return null
    }

    private fun handle(sock: Socket) {
        sock.use {
            try {
                val input = it.getInputStream()
                val reqLine = readLine(input) ?: return
                var contentLength = 0
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    val idx = h.indexOf(':')
                    if (idx > 0 && h.substring(0, idx).trim().equals("Content-Length", true)) {
                        contentLength = h.substring(idx + 1).trim().toIntOrNull() ?: 0
                    }
                }
                val parts = reqLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val pathQuery = if (parts.size >= 2) parts[1] else "/"
                val path = pathQuery.substringBefore('?')
                val query = if (pathQuery.contains('?')) pathQuery.substringAfter('?') else ""
                val out = it.getOutputStream()

                if (method.equals("POST", true) && path == "/config") {
                    handleConfigPost(out, readBody(input, contentLength)); out.flush(); return
                }

                when (path) {
                    "/" -> writeText(out, 200, "text/html; charset=utf-8", INDEX_HTML)
                    "/history" -> writeText(out, 200, "text/html; charset=utf-8", HISTORY_HTML)
                    "/config" -> writeText(out, 200, "text/html; charset=utf-8", configHtml())
                    "/state.json" -> writeText(out, 200, "application/json; charset=utf-8", stateJson())
                    "/records.json" -> writeText(out, 200, "application/json; charset=utf-8", recordsJson())
                    "/records.csv" -> writeBytes(out, 200, "text/csv; charset=utf-8", recordsCsv().toByteArray(Charsets.UTF_8),
                        "Content-Disposition: attachment; filename=\"meter_records.csv\"\r\n")
                    "/clear" -> { clear(); persist(); writeText(out, 200, "application/json; charset=utf-8", "{\"ok\":true}") }
                    "/shot.jpg" -> writeImageOr204(out, latest?.let { r -> File(historyDir, "${r.ts}.jpg") })
                    "/history.jpg" -> {
                        val ts = queryParam(query, "ts")?.toLongOrNull()
                        writeImageOr204(out, ts?.let { t -> File(historyDir, "$t.jpg") })
                    }
                    "/preview.jpg" -> {
                        val j = previewJpeg
                        if (j == null) writeText(out, 204, "text/plain", "") else writeBytes(out, 200, "image/jpeg", j)
                    }
                    else -> writeText(out, 404, "text/plain; charset=utf-8", "not found")
                }
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "handle failed", e)
            }
        }
    }

    private fun handleConfigPost(out: OutputStream, body: String) {
        try {
            val o = JSONObject(body)
            config.update(
                apiKey = o.optString("apiKey").takeIf { o.has("apiKey") },
                model = o.optString("model").takeIf { o.has("model") },
                endpoint = o.optString("endpoint").takeIf { o.has("endpoint") },
                hint = if (o.has("hint")) o.optString("hint") else null,
                exposureUs = if (o.has("exposureUs")) o.optLong("exposureUs") else null,
                iso = if (o.has("iso")) o.optInt("iso") else null,
                deviceName = if (o.has("deviceName")) o.optString("deviceName") else null,
                reasoningEffort = if (o.has("reasoningEffort")) o.optString("reasoningEffort") else null,
            )
            onConfigChanged?.invoke()
            writeText(out, 200, "application/json; charset=utf-8",
                JSONObject().put("ok", true).put("model", config.model).put("key", config.maskedKey())
                    .put("deviceName", config.deviceName).put("reasoningEffort", config.reasoningEffort).toString())
        } catch (e: Exception) {
            Log.w(TAG, "config post failed", e)
            writeText(out, 400, "application/json; charset=utf-8", "{\"ok\":false}")
        }
    }

    private fun writeImageOr204(out: OutputStream, file: File?) {
        if (file == null || !file.exists()) { writeText(out, 204, "text/plain", ""); return }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null) writeText(out, 204, "text/plain", "") else writeBytes(out, 200, "image/jpeg", bytes)
    }

    private fun stateJson(): String {
        val o = JSONObject()
        latest?.let { o.put("latest", recJson(it, includeRaw = false)) }
        o.put("count", records.size)
        o.put("queued", queued)
        o.put("hasShot", latest?.hasImage == true)
        o.put("config", JSONObject()
            .put("model", config.model).put("endpoint", config.endpoint).put("hint", config.hint)
            .put("mdns", mdnsName).put("port", port).put("reasoningEffort", config.reasoningEffort)
            .put("keySet", config.hasKey()).put("keyMasked", config.maskedKey()))
        val arr = JSONArray()
        for (i in records.indices.reversed()) arr.put(recJson(records[i], includeRaw = false))   // 新しい順
        o.put("records", arr)
        return o.toString()
    }

    private fun recordsJson(): String {
        val o = JSONObject()
        val arr = JSONArray()
        for (r in records) arr.put(recJson(r, includeRaw = true))   // 古い順
        o.put("count", records.size)
        o.put("records", arr)
        return o.toString()
    }

    private fun recJson(r: Rec, includeRaw: Boolean): JSONObject {
        val j = JSONObject()
            .put("ts", r.ts).put("valueText", r.valueText)
            .put("value", r.value ?: JSONObject.NULL).put("unit", r.unit ?: JSONObject.NULL)
            .put("meterType", r.meterType).put("displayType", r.displayType)
            .put("confidence", r.confidence).put("ok", r.ok).put("notes", r.notes)
            .put("source", r.source).put("hasImage", r.hasImage)
            .put("codes", JSONArray(r.codes))
        if (includeRaw) j.put("raw", r.raw)
        return j
    }

    private fun recordsCsv(): String {
        val sb = StringBuilder("ts,datetime,value_text,value,unit,meter_type,display_type,confidence,ok,notes,source,codes\n")
        for (r in records) {
            sb.append(r.ts).append(',').append(csv(fmtTs(r.ts))).append(',')
              .append(csv(r.valueText)).append(',').append(r.value?.toString() ?: "").append(',')
              .append(csv(r.unit ?: "")).append(',').append(csv(r.meterType)).append(',').append(csv(r.displayType)).append(',')
              .append(r.confidence).append(',').append(r.ok).append(',').append(csv(r.notes)).append(',').append(csv(r.source)).append(',')
              .append(csv(r.codes.joinToString(" | "))).append('\n')
        }
        return sb.toString()
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun fmtTs(ts: Long): String =
        runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts)) }.getOrDefault("")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun queryParam(query: String, name: String): String? =
        query.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var read = 0
        while (true) {
            val c = input.read()
            if (c == -1) { if (read == 0) return null else break }
            read++
            if (c == '\n'.code) break
            if (c != '\r'.code) buf.write(c)
        }
        return buf.toString("UTF-8")
    }

    private fun readBody(input: InputStream, len: Int): String {
        if (len <= 0) return ""
        val data = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(data, off, len - off)
            if (n < 0) break
            off += n
        }
        return String(data, 0, off, Charsets.UTF_8)
    }

    private fun writeText(out: OutputStream, code: Int, ctype: String, body: String) =
        writeBytes(out, code, ctype, body.toByteArray(Charsets.UTF_8))

    private fun writeBytes(out: OutputStream, code: Int, ctype: String, body: ByteArray, extra: String = "") {
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "OK" }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $ctype\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-store\r\n" + extra +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
    }

    /** 露光/ISO入力の目安（カメラから取得済みの場合のみ）。 */
    private fun camCapsHint(): String =
        if (capExpHiUs > 0) "<br>有効範囲: 露光 ${capExpLoUs}〜${capExpHiUs} µs / ISO ${capIsoMin}〜${capIsoMax}" else ""

    /** モデル選択の <option> 群（現在値を先頭＋既定プリセット＋カスタム）。 */
    private fun modelOptionsHtml(): String {
        val models = (listOf(config.model) + Config.MODEL_PRESETS).filter { it.isNotBlank() }.distinct()
        val opts = models.joinToString("") { m ->
            "<option value=\"${esc(m)}\"${if (m == config.model) " selected" else ""}>${esc(m)}</option>"
        }
        return opts + "<option value=\"__custom__\">カスタム入力…</option>"
    }

    /** reasoning_effort 選択の <option> 群。 */
    private fun effortOptionsHtml(): String {
        val items = listOf(
            "" to "（指定なし＝モデル既定）", "minimal" to "minimal（最速・最安）",
            "low" to "low", "medium" to "medium", "high" to "high（最も丁寧）",
        )
        return items.joinToString("") { (v, l) ->
            "<option value=\"$v\"${if (v == config.reasoningEffort) " selected" else ""}>${esc(l)}</option>"
        }
    }

    private fun configHtml(): String {
        return """
<!doctype html><html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Meter Reader 設定</title>
<style>
 body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#111;color:#eee}
 header{padding:12px 16px;background:#1b1b1b;border-bottom:1px solid #333}
 header h1{font-size:16px;margin:0}
 .wrap{max-width:560px;margin:0 auto;padding:16px}
 label{display:block;margin:14px 0 4px;color:#9aa;font-size:13px}
 input,textarea{width:100%;box-sizing:border-box;background:#000;color:#eee;border:1px solid #444;border-radius:8px;padding:10px;font-size:15px}
 .row{color:#9aa;font-size:13px;margin:6px 0}
 .btn{margin-top:16px;background:#2563eb;border:0;color:#fff;border-radius:8px;padding:11px 18px;font-size:15px;cursor:pointer}
 .btn:hover{background:#1d4ed8}
 a{color:#6ab7ff}
 #msg{margin-top:12px;font-size:14px}
</style></head><body>
<header><h1>Meter Reader 設定</h1></header>
<div class="wrap">
 <div class="row">現在: モデル <b>${esc(config.model)}</b> / APIキー <b>${esc(config.maskedKey())}</b></div>
 <label>OpenAI APIキー（空のままなら変更しません）</label>
 <input id="apiKey" type="password" placeholder="sk-...">
 <label>モデル（選択、または「カスタム入力…」で自由記述）</label>
 <select id="modelSel" onchange="onModelSel()">${modelOptionsHtml()}</select>
 <input id="modelCustom" type="text" placeholder="カスタムモデルID（OpenAI互換サーバのIDも可）" style="display:none;margin-top:6px">
 <label>reasoning 強度（推論の強さ。指定なし＝モデル既定。OpenAI互換で未対応なら指定なし）</label>
 <select id="reasoningEffort">${effortOptionsHtml()}</select>
 <label>エンドポイント（OpenAI互換サーバも可）</label>
 <input id="endpoint" type="text" value="${esc(config.endpoint)}">
 <label>読み取りヒント（任意。例: 水道メータ 単位m3 / 黒地に白数字の積算計）</label>
 <textarea id="hint" rows="2">${esc(config.hint)}</textarea>

 <div class="row" style="margin-top:18px;border-top:1px solid #333;padding-top:12px">デバイス名（mDNS）。既定は端末固有 <code>meter-XXXX</code>。<b>同一LANで複数台を同名にすると衝突します（自動回避は不確実）</b>。単一台なら <code>meter</code> 等の短い名前でOK。英小文字・数字・ハイフンのみ${if (mdnsName.isNotBlank()) "<br>現在の公開名: <b>http://${esc(mdnsName)}:$port/</b>" else ""}</div>
 <label>デバイス名</label>
 <input id="deviceName" type="text" value="${esc(config.deviceName)}" placeholder="meter">

 <div class="row" style="margin-top:18px;border-top:1px solid #333;padding-top:12px">カメラ露出（ノイズを減らすなら<b>露光時間を長く・ISOを低く</b>。0=自動）${camCapsHint()}</div>
 <label>露光時間（マイクロ秒, 0=自動）</label>
 <input id="exposureUs" type="number" min="0" value="${config.exposureUs}">
 <label>ISO感度（0=自動）</label>
 <input id="iso" type="number" min="0" value="${config.iso}">

 <button class="btn" onclick="save()">保存</button>
 <div id="msg"></div>
 <div class="row" style="margin-top:18px"><a href="/">← 読み取り画面</a> ・ <a href="/history">履歴</a></div>
</div>
<script>
function onModelSel(){document.getElementById('modelCustom').style.display=(document.getElementById('modelSel').value=='__custom__')?'block':'none';}
function currentModel(){var s=document.getElementById('modelSel').value;return s=='__custom__'?document.getElementById('modelCustom').value.trim():s;}
function save(){
 var b={apiKey:document.getElementById('apiKey').value,
        model:currentModel(),
        endpoint:document.getElementById('endpoint').value,
        hint:document.getElementById('hint').value,
        reasoningEffort:document.getElementById('reasoningEffort').value,
        exposureUs:parseInt(document.getElementById('exposureUs').value||'0',10),
        iso:parseInt(document.getElementById('iso').value||'0',10),
        deviceName:document.getElementById('deviceName').value};
 fetch('/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)})
  .then(function(r){return r.json();})
  .then(function(j){document.getElementById('msg').textContent=j.ok?('保存しました（モデル '+j.model+' / キー '+j.key+'）'):'保存に失敗しました';
        document.getElementById('apiKey').value='';})
  .catch(function(){document.getElementById('msg').textContent='通信に失敗しました';});
}
</script></body></html>
""".trimIndent()
    }

    companion object {
        private const val TAG = "MeterReader"
        private const val MAX_RECORDS = 300       // これを超えた古い撮影は画像ごと削除
        private const val JSONL = "records.jsonl"
        private const val BIND_RETRIES = 12
        private const val BIND_RETRY_MS = 400L

        // ライブ（照準）＋最新値＋直近テーブル。/state.json を ~700ms ポーリングして自動更新。
        private val INDEX_HTML = """
<!doctype html><html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Meter Reader</title>
<style>
 body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#111;color:#eee}
 header{padding:12px 16px;background:#1b1b1b;border-bottom:1px solid #333;display:flex;align-items:center;gap:10px}
 header h1{font-size:16px;margin:0;font-weight:600}
 .wrap{display:flex;flex-wrap:wrap;gap:16px;padding:16px;align-items:flex-start}
 .card{background:#1b1b1b;border:1px solid #333;border-radius:10px;padding:14px}
 .live{flex:1;min-width:300px}.log{flex:1.6;min-width:360px}
 .h{font-size:13px;color:#9aa;font-weight:600;margin:0 0 10px;text-transform:uppercase;letter-spacing:.04em}
 img.cam{max-width:100%;border-radius:8px;background:#000;display:block;margin-bottom:8px}
 .val{font-size:34px;font-weight:700;margin:8px 0 2px;word-break:break-word}
 .meta{color:#9aa;font-size:13px}
 table{border-collapse:collapse;width:100%;font-size:13px}
 th,td{border-bottom:1px solid #2a2a2a;padding:6px 8px;text-align:left;vertical-align:top}
 th{color:#9aa;font-weight:600}
 .empty{color:#777;padding:16px 0}
 .dot{width:9px;height:9px;border-radius:50%;background:#3c3;display:inline-block}
 .sp{margin-left:auto;display:flex;gap:8px;align-items:center}
 .btn{background:#2a2a2a;border:1px solid #444;color:#ddd;border-radius:7px;padding:6px 12px;font-size:13px;text-decoration:none;cursor:pointer}
 .btn:hover{background:#333}
 .ng{color:#f87171}.ok{color:#4ade80}
 .warn{background:#3b2a00;color:#fbbf24;padding:8px 12px;border-radius:7px;margin:0 16px;font-size:13px}
</style></head><body>
<header><span class="dot"></span><h1>Meter Reader</h1><span class="meta" id="cnt"></span>
 <span class="sp"><span class="meta" id="cfg"></span>
 <a class="btn" href="/history">履歴</a>
 <a class="btn" href="/config">設定</a>
 <a class="btn" href="/records.csv" download>CSV</a></span></header>
<div id="keywarn" class="warn" style="display:none">APIキーが未設定です。<a href="/config">設定ページ</a>で入力してください。</div>
<div class="wrap">
 <div class="card live"><div class="h">ライブ（照準）</div><img id="prev" class="cam" alt="preview">
   <div class="h">直近の撮影</div><img id="shot" class="cam" alt="shot">
   <div class="val" id="val">&mdash;</div><div class="meta" id="vmeta"></div></div>
 <div class="card log"><div class="h">直近の読み取り <span class="meta" id="hcnt"></span>
   <a class="btn" href="/history" style="float:right">すべての履歴 →</a></div>
   <table><thead><tr><th>#</th><th>日時</th><th>value</th><th>unit</th><th>type</th><th>conf</th></tr></thead>
   <tbody id="rows"></tbody></table>
   <div class="empty" id="empty">まだ読み取りがありません（端末の音量Downで撮影）</div></div>
</div>
<script>
function esc(s){var d=document.createElement('div');d.textContent=(s==null?'':String(s));return d.innerHTML;}
function ftime(ts){try{return new Date(ts).toLocaleString();}catch(e){return '';}}
function fconf(c){return (c==null)?'':Math.round(c*100)+'%';}
function tick(){
 document.getElementById('prev').src='/preview.jpg?t='+Date.now();
 fetch('/state.json',{cache:'no-store'}).then(function(r){return r.json();}).then(function(s){
  var l=s.latest;
  document.getElementById('shot').src='/shot.jpg?t='+(l?l.ts:0);
  document.getElementById('val').innerHTML=l?(esc(l.valueText||(l.value!=null?l.value:'?'))+' '+esc(l.unit||'')):'—';
  var ids=(l&&l.codes&&l.codes.length)?('  /  ID: '+l.codes.map(esc).join(', ')):'';
  document.getElementById('vmeta').innerHTML=l?((l.ok?'<span class=ok>OK</span>':'<span class=ng>要確認</span>')+'  /  '+esc(l.meterType)+'/'+esc(l.displayType)+'  /  '+fconf(l.confidence)+ids+'  /  '+ftime(l.ts)+(l.notes?('  /  '+esc(l.notes)):'')):'';
  var cf=s.config||{};
  document.getElementById('cfg').textContent='model: '+(cf.model||'')+' / key: '+(cf.keyMasked||'')+(cf.mdns?(' / '+cf.mdns+((cf.port&&cf.port!=80)?(':'+cf.port):'')):'');
  document.getElementById('keywarn').style.display=cf.keySet?'none':'block';
  document.getElementById('cnt').textContent=(s.count||0)+' records'+((s.queued||0)>0?('  /  保留 '+s.queued+'件（オフライン）'):'');
  var rows=(s.records||[]).slice(0,12);
  document.getElementById('empty').style.display=rows.length?'none':'block';
  document.getElementById('hcnt').textContent='('+(s.count||0)+')';
  var hh='';
  for(var j=0;j<rows.length;j++){var r=rows[j];
   hh+='<tr><td>'+((s.count||0)-j)+'</td><td>'+ftime(r.ts)+'</td><td><b>'+esc(r.valueText||(r.value!=null?r.value:''))+'</b></td><td>'+esc(r.unit||'')+'</td><td>'+esc(r.meterType)+'</td><td>'+fconf(r.confidence)+(r.ok?'':' <span class=ng>!</span>')+'</td></tr>';}
  document.getElementById('rows').innerHTML=hh;
 }).catch(function(){}).then(function(){setTimeout(tick,700);});
}
tick();
</script></body></html>
""".trimIndent()

        // 撮影履歴ギャラリー：各撮影の画像＋認識結果（生JSON含む）をカード表示。/records.json を ~3s ポーリング。
        private val HISTORY_HTML = """
<!doctype html><html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Meter Reader 履歴</title>
<style>
 body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#111;color:#eee}
 header{padding:12px 16px;background:#1b1b1b;border-bottom:1px solid #333;display:flex;align-items:center;gap:10px;position:sticky;top:0}
 header h1{font-size:16px;margin:0;font-weight:600}
 .meta{color:#9aa;font-size:13px}
 .sp{margin-left:auto;display:flex;gap:8px;align-items:center}
 .btn{background:#2a2a2a;border:1px solid #444;color:#ddd;border-radius:7px;padding:6px 12px;font-size:13px;text-decoration:none;cursor:pointer}
 .btn:hover{background:#333}
 .list{padding:16px;display:flex;flex-direction:column;gap:12px;max-width:900px;margin:0 auto}
 .card{display:flex;gap:14px;background:#1b1b1b;border:1px solid #333;border-radius:10px;padding:12px}
 .th{width:200px;height:150px;object-fit:cover;border-radius:8px;background:#000;flex:none}
 .noimg{width:200px;height:150px;display:flex;align-items:center;justify-content:center;color:#666;background:#000;border-radius:8px;flex:none;font-size:13px}
 .info{flex:1;min-width:0}
 .v{font-size:30px;font-weight:700;margin:0 0 6px;word-break:break-word}
 .badges{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:6px}
 .tag{background:#222;border:1px solid #3a3a3a;border-radius:6px;padding:2px 8px;font-size:12px;color:#cbd5e1}
 .ok{color:#4ade80;font-weight:700}.ng{color:#f87171;font-weight:700}
 .conf{color:#9aa;font-size:13px}
 .ids{margin:4px 0;font-size:14px;color:#ddd}.ids b{color:#fbbf24}
 .time{color:#9aa;font-size:13px;margin-bottom:6px}
 .notes{color:#ddd;font-size:14px;margin:6px 0;white-space:pre-wrap}
 details{margin-top:6px}summary{color:#6ab7ff;cursor:pointer;font-size:13px}
 pre{white-space:pre-wrap;word-break:break-all;background:#000;border:1px solid #333;border-radius:6px;padding:8px;font-size:12px;color:#9fe0a0}
 .empty{color:#777;padding:40px 0;text-align:center}
</style></head><body>
<header><h1>撮影履歴</h1><span class="meta" id="cnt"></span>
 <span class="sp"><a class="btn" href="/">← ライブ</a><a class="btn" href="/config">設定</a>
 <a class="btn" href="/records.csv" download>CSV</a><button class="btn" onclick="doClear()">クリア</button></span></header>
<div class="list" id="list"><div class="empty">読み込み中…</div></div>
<script>
function esc(s){var d=document.createElement('div');d.textContent=(s==null?'':String(s));return d.innerHTML;}
function fconf(c){return (c==null)?'':Math.round(c*100)+'%';}
function doClear(){if(confirm('履歴（画像を含む）をすべて消去しますか？'))fetch('/clear',{cache:'no-store'}).then(tick);}
function card(r){
 var v=esc(r.valueText||(r.value!=null?r.value:'?')), u=esc(r.unit||'');
 var img=r.hasImage?('<img class=th loading=lazy src="/history.jpg?ts='+r.ts+'">'):'<div class=noimg>画像なし</div>';
 var ok=r.ok?'<span class=ok>OK</span>':'<span class=ng>要確認</span>';
 var t=''; try{t=new Date(r.ts).toLocaleString();}catch(e){}
 var ids=(r.codes&&r.codes.length)?('<div class=ids><b>ID:</b> '+r.codes.map(esc).join(', ')+'</div>'):'';
 return '<div class=card>'+img+'<div class=info>'+
   '<div class=v>'+v+' '+u+'</div>'+
   '<div class=badges>'+ok+' <span class=conf>'+fconf(r.confidence)+'</span>'+
     ' <span class=tag>'+esc(r.meterType)+' / '+esc(r.displayType)+'</span>'+
     ' <span class=tag>'+esc(r.source)+'</span></div>'+
   ids+
   '<div class=time>'+t+'</div>'+
   (r.notes?'<div class=notes>'+esc(r.notes)+'</div>':'')+
   (r.raw?'<details><summary>認識結果(生JSON)</summary><pre>'+esc(r.raw)+'</pre></details>':'')+
   '</div></div>';
}
function tick(){
 fetch('/records.json',{cache:'no-store'}).then(function(r){return r.json();}).then(function(s){
  var rows=s.records||[]; document.getElementById('cnt').textContent=rows.length+' 件';
  var h=''; for(var i=rows.length-1;i>=0;i--) h+=card(rows[i]);
  document.getElementById('list').innerHTML=h||'<div class=empty>まだ履歴がありません（端末の音量Downで撮影）</div>';
 }).catch(function(){}).then(function(){setTimeout(tick,3000);});
}
tick();
</script></body></html>
""".trimIndent()
    }
}
