package io.github.quanbughunter.subzoro

import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Dịch câu sang tiếng Việt.
 *   1. Ngay trên máy bằng ML Kit của Google (gói dịch ~30 MB mỗi thứ tiếng, tự tải lần đầu, sau đó không cần mạng).
 *   2. Không được thì gọi Google Dịch qua mạng (hay bị chặn khi dùng VPN hoặc mạng bị Google nghi là robot).
 * Gọi trên luồng nền.
 */
object Translator {
    /** Trạng thái để hiện trên bong bóng: đang tải gói dịch / lỗi gần nhất. */
    @Volatile var status: String? = null
        private set

    private val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val readyLangs = HashSet<String>()
    @Volatile private var mlkitFailedAt = 0L

    private fun mlkitCode(lang: String) = when (lang) {
        "zh" -> TranslateLanguage.CHINESE
        "ru" -> TranslateLanguage.RUSSIAN
        else -> TranslateLanguage.ENGLISH
    }

    fun translate(text: String, lang: Lang): String? {
        if (text.isBlank()) return null
        // 1) trên máy (bỏ qua 5 phút nếu vừa hỏng, để không chờ mãi từng câu)
        if (SystemClock.elapsedRealtime() - mlkitFailedAt > 5 * 60_000 || mlkitFailedAt == 0L) {
            try {
                val client = synchronized(clients) {
                    clients.getOrPut(lang.code) {
                        Translation.getClient(
                            TranslatorOptions.Builder()
                                .setSourceLanguage(mlkitCode(lang.code))
                                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                                .build()
                        )
                    }
                }
                if (!synchronized(readyLangs) { lang.code in readyLangs }) {
                    status = "Đang tải gói dịch ${lang.name} → tiếng Việt (~30 MB, chỉ lần đầu)…"
                    Tasks.await(client.downloadModelIfNeeded(DownloadConditions.Builder().build()), 240, TimeUnit.SECONDS)
                    synchronized(readyLangs) { readyLangs.add(lang.code) }
                }
                val out = Tasks.await(client.translate(text), 20, TimeUnit.SECONDS)
                status = null
                mlkitFailedAt = 0L
                if (!out.isNullOrBlank()) return out.trim()
            } catch (e: InterruptedException) {
                // bong bóng vừa tắt giữa chừng: không phải lỗi của bộ dịch
                status = null
                return null
            } catch (e: Exception) {
                mlkitFailedAt = SystemClock.elapsedRealtime()
                status = "Dịch trên máy chưa được (${e.cause?.message ?: e.message ?: e.javaClass.simpleName}) — thử Google Dịch qua mạng."
            }
        }
        // 2) qua mạng
        val g = gtx(text, lang.gtx)
        if (g != null) {
            status = null
            return g
        }
        status = "Không dịch được: gói dịch trên máy chưa tải được và Google Dịch qua mạng bị chặn. Thử tắt/bật VPN."
        return null
    }

    private fun gtx(text: String, sl: String): String? {
        return try {
            val q = URLEncoder.encode(text, "UTF-8")
            val c = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=vi&dt=t&q=$q")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.instanceFollowRedirects = false        // bị chuyển sang trang xác minh robot = bị chặn
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

    fun close() {
        synchronized(clients) {
            for (c in clients.values) try { c.close() } catch (e: Exception) { }
            clients.clear()
        }
    }
}
