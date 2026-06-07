package com.example.meterreader

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI Chat Completions（Vision＋Structured Outputs）クライアント。
 * 追加依存を持たず `HttpURLConnection`＋`org.json` のみで実装（GMS非依存・APK軽量）。
 *
 * 画像を base64 data URL で送り、`response_format=json_schema(strict)` で
 * メータ読み取り結果を厳密JSONとして受け取る。
 */
class OpenAiClient(private val config: Config) {

    /** 読み取り結果（スキーマと1:1）。 */
    data class MeterReading(
        val meterType: String,
        val displayType: String,
        val value: Double?,
        val valueText: String,
        val unit: String?,
        val confidence: Double,
        val readingOk: Boolean,
        val notes: String,
        val rawJson: String,
    )

    /** 成功＝Ok、失敗＝Err（message は日本語でTTS/画面に出せる文言）。retryable=true は後で再試行する価値あり（ネットワーク/一時障害）。 */
    sealed class Result {
        data class Ok(val reading: MeterReading) : Result()
        data class Err(val message: String, val retryable: Boolean = false) : Result()
    }

    /**
     * JPEG画像を送ってメータを読み取る。ネットワークI/Oを含むので**ワーカースレッドから**呼ぶこと。
     * codes は同じ画像から ML Kit で検出したバーコード/QR（顧客ID・メーターIDの可能性が高い）を文脈として渡す。
     */
    fun read(jpeg: ByteArray, codes: List<String> = emptyList()): Result {
        if (!config.hasKey()) return Result.Err("APIキーが未設定です。設定ページで入力してください")
        val body = buildRequest(jpeg, codes)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 45_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")  // ★ログに出さない
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = readStream(conn.errorStream ?: conn.inputStream)
                Log.w(TAG, "OpenAI HTTP $code: ${err.take(500)}")
                val retryable = code == 429 || code in 500..599   // 一時障害は後で再試行
                return Result.Err(httpErrorMessage(code, err), retryable)
            }
            val resp = readStream(conn.inputStream)
            parseResponse(resp)
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "network: unknown host", e)
            Result.Err("ネットワークに接続できません", retryable = true)
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "network: timeout", e)
            Result.Err("通信がタイムアウトしました", retryable = true)
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e)
            Result.Err("通信エラーが発生しました", retryable = true)
        } finally {
            conn?.disconnect()
        }
    }

    private fun buildRequest(jpeg: ByteArray, codes: List<String>): String {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$b64"

        val userText = buildString {
            append("画像のメータを読み取り規則に従って読み取り、結果をJSONスキーマで返してください。")
            if (codes.isNotEmpty()) {
                append("\n参考: 同じ画像から検出されたバーコード/QRコード（顧客IDまたはメーターIDの可能性が高い。メータの表示値ではない）: ")
                append(codes.joinToString(" / "))
                append("。これらは値の読み取りには使わず、文脈情報として扱ってください。")
            }
            val h = config.hint
            if (h.isNotBlank()) append("\nヒント: $h")
        }

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", userText))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", dataUrl).put("detail", "high")
                )
            )

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", content))

        val req = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("response_format", JSONObject(RESPONSE_FORMAT_JSON))
            .put("max_completion_tokens", 800)
        // reasoning_effort は指定がある時だけ付与（未対応モデル/OpenAI互換サーバでも安全）
        if (config.reasoningEffort.isNotBlank()) req.put("reasoning_effort", config.reasoningEffort)
        return req.toString()
    }

    private fun parseResponse(resp: String): Result {
        return try {
            val root = JSONObject(resp)
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
                ?: return Result.Err("応答が空でした")
            val message = choice.optJSONObject("message")
            val refusal = message?.optString("refusal")?.takeIf { it.isNotBlank() && it != "null" }
            if (refusal != null) return Result.Err("読み取りを拒否されました")
            val contentStr = message?.optString("content")?.trim().orEmpty()
            if (contentStr.isEmpty()) {
                val finish = choice.optString("finish_reason")
                return Result.Err(if (finish == "length") "応答が長すぎて切れました" else "メータを認識できませんでした")
            }
            val o = JSONObject(contentStr)
            val reading = MeterReading(
                meterType = o.optString("meter_type", "unknown"),
                displayType = o.optString("display_type", "unknown"),
                value = if (o.isNull("value")) null else o.optDouble("value").let { if (it.isNaN()) null else it },
                valueText = o.optString("value_text", ""),
                unit = if (o.isNull("unit")) null else o.optString("unit").takeIf { it.isNotBlank() },
                confidence = o.optDouble("confidence", 0.0),
                readingOk = o.optBoolean("reading_ok", false),
                notes = o.optString("notes", ""),
                rawJson = contentStr,
            )
            Result.Ok(reading)
        } catch (e: Exception) {
            Log.w(TAG, "parse failed: ${resp.take(300)}", e)
            Result.Err("応答の解析に失敗しました")
        }
    }

    private fun httpErrorMessage(code: Int, body: String): String {
        val apiMsg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        return when (code) {
            401 -> "APIキーが無効です"
            404 -> "モデルが見つかりません（設定のモデル名を確認）"
            429 -> "レート制限/残高不足です"
            in 500..599 -> "OpenAI サーバエラー（$code）"
            else -> "APIエラー $code" + (apiMsg?.let { "：${it.take(80)}" } ?: "")
        }
    }

    private fun readStream(input: java.io.InputStream?): String {
        input ?: return ""
        return BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
    }

    companion object {
        private const val TAG = "MeterReader"

        private const val SYSTEM_PROMPT =
            "あなたは産業・家庭用メータ（水道・ガス・電力・圧力・温度など）の表示値を読み取る専門アシスタントです。" +
            "画像内の計器を観察し、推測を最小化して正確に読み取り、必ず指定のJSONスキーマだけを出力します。\n" +
            "\n" +
            "読み取り規則:\n" +
            "1. 計測レジスタ（主表示）だけを読む。製造番号・型番・定格・バーコード・住所等は値として読まない。\n" +
            "2. 機械式オドメータ（数字ホイール）は左から右へ全桁読む。先頭の0も省略しない。" +
            "桁が回転途中（2数字の境界）にある場合は、完全に切り替わっていなければ小さい方の数字を採用する。" +
            "色や枠が異なる末尾桁は小数部のことが多い。確信が持てなければ notes に明記する。\n" +
            "3. 指針式の補助ダイヤル（leak/test 用の小さな赤針など）は主表示と混同しない。主レジスタを優先する。\n" +
            "4. アナログ円形ゲージは目盛と針位置から読み、目盛間隔・単位を考慮する。\n" +
            "5. 単位は文字盤に印字された表記を優先（例: m3, ㎥, CUBIC FEET, kWh, imp/kWh, MPa, ℃）。" +
            "印字が無ければ unit=null とし、勝手に作らない。\n" +
            "6. meter_type は文字盤の表記や文脈が明確な場合のみ断定する（例: kWh→electric）。" +
            "\"CUBIC FEET\" 等で水道かガスか判別できない場合は unknown を選ぶ（推測しない）。\n" +
            "7. value_text は表示そのまま（先頭0・区切りも保持）。value は数値（桁区切りを除いた数）。" +
            "表示が日時・非計測値で数値化できない場合は value=null, reading_ok=false とし、notes に理由を書く。\n" +
            "8. 反射・汚れ・見切れで読めない桁があれば reading_ok=false にし、読めた範囲を value_text に入れて notes で説明する。\n" +
            "9. 画像に複数の計器があれば、最も大きく中央に写る主計器を読み、他は notes に簡潔に記す。\n" +
            "\n" +
            "confidence の目安: 0.95-1.0=鮮明なデジタル/全桁明瞭, 0.7-0.94=読めるが軽微な不確か, " +
            "0.4-0.69=反射・回転途中・一部不鮮明, 0.0-0.39=ほぼ判読不能。" +
            "reading_ok は概ね confidence>=0.7 で確信できる時のみ true。notes は日本語で簡潔に。"

        // Structured Outputs（strict）。全プロパティ required・additionalProperties:false が必須。
        // null許容は type を配列にする（例: ["number","null"]）。
        private const val RESPONSE_FORMAT_JSON = """
{
  "type": "json_schema",
  "json_schema": {
    "name": "meter_reading",
    "strict": true,
    "schema": {
      "type": "object",
      "additionalProperties": false,
      "required": ["meter_type","display_type","value","value_text","unit","confidence","reading_ok","notes"],
      "properties": {
        "meter_type": {"type":"string","enum":["water","gas","electric","pressure","temperature","other","unknown"],"description":"文字盤の表記/文脈から断定できる場合のみ選ぶ。判別不能なら unknown"},
        "display_type": {"type":"string","enum":["digital","odometer","analog_dial","unknown"],"description":"表示形式。液晶=digital, 数字ホイール=odometer, 針=analog_dial"},
        "value": {"type":["number","null"],"description":"数値（桁区切りを除いた数）。数値化できなければ null"},
        "value_text": {"type":"string","description":"表示そのままの読み（先頭0や区切りも保持。例: 00263.5）"},
        "unit": {"type":["string","null"],"description":"文字盤に印字された単位（m3, ㎥, CUBIC FEET, kWh, MPa, ℃ 等）。印字が無ければ null（捏造しない）"},
        "confidence": {"type":"number","description":"0.0〜1.0。0.95+=鮮明, 0.7+=軽微な不確か, 0.4+=一部不鮮明, 未満=判読困難"},
        "reading_ok": {"type":"boolean","description":"概ね confidence>=0.7 で確信できる時のみ true"},
        "notes": {"type":"string","description":"日本語で簡潔に。曖昧さ・補助ダイヤル・複数計器・読めない理由・回転途中の桁など"}
      }
    }
  }
}
"""
    }
}
