package io.github.quanbughunter.subzoro

import android.content.Context
import java.util.zip.GZIPInputStream

/**
 * Bính âm cho câu tiếng Trung, mỗi âm tiết cách nhau một dấu cách (như phụ đề học tiếng Trung).
 * Dữ liệu assets/zh_pinyin.txt.gz: âm mặc định của từng chữ + các từ 2–4 chữ đọc khác âm mặc định
 * (đa âm: 银行 yín háng, 了解 liǎo jiě, 还是 hái shi…). Nguồn jieba + pypinyin (MIT).
 */
object Pinyin {
    @Volatile private var map: HashMap<String, String>? = null

    private fun load(ctx: Context): HashMap<String, String> {
        map?.let { return it }
        synchronized(this) {
            map?.let { return it }
            val m = HashMap<String, String>(200_000)
            GZIPInputStream(ctx.assets.open("zh_pinyin.txt.gz")).bufferedReader().useLines { seq ->
                for (line in seq) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val tab = line.indexOf('\t')
                    if (tab > 0) m[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
            map = m
            return m
        }
    }

    private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    private val PUNCT = mapOf('，' to ",", '。' to ".", '？' to "?", '！' to "!", '、' to ",", '：' to ":", '；' to ";", '“' to "\"", '”' to "\"", '（' to "(", '）' to ")")

    /** "今天想和大家来聊。" -> "jīn tiān xiǎng hé dà jiā lái liáo." */
    fun of(ctx: Context, text: String): String = mapped(ctx, text).text

    /** Như of(), kèm vị trí bính âm của từng chữ (để tô bính âm cùng lúc với chữ Hán). */
    fun mapped(ctx: Context, text: String): PinyinText = mapped(load(ctx), text)

    fun mapped(m: Map<String, String>, text: String): PinyinText {
        val out = StringBuilder()
        val st = IntArray(text.length)
        val en = IntArray(text.length)
        fun add(s: String): Int {
            if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            val a = out.length
            out.append(s)
            return a
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isHan(c)) {
                var hit = false
                // từ dài nhất (4 → 2 chữ) có cách đọc riêng
                for (len in 4 downTo 2) {
                    if (i + len > text.length) continue
                    val w = text.substring(i, i + len)
                    if (!w.all { isHan(it) }) continue
                    val py = m[w] ?: continue
                    val a = add(py)
                    val syl = py.split(' ')
                    if (syl.size == len) {
                        var p = a
                        for (k in 0 until len) { st[i + k] = p; en[i + k] = p + syl[k].length; p += syl[k].length + 1 }
                    } else {
                        for (k in 0 until len) { st[i + k] = a; en[i + k] = out.length }
                    }
                    i += len
                    hit = true
                    break
                }
                if (!hit) {
                    val a = add(m[c.toString()] ?: c.toString())
                    st[i] = a
                    en[i] = out.length
                    i++
                }
            } else {
                val p = PUNCT[c]
                when {
                    p != null -> { st[i] = out.length; out.append(p); en[i] = out.length }
                    c.isWhitespace() -> {
                        if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                        st[i] = out.length
                        en[i] = out.length
                    }
                    else -> {
                        // chữ số, chữ Latinh: giữ nguyên cả cụm
                        var j = i
                        while (j < text.length && !isHan(text[j]) && PUNCT[text[j]] == null && !text[j].isWhitespace()) j++
                        val a = add(text.substring(i, j))
                        for (k in i until j) { st[k] = a + (k - i); en[k] = a + (k - i) + 1 }
                        i = j
                        continue
                    }
                }
                i++
            }
        }
        val s = out.toString().trimEnd()
        for (k in st.indices) { st[k] = minOf(st[k], s.length); en[k] = minOf(en[k], s.length) }
        return PinyinText(text, s, st, en)
    }
}
