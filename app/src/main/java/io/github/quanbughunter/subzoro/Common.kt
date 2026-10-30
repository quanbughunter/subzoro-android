package io.github.quanbughunter.subzoro

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/** Màu subZoro: xanh lá pastel (màu tóc Zoro), giống extension. */
object Pal {
    val ACCENT = 0xFF3F7D4E.toInt()
    val ACCENT_SOFT = 0xFFD9EED5.toInt()
    val BG = 0xFFEEF4EC.toInt()
    val SURFACE = 0xFFFBFDFA.toInt()
    val PANEL = 0xF2FBFDFA.toInt()      // nền bong bóng, hơi trong
    val TEXT = 0xFF1D2B20.toInt()
    val MUTED = 0xFF5F7363.toInt()
    val PINYIN = 0xFF2F6B44.toInt()
    val LINE = 0xFFD6E3D2.toInt()
    val DANGER = 0xFFB42318.toInt()
    val WARN_BG = 0xFFFFF4E5.toInt()
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, stroke: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/** Ngôn ngữ nói trong video. short = nhãn trên bong bóng; gtx = mã Google Dịch. */
data class Lang(val code: String, val name: String, val short: String, val gtx: String)

object Langs {
    val ALL = listOf(
        Lang("en", "Tiếng Anh", "EN", "en"),
        Lang("ru", "Tiếng Nga", "RU", "ru"),
        Lang("zh", "Tiếng Trung", "中文", "zh-CN")
    )

    fun of(code: String?): Lang = ALL.firstOrNull { it.code == code } ?: ALL[0]

    /** Đoán ngôn ngữ nói theo tên video: có chữ Hán → Trung, chữ Kirin → Nga, còn lại chữ Latinh → Anh. */
    fun guess(title: String?): String? {
        if (title.isNullOrBlank()) return null
        var han = 0
        var cyr = 0
        var lat = 0
        for (ch in title) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.CYRILLIC -> cyr++
                Character.UnicodeScript.LATIN -> lat++
                else -> {}
            }
        }
        return when {
            han >= 2 -> "zh"
            cyr >= 3 -> "ru"
            lat >= 3 -> "en"
            else -> null
        }
    }
}

/** Cài đặt lưu trên máy. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("subzoro", Context.MODE_PRIVATE)

    /** Ngôn ngữ chọn tay (hoặc dùng khi không đoán được). */
    var lang: String
        get() = sp.getString("lang", "en") ?: "en"
        set(v) = sp.edit().putString("lang", v).apply()

    /** Tự đổi ngôn ngữ theo tên video đang phát. */
    var autoLang: Boolean
        get() = sp.getBoolean("autoLang", true)
        set(v) = sp.edit().putBoolean("autoLang", v).apply()

    /** "app" = tiếng phát ra từ YouTube, "mic" = nghe qua micro */
    var source: String
        get() = sp.getString("source", "app") ?: "app"
        set(v) = sp.edit().putString("source", v).apply()

    var translate: Boolean
        get() = sp.getBoolean("translate", true)
        set(v) = sp.edit().putBoolean("translate", v).apply()

    /** Dùng mô hình nghe chính xác (nếu đã tải) để sửa lại câu. */
    var accurate: Boolean
        get() = sp.getBoolean("accurate", true)
        set(v) = sp.edit().putBoolean("accurate", v).apply()

    var textSize: Int
        get() = sp.getInt("textSize", 17)
        set(v) = sp.edit().putInt("textSize", v).apply()

    var panelY: Int
        get() = sp.getInt("panelY", -1)
        set(v) = sp.edit().putInt("panelY", v).apply()

    /** Lần thử gần nhất bắt tiếng YouTube: "ok", "silent" hoặc "" (chưa thử). */
    var appCapture: String
        get() = sp.getString("appCapture", "") ?: ""
        set(v) = sp.edit().putString("appCapture", v).apply()
}

/**
 * Một câu đã nghe được. startMs/endMs là thời điểm trong video (-1 nếu không biết).
 * text có thể được thay bằng bản nghe chính xác hơn (refined); pinyin chỉ có với tiếng Trung.
 */
class Line(@Volatile var text: String, val startMs: Long, val endMs: Long, val lang: String) {
    @Volatile var pinyin: String? = null
    @Volatile var vi: String? = null
    @Volatile var refined = false
}

fun fmtTime(ms: Long): String {
    if (ms < 0) return "--:--"
    val s = ms / 1000
    return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format("%02d:%02d", s / 60, s % 60)
}
