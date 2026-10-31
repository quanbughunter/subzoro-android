package io.github.quanbughunter.subzoro

/** Một từ Vosk: chữ, mốc trong video (ms, -1 nếu không biết) và vị trí trong luồng tiếng (số mẫu 16 kHz). */
class Word(val text: String, val startMs: Long, val endMs: Long, val s0: Long, val s1: Long, val conf: Double)

/** Bảng tra: mẫu tiếng thứ `sample` ứng với ms nào của video (mỗi `step` mẫu một mốc, -1 = không biết). */
class TimeMap(private val from: Long, private val step: Int, private val ms: LongArray) {
    fun at(sample: Long): Long {
        if (ms.isEmpty()) return -1
        val f = (sample - from).toDouble() / step
        val i = f.toInt().coerceIn(0, ms.size - 1)
        val a = ms[i]
        if (a < 0) return -1
        val j = minOf(i + 1, ms.size - 1)
        val b = ms[j]
        if (j == i || b < 0 || b < a) return a
        val frac = (f - i).coerceIn(0.0, 1.0)
        return a + ((b - a) * frac).toLong()
    }
}

/**
 * Những đoạn video đã có câu (của một ngôn ngữ), để không nghe lại thành câu lặp khi tua về.
 * Bất biến: dựng lại mỗi khi danh sách câu đổi, luồng nghe đọc mà không cần khoá.
 */
class Cover(val lang: String, private val starts: LongArray, private val ends: LongArray) {
    companion object {
        val EMPTY = Cover("", LongArray(0), LongArray(0))
    }

    /** ms có nằm trong (hoặc sát, cách không quá pad) một câu đã có không. starts phải tăng dần. */
    fun has(ms: Long, pad: Long): Boolean {
        if (ms < 0 || starts.isEmpty()) return false
        var lo = 0
        var hi = starts.size - 1
        var k = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= ms + pad) { k = mid; lo = mid + 1 } else hi = mid - 1
        }
        var i = k
        var n = 0
        while (i >= 0 && n < 12) {
            if (ends[i] + pad >= ms) return true
            i--
            n++
        }
        return false
    }
}

/** Dựng chữ của câu kèm mốc từng từ, từ kết quả Vosk hoặc mô hình nghe chính xác. */
object Timing {
    fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    private const val ZH_PUNCT = "，。？！、：；“”‘’（）《》…—"

    private fun isCjkOrPunct(c: Char) = isHan(c) || c in ZH_PUNCT

    private fun isApos(c: Char) = c == '\'' || c == '’'

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || isApos(c)

    /** Chữ c có mở đầu một đoạn mới không (mỗi chữ Hán một đoạn, chữ Latinh/Kirin theo từng từ). */
    private fun startsSeg(text: CharSequence, k: Int): Boolean {
        val c = text[k]
        if (!c.isLetterOrDigit()) return false
        if (k == 0 || isHan(c)) return true
        val p = text[k - 1]
        return !isWordChar(p) || isHan(p)
    }

    /** Viết hoa/thường mà không đổi độ dài chuỗi (để vị trí từng đoạn vẫn đúng). */
    fun fixCase(t: String, lang: String): String {
        if (lang == "zh" || t.isEmpty()) return t
        val a = t.toCharArray()
        var hasLetter = false
        var allUpper = true
        for (c in a) if (c.isLetter()) { hasLetter = true; if (c.isLowerCase()) allUpper = false }
        if (hasLetter && allUpper && a.size > 3) for (i in a.indices) a[i] = Character.toLowerCase(a[i])
        for (i in a.indices) if (a[i].isLetter()) { a[i] = Character.toUpperCase(a[i]); break }
        if (lang == "en") {
            for (i in a.indices) {
                if (a[i] == 'i' && (i == 0 || !isWordChar(a[i - 1])) && (i == a.size - 1 || !a[i + 1].isLetterOrDigit())) a[i] = 'I'
            }
        }
        return String(a)
    }

    /** Câu từ các từ Vosk (mốc từng từ có sẵn). */
    fun fromWords(words: List<Word>, lang: String): Body {
        val sb = StringBuilder()
        val segs = ArrayList<Seg>()
        for (w in words) {
            val t = w.text.trim()
            if (t.isEmpty()) continue
            if (lang != "zh" && sb.isNotEmpty()) sb.append(' ')
            val a = sb.length
            sb.append(t)
            if (w.startMs >= 0) segs.add(Seg(a, sb.length, w.startMs))
        }
        return Body(fixCase(sb.toString(), lang), monotonic(segs))
    }

