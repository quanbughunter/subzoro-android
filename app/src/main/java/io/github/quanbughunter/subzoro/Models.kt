package io.github.quanbughunter.subzoro

import android.content.Context
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Mô hình Vosk: tải một lần (có tải tiếp khi đứt mạng), giải nén vào bộ nhớ riêng của app. */
object Models {
    private fun root(ctx: Context) = File(ctx.filesDir, "models")

    fun dir(ctx: Context, l: Lang) = File(root(ctx), l.modelId)

    fun ready(ctx: Context, l: Lang) = File(dir(ctx, l), ".ok").exists()

    fun delete(ctx: Context, l: Lang) {
        dir(ctx, l).deleteRecursively()
        File(ctx.cacheDir, l.modelId + ".zip").delete()
    }

    fun url(l: Lang) = "https://alphacephei.com/vosk/models/${l.modelId}.zip"

    /** Chạy trên luồng nền. onProgress(đã tải, tổng) tính theo byte; tổng = -1 nếu máy chủ không báo. */
    fun download(ctx: Context, l: Lang, cancel: AtomicBoolean, onProgress: (Long, Long) -> Unit) {
        val zip = File(ctx.cacheDir, l.modelId + ".zip")
        var attempt = 0
        while (true) {
            try {
                fetch(url(l), zip, cancel, onProgress)
                break
            } catch (e: IOException) {
                if (cancel.get() || ++attempt >= 5) throw e
                Thread.sleep(1500L * attempt)
            }
        }
        if (cancel.get()) throw IOException("đã huỷ")
        val out = dir(ctx, l)
        out.deleteRecursively()
        unzip(zip, root(ctx), cancel)
        if (!File(out, "conf").isDirectory && !File(out, "am").isDirectory) throw IOException("file mô hình không đúng cấu trúc")
        File(out, ".ok").writeText("ok")
        zip.delete()
    }

    private fun fetch(url: String, out: File, cancel: AtomicBoolean, onProgress: (Long, Long) -> Unit) {
        val have = if (out.exists()) out.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
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
                    if (now - last > 200) { last = now; onProgress(done, total) }
                }
            }
        }
        onProgress(done, total)
        if (total > 0 && done < total) throw IOException("mất kết nối giữa chừng")
    }

    private fun unzip(zip: File, dest: File, cancel: AtomicBoolean) {
        dest.mkdirs()
        val base = dest.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { z ->
            while (true) {
                if (cancel.get()) throw IOException("đã huỷ")
                val e = z.nextEntry ?: break
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(base)) throw IOException("file nén lạ")
                if (e.isDirectory) f.mkdirs()
                else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { z.copyTo(it) }
                }
            }
        }
    }
}
