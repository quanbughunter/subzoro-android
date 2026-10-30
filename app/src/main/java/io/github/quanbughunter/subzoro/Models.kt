package io.github.quanbughunter.subzoro

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Một gói mô hình tải về máy.
 *   - Vosk small (zip, ~40 MB/thứ tiếng): nghe nhanh, chữ hiện ngay theo từng từ.
 *   - SenseVoice (Trung + Anh) / GigaAM v2 (Nga) qua sherpa-onnx (tar.bz2, ~165 MB): nghe lại từng câu cho chính xác.
 */
data class Pack(
    val id: String,
    val name: String,
    val url: String,
    val mb: Int,
    val dirName: String,
    val tarBz2: Boolean,
    val required: List<String>
)

object Packs {
    private const val VOSK = "https://alphacephei.com/vosk/models/"
    private const val SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    private fun vosk(code: String, name: String, id: String, mb: Int) =
        Pack("vosk-$code", name, "$VOSK$id.zip", mb, id, false, listOf("am", "conf"))

    val VOSK_PACKS = mapOf(
        "en" to vosk("en", "Vosk tiếng Anh", "vosk-model-small-en-us-0.15", 40),
        "ru" to vosk("ru", "Vosk tiếng Nga", "vosk-model-small-ru-0.22", 45),
        "zh" to vosk("zh", "Vosk tiếng Trung", "vosk-model-small-cn-0.22", 42)
    )

    val SENSEVOICE = Pack(
        "sensevoice", "SenseVoice (Trung + Anh)",
        SHERPA + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2", 163,
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17", true, listOf("model.int8.onnx", "tokens.txt")
    )

    val GIGAAM = Pack(
        "gigaam", "GigaAM v2 (Nga)",
        SHERPA + "sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19.tar.bz2", 167,
        "sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19", true, listOf("model.int8.onnx", "tokens.txt")
    )

    val ALL = VOSK_PACKS.values.toList() + listOf(SENSEVOICE, GIGAAM)

    fun vosk(lang: String): Pack = VOSK_PACKS[lang] ?: VOSK_PACKS.getValue("en")

    /** Mô hình nghe chính xác cho ngôn ngữ này (null nếu chưa có). */
    fun accurate(lang: String): Pack? = when (lang) {
        "zh", "en" -> SENSEVOICE
        "ru" -> GIGAAM
        else -> null
    }

    private fun root(ctx: Context) = File(ctx.filesDir, "models")

    fun dir(ctx: Context, p: Pack) = File(root(ctx), p.dirName)

    fun ready(ctx: Context, p: Pack): Boolean {
        val d = dir(ctx, p)
        return File(d, ".ok").exists() && p.required.all { File(d, it).exists() }
    }

    fun delete(ctx: Context, p: Pack) {
        dir(ctx, p).deleteRecursively()
        partFile(ctx, p).delete()
    }

    private fun partFile(ctx: Context, p: Pack) = File(ctx.cacheDir, p.dirName + if (p.tarBz2) ".tar.bz2" else ".zip")

    /** Chạy trên luồng nền. onProgress(đã tải, tổng) theo byte; tổng = -1 nếu máy chủ không báo, -2 khi đang giải nén. */
    fun download(ctx: Context, p: Pack, cancel: AtomicBoolean, onProgress: (Long, Long) -> Unit) {
        val file = partFile(ctx, p)
        var attempt = 0
        while (true) {
            try {
                fetch(p.url, file, cancel, onProgress)
                break
            } catch (e: IOException) {
                if (cancel.get() || ++attempt >= 5) throw e
                Thread.sleep(1500L * attempt)
            }
        }
        if (cancel.get()) throw IOException("đã huỷ")
        onProgress(-2L, -2L)
        val out = dir(ctx, p)
        out.deleteRecursively()
        try {
            if (p.tarBz2) untarBz2(file, root(ctx), p, cancel) else unzip(file, root(ctx), cancel)
            if (!p.required.all { File(out, it).exists() }) throw IOException("file mô hình không đúng cấu trúc")
        } catch (e: Exception) {
            // file tải về hỏng (vd. trang chặn mạng thay cho file thật): xoá để lần sau tải lại từ đầu
            if (!cancel.get()) file.delete()
            out.deleteRecursively()
            throw e
        }
        File(out, ".ok").writeText("ok")
        file.delete()
    }