    /**
     * Câu từ token + mốc giờ của mô hình chính xác (SenseVoice: mỗi chữ Hán / mảnh từ một token; GigaAM: mỗi chữ cái một token).
     * msAt(giây tính từ đầu đoạn tiếng) -> ms trong video. null nếu không dùng được mốc.
     */
    fun fromTokens(tokens: Array<String>, times: FloatArray, lang: String, msAt: (Float) -> Long): Body? {
        if (tokens.isEmpty() || tokens.size != times.size) return null
        val raw = StringBuilder()
        val own = ArrayList<Int>()
        for (i in tokens.indices) for (c in tokens[i]) { raw.append(if (c == '▁') ' ' else c); own.add(i) }
        val (text0, map) = squeeze(raw, lang)
        if (text0.isEmpty()) return null
        val text = fixCase(text0, lang)
        val segs = ArrayList<Seg>()
        for (k in text.indices) {
            val c = text[k]
            if (startsSeg(text, k)) {
                val ms = msAt(times[own[map[k]]])
                if (ms < 0) return null
                segs.add(Seg(k, k + 1, ms))
            } else if (c != ' ' && segs.isNotEmpty()) {
                val s = segs[segs.size - 1]
                if (s.to == k) segs[segs.size - 1] = Seg(s.from, k + 1, s.ms)
            }
        }
        return Body(text, monotonic(segs))
    }

    /**
     * Chia những từ chưa có (covered[i] = false) thành từng nhóm liền nhau:
     * ngắt ở chỗ từ đã có, hoặc chỗ thời gian trong video nhảy (lùi lại, hoặc cách nhau quá 2,5 giây).
     */
    fun groups(words: List<Word>, covered: BooleanArray): List<IntRange> {
        val out = ArrayList<IntRange>()
        var gs = -1
        for (i in words.indices) {
            if (covered[i]) {
                if (gs >= 0) { out += gs until i; gs = -1 }
                continue
            }
            if (gs >= 0) {
                val prev = words[i - 1]
                val w = words[i]
                if (w.startMs < prev.endMs - 300 || w.startMs > prev.endMs + 2500) { out += gs until i; gs = i }
            } else gs = i
        }
        if (gs >= 0) out += gs until words.size
        return out
    }

    /** Chuẩn hoá chữ (tiếng Trung bỏ dấu cách cạnh chữ Hán; bỏ dấu cách thừa; viết hoa đầu câu). */
    fun clean(raw: String, lang: String): String = fixCase(squeeze(raw, lang).first, lang)

    /** Không có mốc từng từ: chia thời gian câu cho từng đoạn theo độ dài. */
    fun proportional(text: String, lang: String, startMs: Long, endMs: Long): Body {
        val segs = ArrayList<Seg>()
        for (k in text.indices) {
            if (startsSeg(text, k)) segs.add(Seg(k, k + 1, 0))
            else if (text[k] != ' ' && segs.isNotEmpty() && segs.last().to == k) {
                val s = segs.last()
                segs[segs.size - 1] = Seg(s.from, k + 1, 0)
            }
        }
        if (segs.isEmpty() || startMs < 0 || endMs <= startMs) return Body(text, emptyList())
        val weight = DoubleArray(segs.size) { i ->
            val s = segs[i]
            if (isHan(text[s.from])) 1.0 else maxOf(1.0, (s.from until s.to).count { text[it].isLetterOrDigit() } / 3.0)
        }
        val total = weight.sum()
        var acc = 0.0
        val out = ArrayList<Seg>(segs.size)
        for (i in segs.indices) {
            out.add(Seg(segs[i].from, segs[i].to, startMs + ((endMs - startMs) * acc / total).toLong()))
            acc += weight[i]
        }
        return Body(text, out)
    }

    /** Bỏ dấu cách thừa; trả về chuỗi mới và vị trí gốc của từng chữ. */
    private fun squeeze(raw: CharSequence, lang: String): Pair<String, IntArray> {
        val sb = StringBuilder()
        val map = ArrayList<Int>()
        for (k in 0 until raw.length) {
            val c = raw[k]
            if (c.isWhitespace()) {
                if (sb.isEmpty() || sb[sb.length - 1] == ' ') continue
                if (lang == "zh") {
                    var j = k + 1
                    while (j < raw.length && raw[j].isWhitespace()) j++
                    val next = if (j < raw.length) raw[j] else ' '
                    if (isCjkOrPunct(sb[sb.length - 1]) || isCjkOrPunct(next)) continue
                }
                sb.append(' ')
                map.add(k)
            } else {
                sb.append(c)
                map.add(k)
            }
        }
        while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') { sb.setLength(sb.length - 1); map.removeAt(map.size - 1) }
        return sb.toString() to map.toIntArray()
    }

    /** Mốc phải tăng dần (mô hình đôi khi trả mốc lệch). */
    private fun monotonic(segs: ArrayList<Seg>): List<Seg> {
        for (i in 1 until segs.size) if (segs[i].ms < segs[i - 1].ms) segs[i] = Seg(segs[i].from, segs[i].to, segs[i - 1].ms)
        return segs
    }
}
