package io.github.quanbughunter.subzoro

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ghi lại lỗi làm app văng (nếu có) vào file, để lần mở sau màn hình chính hiện ra cho người dùng chụp gửi.
 */
class App : Application() {
    companion object {
        const val CRASH_FILE = "last-crash.txt"
    }

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (x: Exception) { "?" }
                val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date())
                File(filesDir, CRASH_FILE).writeText(
                    "subZoro $ver · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "$time · luồng ${t.name}\n\n$sw"
                )
            } catch (x: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
