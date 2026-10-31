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
    /** câu đang phát: nền câu, nền từ đang nói, chữ/bính âm chưa tới lượt */
    val HL_BG = 0xFFE2F1DD.toInt()
    val HL_WORD = 0xFFB6DCAD.toInt()
    val UPCOMING = 0xFF8E9E91.toInt()
    val PINYIN_UP = 0xFF93B39C.toInt()
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

    private val KEYWORDS = listOf(
        "zh" to listOf("tiếng trung", "tiếng hoa", "hán ngữ", "chinese", "mandarin", "hsk", "中文", "汉语", "普通话"),
        "ru" to listOf("tiếng nga", "russian", "русский", "по-русски"),
        "en" to listOf("tiếng anh", "english", "ielts", "toeic", "toefl")
    )

    /** Chữ chỉ tiếng Việt mới có (ă đ ơ ư và các nguyên âm mang dấu hỏi/ngã/nặng…). */
    private const val VN_ONLY = "ăđơưảãạằắẳẵặầấẩẫậẻẽẹềếểễệỉĩịỏọồốổỗộờớởỡợủũụừứửữựỳỷỹỵ"
    private const val VN_ACCENT = "àáâãèéêìíòóôõùúý"
    /** từ tiếng Việt hay gặp trong tên video, kể cả viết không dấu */
    private val VN_WORDS = setOf(
        "và", "là", "của", "những", "người", "không", "khong", "nhạc", "nhac", "bài", "bai", "hát", "tình", "yêu", "yeu",
        "em", "anh", "chị", "mình", "minh", "tôi", "toi", "với", "voi", "được", "duoc", "ngày", "nhất", "nhat", "việt", "viet",
        "cực", "cuc", "hay", "vui", "buồn", "buon", "phim", "tập", "trực", "tiếp"
    )

    /**
     * Đoán ngôn ngữ nói theo tên video: có chữ Hán → Trung, chữ Kirin → Nga; tên có ghi rõ "tiếng Trung/Nga/Anh"
     * (hoặc Chinese, HSK, IELTS…) thì theo đó; tên tiếng Việt không ghi rõ thì không đoán; còn lại chữ Latinh → Anh.
     */
    fun guess(title: String?): String? {
        if (title.isNullOrBlank()) return null
        var han = 0
        var cyr = 0
        var lat = 0
        var vn = 0
        val low = title.lowercase()
        for (ch in low) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.CYRILLIC -> cyr++
                Character.UnicodeScript.LATIN -> { lat++; if (ch in VN_ONLY) vn++ }
                else -> {}
            }
        }
        // tên tiếng Nhật (có chữ kana): không đoán (chưa hỗ trợ tiếng Nhật)
        if (low.any { val s = Character.UnicodeScript.of(it.code); s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA }) return null
        if (han >= 2) return "zh"
        if (cyr >= 3) return "ru"
        for ((code, words) in KEYWORDS) if (words.any { low.contains(it) }) return code
        // tên tiếng Việt (có chữ chỉ tiếng Việt mới có, hoặc vài chữ có dấu, hoặc từ tiếng Việt hay gặp): không đoán
        val accents = low.count { it in VN_ACCENT }
        val tokens = low.split(Regex("[^\\p{L}]+"))
        if (vn > 0 || accents >= 2 || tokens.any { it in VN_WORDS }) return null
        return if (lat >= 3) "en" else null
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

    /** Chiều cao tối đa của danh sách câu trên bong bóng, tính theo phần chiều cao màn hình. */
    var listFrac: Float
        get() = sp.getFloat("listFrac", 0.30f)
        set(v) = sp.edit().putFloat("listFrac", v).apply()

    /** Lần thử gần nhất bắt tiếng YouTube: "ok", "silent" hoặc "" (chưa thử). */
    var appCapture: String
        get() = sp.getString("appCapture", "") ?: ""
        set(v) = sp.edit().putString("appCapture", v).apply()
}

/** Một đoạn của câu (một từ, hoặc một chữ Hán, kèm dấu câu theo sau) và mốc trong video lúc nó bắt đầu được nói. */
class Seg(val from: Int, val to: Int, val ms: Long)

/** Chữ của câu và mốc từng từ (để tô chữ theo lời nói). segs có thể rỗng nếu không biết mốc. */
class Body(val text: String, val segs: List<Seg>)

/** Bính âm của câu tiếng Trung src; chữ thứ i của src ứng với đoạn [start[i], end[i]) trong text. */
class PinyinText(val src: String, val text: String, val start: IntArray, val end: IntArray)

/**
 * Một câu đã nghe được. startMs/endMs là thời điểm trong video (-1 nếu không biết).
 * body có thể được thay bằng bản nghe chính xác hơn (refined); pinyin chỉ có với tiếng Trung.
 */
class Line(body: Body, val startMs: Long, val endMs: Long, val lang: String) {
    @Volatile var body: Body = body
    val text: String get() = body.text
    @Volatile var pinyin: PinyinText? = null
    @Volatile var vi: String? = null
    @Volatile var refined = false
}

fun fmtTime(ms: Long): String {
    if (ms < 0) return "--:--"
    val s = ms / 1000
    return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format("%02d:%02d", s / 60, s % 60)
}