    private fun fetch(url: String, out: File, cancel: AtomicBoolean, onProgress: (Long, Long) -> Unit) {
        val have = if (out.exists()) out.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 40000
        c.instanceFollowRedirects = true
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code == 416) { c.disconnect(); return }          // đã tải đủ từ lần trước
        if (code != 200 && code != 206) throw IOException("máy chủ trả mã $code")
        val append = code == 206
        val len = c.contentLengthLong
        val total = if (len > 0) (if (append) have + len else len) else -1L
        var done = if (append) have else 0L
        c.inputStream.use { inp ->
            FileOutputStream(out, append).use { o ->
                val buf = ByteArray(64 * 1024)
                var last = 0L
                while (true) {
                    if (cancel.get()) throw IOException("đã huỷ")
                    val n = inp.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    val now = SystemClock.uptimeMillis()
                    if (now - last > 250) { last = now; onProgress(done, total) }
                }
            }
        }
        onProgress(done, total)
        if (total > 0 && done < total) throw IOException("mất kết nối giữa chừng")
    }

    private fun safeFile(dest: File, name: String): File {
        val f = File(dest, name)
        if (!f.canonicalPath.startsWith(dest.canonicalPath + File.separator)) throw IOException("file nén lạ")
        return f
    }

    private fun unzip(zip: File, dest: File, cancel: AtomicBoolean) {
        dest.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { z ->
            while (true) {
                if (cancel.get()) throw IOException("đã huỷ")
                val e = z.nextEntry ?: break
                val f = safeFile(dest, e.name)
                if (e.isDirectory) f.mkdirs()
                else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { z.copyTo(it) }
                }
            }
        }
    }

    /** Chỉ lấy các file cần dùng (bỏ file âm thanh mẫu, mã nguồn…) cho đỡ tốn bộ nhớ. */
    private fun untarBz2(file: File, dest: File, p: Pack, cancel: AtomicBoolean) {
        dest.mkdirs()
        val keep = p.required.toSet() + setOf("LICENSE", "README.md")
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16), true)).use { t ->
            while (true) {
                if (cancel.get()) throw IOException("đã huỷ")
                val e = t.nextEntry ?: break
                if (e.isDirectory) continue
                val base = e.name.substringAfterLast('/')
                if (base !in keep) continue
                val f = safeFile(dest, p.dirName + "/" + base)
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { t.copyTo(it) }
            }
        }
    }
}

/**
 * Quản lý các lượt tải đang chạy, để màn hình chính và bong bóng cùng thấy tiến độ.
 */
object Downloads {
    class State(val done: Long, val total: Long, val unpacking: Boolean, val error: String?, val finished: Boolean)

    private val states = ConcurrentHashMap<String, State>()
    private val cancels = ConcurrentHashMap<String, AtomicBoolean>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun state(p: Pack): State? = states[p.id]

    fun running(p: Pack): Boolean = cancels.containsKey(p.id)

    fun addListener(l: () -> Unit) { listeners.add(l) }

    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun changed() {
        main.post { for (l in listeners) l() }
    }

    fun start(ctx: Context, p: Pack) {
        if (running(p) || Packs.ready(ctx, p)) return
        val cancel = AtomicBoolean(false)
        cancels[p.id] = cancel
        states[p.id] = State(0, -1, false, null, false)
        changed()
        val app = ctx.applicationContext
        Thread({
            var err: String? = null
            try {
                Packs.download(app, p, cancel) { done, total ->
                    states[p.id] = if (total == -2L) State(0, -2, true, null, false) else State(done, total, false, null, false)
                    changed()
                }
            } catch (e: Exception) {
                err = if (cancel.get()) "đã huỷ" else (e.message ?: e.toString())
            }
            cancels.remove(p.id)
            states[p.id] = State(0, 0, false, err, true)
            changed()
        }, "subzoro-tai-${p.id}").start()
    }

    fun cancel(p: Pack) {
        cancels[p.id]?.set(true)
    }

    /** Dòng mô tả tiến độ, null nếu không có gì đang tải. */
    fun describe(p: Pack): String? {
        val s = states[p.id] ?: return null
        if (!running(p)) return s.error?.let { if (it == "đã huỷ") "Đã huỷ tải." else "Tải không được: $it" }
        return when {
            s.unpacking -> "Đang giải nén ${p.name}… (có thể mất một lúc)"
            s.total > 0 -> String.format("Đang tải %s: %.1f / %.1f MB", p.name, s.done / 1e6, s.total / 1e6)
            else -> String.format("Đang tải %s: %.1f MB", p.name, s.done / 1e6)
        }
    }
}
