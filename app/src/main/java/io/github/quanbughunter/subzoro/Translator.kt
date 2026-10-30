package io.github.quanbughunter.subzoro

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Dịch câu sang tiếng Việt bằng Google Dịch (cách extension bản cá nhân đang dùng). Cần mạng. */
object Translator {
    fun translate(text: String, sl: String, tl: String = "vi"): String? {
        return try {
            val q = URLEncoder.encode(text, "UTF-8")
            val c = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$q")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode != 200) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val parts = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONArray(i) ?: continue
                sb.append(p.optString(0))
            }
            sb.toString().trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
