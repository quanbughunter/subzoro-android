package io.github.quanbughunter.subzoro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/** Những việc bong bóng nhờ dịch vụ làm khi người dùng bấm. */
interface OverlayHost {
    fun onReplay()
    fun onTogglePlay()
    fun onSeekLine(line: Line)
    fun onClose()
    /** code = "auto" hoặc mã ngôn ngữ (en/ru/zh) */
    fun onPickLang(code: String)
}

/**
 * Bong bóng phụ đề nổi trên YouTube (quyền "Hiển thị trên ứng dụng khác").
 *   - Mở: nút ngôn ngữ + trạng thái + nút điều khiển; danh sách mọi câu đã nghe của video (tiếng Trung có bính âm
 *     phía trên), nghĩa tiếng Việt nhỏ ngay bên dưới; câu đang nghe dở in nghiêng.
 *   - Câu đang phát được tô nền, từng từ sáng dần theo lời nói (bính âm sáng theo), nghĩa tiếng Việt đậm lên.
 *   - Danh sách cao tối đa một khoảng (kéo vạch dưới đáy để đổi), vuốt hoặc kéo thanh trượt bên phải để xem câu cũ;
 *     đang xem câu cũ thì thôi tự cuộn, bấm "Theo video" (hoặc để yên 15 giây) để cuộn theo lại.
 *   - Thu nhỏ: nút tròn ba nhát chém, kéo đi đâu cũng được, chạm để mở lại.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n", "ViewConstructor")
class Overlay(private val ctx: Context, private val host: OverlayHost) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(ctx)

    private val panel = LinearLayout(ctx)
    private val header = LinearLayout(ctx)
    private val dot = View(ctx)
    private val langBtn = TextView(ctx)
    private val status = TextView(ctx)
    private val playBtn = TextView(ctx)
    private val langRow = LinearLayout(ctx)
    private val langScroll = HorizontalScrollView(ctx)
    private val infoView = TextView(ctx)
    private val warnBox = LinearLayout(ctx)
    private val warnText = TextView(ctx)
    private val warnBtn = TextView(ctx)
    private val listFrame = FrameLayout(ctx)
    private val listRow = LinearLayout(ctx)
    private val scroll = ListScroll(ctx)
    private val linesBox = LinearLayout(ctx)
    private val partialView = TextView(ctx)
    private val rail = Rail(ctx)
    private val followBtn = TextView(ctx)
    private val grip = FrameLayout(ctx)
    private val bubble = BubbleView(ctx)

    private val panelLp = baseParams()
    private val bubbleLp = baseParams()
    private var panelShown = false
    private var bubbleShown = false
    private var dotState = -1
    private var warnAction: (() -> Unit)? = null

    private var lines: List<Line> = emptyList()
    private val views = HashMap<Line, LineView>()
    private var partialText = ""
    private var curLang = "en"
    private var autoLang = true

    /** vị trí video gần nhất (ms, -1 = không biết) */
    private var posMs = -1L
    private var activeView: LineView? = null
    private var following = true
    private var lastUserScroll = 0L
    /** ngón tay đang chạm danh sách: không tự cuộn */
    private var touching = false
    /** đang chạy hiệu ứng cuộn tới lúc này: không giục cuộn tiếp (cuộn dồn sẽ thành giật) */
    private var scrollAnimUntil = 0L
    /** vừa chạm câu để tua: YouTube chưa kịp báo vị trí mới thì tạm coi vị trí là đầu câu đó */
    private var seekTarget = -1L
    private var seekUntil = 0L
    private val medium: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)

    val expanded: Boolean get() = panelShown

    private companion object {
        /** tô sớm hơn một chút cho kịp mắt đọc (tiếng bắt được đến muộn vài chục ms so với vị trí video) */
        const val LEAD = 100L
        /** câu vẫn được tô thêm chừng này sau khi nói xong (trừ khi câu sau đã bắt đầu) */
        const val HOLD = 2000L
    }

    init {
        buildPanel()
        bubble.setOnTouchListener(DragTouch(bubble, bubbleLp, horizontal = true) { expand() })
    }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /* ---------------------------------------------------------------- dựng giao diện */

    private fun iconButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 18f
        setTextColor(Pal.ACCENT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = ctx.dp(34)
        setPadding(ctx.dp(5), ctx.dp(2), ctx.dp(5), ctx.dp(2))
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13.5f
        typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (on) Pal.ACCENT else Pal.TEXT)
        background = rounded(if (on) Pal.ACCENT_SOFT else Pal.SURFACE, ctx.dp(99).toFloat(), if (on) Pal.ACCENT else Pal.LINE, ctx.dp(1))
        setPadding(ctx.dp(11), ctx.dp(4), ctx.dp(11), ctx.dp(4))
        setOnClickListener { onClick() }
    }

    private fun buildPanel() {
        panel.orientation = LinearLayout.VERTICAL
        panel.background = rounded(Pal.PANEL, ctx.dp(16).toFloat(), Pal.LINE, ctx.dp(1))
        panel.setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(4), ctx.dp(0))

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        dot.background = oval(Pal.LINE)
        header.addView(dot, LinearLayout.LayoutParams(ctx.dp(9), ctx.dp(9)).apply { rightMargin = ctx.dp(7) })
        langBtn.apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Pal.ACCENT)
            background = rounded(Pal.ACCENT_SOFT, ctx.dp(99).toFloat(), Pal.ACCENT, ctx.dp(1))
            setPadding(ctx.dp(9), ctx.dp(2), ctx.dp(7), ctx.dp(2))
            setOnClickListener { toggleLangRow() }
        }
        header.addView(langBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = ctx.dp(7) })
        status.textSize = 12f
        status.setTextColor(Pal.MUTED)
        status.isSingleLine = true
        status.ellipsize = TextUtils.TruncateAt.END
        header.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("↺") { host.onReplay() })
        playBtn.apply {
            text = "❚❚"
            textSize = 14f
            setTextColor(Pal.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = ctx.dp(34)
            setPadding(ctx.dp(5), ctx.dp(2), ctx.dp(5), ctx.dp(2))
            setOnClickListener { host.onTogglePlay() }
        }
        header.addView(playBtn)
        header.addView(iconButton("Aa") { cycleTextSize() })
        header.addView(iconButton("—") { collapse() })
        header.addView(iconButton("✕") { host.onClose() })
        header.setPadding(ctx.dp(2), 0, ctx.dp(4), 0)
        header.setOnTouchListener(DragTouch(panel, panelLp, horizontal = false, onTap = null))
        status.setOnTouchListener(DragTouch(panel, panelLp, horizontal = false, onTap = null))
        panel.addView(header)

        // hàng chọn ngôn ngữ (ẩn, bấm nút ngôn ngữ để mở)
        langRow.orientation = LinearLayout.HORIZONTAL
        langRow.setPadding(0, ctx.dp(6), 0, ctx.dp(2))
        langScroll.isHorizontalScrollBarEnabled = false
        langScroll.addView(langRow)
        langScroll.visibility = View.GONE
        panel.addView(langScroll)

        infoView.textSize = 12f
        infoView.setTextColor(Pal.MUTED)
        infoView.visibility = View.GONE
        panel.addView(infoView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(3) })

        warnBox.orientation = LinearLayout.HORIZONTAL
        warnBox.gravity = Gravity.CENTER_VERTICAL
        warnBox.background = rounded(Pal.WARN_BG, ctx.dp(10).toFloat())
        warnBox.setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(8), ctx.dp(6))
        warnText.textSize = 12.5f
        warnText.setTextColor(Pal.TEXT)
        warnBox.addView(warnText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        warnBtn.textSize = 13f
        warnBtn.setTextColor(Pal.ACCENT)
        warnBtn.typeface = Typeface.DEFAULT_BOLD
        warnBtn.setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        warnBtn.setOnClickListener { warnAction?.invoke() }
        warnBox.addView(warnBtn)
        warnBox.visibility = View.GONE
        panel.addView(warnBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })

        // danh sách câu: cuộn được, thanh trượt bên phải, nút "Theo video" khi đang xem câu cũ
        linesBox.orientation = LinearLayout.VERTICAL
        scroll.addView(linesBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> rail.invalidate() }
        linesBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rail.invalidate() }
        listRow.orientation = LinearLayout.HORIZONTAL
        listRow.addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        listRow.addView(rail, LinearLayout.LayoutParams(ctx.dp(20), LinearLayout.LayoutParams.MATCH_PARENT))
        listFrame.addView(listRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        followBtn.apply {
            text = "↓ Theo video"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(Pal.ACCENT, ctx.dp(99).toFloat())
            setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
            visibility = View.GONE
            setOnClickListener { follow() }
        }
        listFrame.addView(followBtn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = ctx.dp(26)
            bottomMargin = ctx.dp(6)
        })
        panel.addView(listFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(2) })

        partialView.setTextColor(Pal.MUTED)
        partialView.setTypeface(null, Typeface.ITALIC)
        partialView.setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(4))

        // vạch dưới đáy: kéo lên/xuống để đổi chiều cao danh sách
        grip.addView(View(ctx).apply { background = rounded(Pal.LINE, ctx.dp(99).toFloat()) },
            FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(4), Gravity.CENTER))
        grip.setOnTouchListener(ResizeTouch())
        panel.addView(grip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(16)))
        renderLang()
        placePartial()
    }

    private fun toggleLangRow() {
        langScroll.visibility = if (langScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        renderLang()
        applyListHeight(true)
    }

    private fun renderLang() {
        val l = Langs.of(curLang)
        langBtn.text = (if (autoLang) "Tự: " else "") + l.short + " ▾"
        langRow.removeAllViews()
        val pick = { code: String ->
            langScroll.visibility = View.GONE
            host.onPickLang(code)
        }
        langRow.addView(chip("Tự động theo tên video", autoLang) { pick("auto") })
        for (x in Langs.ALL) {
            langRow.addView(View(ctx), LinearLayout.LayoutParams(ctx.dp(6), 1))
            langRow.addView(chip(x.name, !autoLang && x.code == curLang) { pick(x.code) })
        }
    }

    /* ---------------------------------------------------------------- hiện / ẩn */

    fun show() {
        layoutPanel()
        addPanel()
    }

    fun hide() {
        removePanel()
        removeBubble()
    }

    fun collapse() {
        removePanel()
        if (!bubbleShown) {
            val dm = ctx.resources.displayMetrics
            bubbleLp.x = dm.widthPixels - ctx.dp(64)
            bubbleLp.y = dm.heightPixels / 2
            try { wm.addView(bubble, bubbleLp); bubbleShown = true } catch (e: Exception) { }
        }
    }

    fun expand() {
        removeBubble()
        layoutPanel()
        addPanel()
    }

    /** Xoay màn hình: tính lại bề rộng. */
    fun relayout() {
        if (!panelShown) return
        layoutPanel()
        try { wm.updateViewLayout(panel, panelLp) } catch (e: Exception) { }
    }

    private fun layoutPanel() {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val width = if (w > h) min((w * 0.62f).toInt(), ctx.dp(600)) else min(w - ctx.dp(16), ctx.dp(500))
        panelLp.width = width
        panelLp.x = (w - width) / 2
        val saved = prefs.panelY
        panelLp.y = if (saved >= 0) saved.coerceIn(0, maxOf(0, h - ctx.dp(140))) else (h * 0.56f).toInt()
        applyListHeight(false)
    }

    /** Phần bong bóng ngoài danh sách câu (đầu, hàng ngôn ngữ, dòng thông tin, cảnh báo, vạch kéo, lề). */
    private fun chromeHeight(): Int {
        var c = (if (header.height > 0) header.height else ctx.dp(40)) + ctx.dp(16 + 8)
        if (langScroll.visibility == View.VISIBLE) c += if (langScroll.height > 0) langScroll.height else ctx.dp(40)
        if (infoView.visibility == View.VISIBLE) c += if (infoView.height > 0) infoView.height + ctx.dp(3) else ctx.dp(36)
        if (warnBox.visibility == View.VISIBLE) c += if (warnBox.height > 0) warnBox.height + ctx.dp(4) else ctx.dp(56)
        return c
    }

    /** Chỗ còn lại cho danh sách câu tính từ chỗ đặt bong bóng tới đáy màn hình (chừa thanh điều hướng). */
    private fun roomForList(): Int {
        val h = ctx.resources.displayMetrics.heightPixels
        return h - panelLp.y - chromeHeight() - ctx.dp(48)
    }

    /**
     * Chiều cao tối đa của danh sách: theo cài đặt (phần màn hình), nhưng không để bong bóng tràn khỏi đáy;
     * bong bóng nằm quá thấp thì đẩy lên cho vừa.
     */
    private fun applyListHeight(update: Boolean) {
        val h = ctx.resources.displayMetrics.heightPixels
        val lo = ctx.dp(90)
        val want = (h * prefs.listFrac).toInt().coerceIn(lo, maxOf(lo, (h * 0.8f).toInt()))
        if (roomForList() < lo) {
            panelLp.y = maxOf(0, h - ctx.dp(48) - chromeHeight() - lo)
            if (update && panelShown) try { wm.updateViewLayout(panel, panelLp) } catch (e: Exception) { }
        }
        val cap = minOf(want, maxOf(lo, roomForList()))
        if (scroll.maxH != cap) {
            scroll.maxH = cap
            scroll.requestLayout()
        }
    }

    private fun addPanel() {
        if (panelShown) return
        try { wm.addView(panel, panelLp); panelShown = true } catch (e: Exception) { }
    }

    private fun removePanel() {
        if (!panelShown) return
        try { wm.removeView(panel) } catch (e: Exception) { }
        panelShown = false
    }

    private fun removeBubble() {
        if (!bubbleShown) return
        try { wm.removeView(bubble) } catch (e: Exception) { }
        bubbleShown = false
    }

    /* ---------------------------------------------------------------- cập nhật */

    fun setLang(code: String, auto: Boolean) {
        if (code == curLang && auto == autoLang) return
        curLang = code
        autoLang = auto
        renderLang()
    }

    fun setStatus(text: String) {
        if (status.text.toString() != text) status.text = text
    }

    /** Dòng thông tin nhỏ (đang tải mô hình/gói dịch, lỗi dịch…); null để ẩn. */
    fun setInfo(text: String?) {
        val was = infoView.visibility
        if (text.isNullOrEmpty()) infoView.visibility = View.GONE
        else {
            if (infoView.text.toString() != text) infoView.text = text
            infoView.visibility = View.VISIBLE
        }
        if (infoView.visibility != was) applyListHeight(true)
    }

    fun setPlaying(playing: Boolean) {
        val t = if (playing) "❚❚" else "▶"
        if (playBtn.text.toString() != t) playBtn.text = t
    }

    /** 0 = chưa nghe, 1 = đang nghe (im), 2 = đang có tiếng, 3 = lỗi */
    fun setDot(state: Int) {
        if (state == dotState) return
        dotState = state
        dot.background = oval(
            when (state) {
                1 -> Pal.ACCENT_SOFT
                2 -> Pal.ACCENT
                3 -> Pal.DANGER
                else -> Pal.LINE
            }
        )
    }

    fun setWarning(text: String?, action: String? = null, onAction: (() -> Unit)? = null) {
        val was = warnBox.visibility
        if (text == null) {
            warnBox.visibility = View.GONE
            warnAction = null
        } else {
            warnText.text = text
            warnBtn.text = action ?: ""
            warnBtn.visibility = if (action == null) View.GONE else View.VISIBLE
            warnAction = onAction
            warnBox.visibility = View.VISIBLE
        }
        if (warnBox.visibility != was) applyListHeight(true)
    }

    /** Cập nhật danh sách câu (cùng một danh sách với dịch vụ, đã xếp theo thời gian trong video). */
    fun setLines(all: List<Line>) {
        lines = all
        val present = HashSet<Line>(all)
        var stale = 0
        for (i in 0 until linesBox.childCount) {
            val l = linesBox.getChildAt(i).tag as? Line
            if (l != null && l !in present) stale++
        }
        if (stale > linesBox.childCount / 2) linesBox.removeAllViews()      // đổi sang video khác
        else for (i in linesBox.childCount - 1 downTo 0) {
            val v = linesBox.getChildAt(i)
            if (v === partialView) continue
            val l = v.tag as? Line
            if (l == null || l !in present) linesBox.removeViewAt(i)
        }
        views.keys.retainAll(present)
        if (activeView?.let { it.line !in present } == true) activeView = null
        linesBox.removeView(partialView)
        for ((i, l) in all.withIndex()) {
            val lv = views.getOrPut(l) { LineView(l) }
            if (linesBox.getChildAt(i) !== lv.box) {
                (lv.box.parent as? ViewGroup)?.removeView(lv.box)
                linesBox.addView(lv.box, i)
            }
        }
        placePartial()
        tick(posMs)
    }

    /** Câu vừa có thêm bản nghe chính xác / bính âm / nghĩa tiếng Việt. */
    fun updateLine(l: Line) {
        views[l]?.bind()
    }

    fun setPartial(partial: String) {
        if (partial == partialText) return
        partialText = partial
        placePartial()
    }

    /** Gọi 10 lần/giây với vị trí video: tô câu đang phát, tự cuộn theo. */
    fun tick(pos0: Long) {
        var pos = pos0
        if (seekTarget >= 0) {
            if (SystemClock.uptimeMillis() > seekUntil || (pos0 >= 0 && abs(pos0 - seekTarget) < 1000)) seekTarget = -1
            else pos = seekTarget
        }
        posMs = pos
        val lv = findActive(pos)?.let { views[it] }
        if (lv !== activeView) {
            activeView?.setActive(false)
            activeView = lv
            lv?.setActive(true)
        }
        lv?.let { it.setSeg(segAt(it, pos)) }
        if (partialText.isNotEmpty()) placePartial()
        if (!following && SystemClock.uptimeMillis() - lastUserScroll > 15000) follow()
        if (following && panelShown) autoScroll()
    }

    /** Vừa ra lệnh tua tới ms: trong lúc YouTube chưa báo vị trí mới, tô và cuộn tới câu ở đó luôn. */
    fun seekingTo(ms: Long) {
        seekTarget = ms
        seekUntil = SystemClock.uptimeMillis() + 1500
        follow()
    }

    /** Thôi xem câu cũ, cuộn theo video lại. */
    fun follow() {
        following = true
        followBtn.visibility = View.GONE
        if (panelShown) autoScroll()
    }

    private fun userScrolled() {
        lastUserScroll = SystemClock.uptimeMillis()
        if (following) {
            following = false
            followBtn.visibility = View.VISIBLE
        }
    }

    /** Số câu (ở đầu danh sách) bắt đầu trước hoặc đúng lúc pos; pos < 0 thì là cả danh sách. */
    private fun insertIndex(pos: Long): Int {
        if (pos < 0) return lines.size
        var lo = 0
        var hi = lines.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val s = lines[mid].startMs
            if (s in 0..pos) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Câu đang phát tại pos: câu bắt đầu gần nhất, còn được tô tới khi câu sau bắt đầu (tối đa HOLD sau khi nói xong). */
    private fun findActive(pos: Long): Line? {
        if (pos < 0) return null
        val i = insertIndex(pos + LEAD) - 1
        if (i < 0) return null
        val l = lines[i]
        if (l.startMs < 0) return null
        val next = lines.getOrNull(i + 1)?.startMs?.takeIf { it >= 0 } ?: Long.MAX_VALUE
        val until = maxOf(l.endMs, minOf(l.endMs + HOLD, next))
        return if (pos <= until) l else null
    }

    /** Từ đang nói trong câu: -1 = chưa tới từ nào, segs.size = đã nói xong cả câu. */
    private fun segAt(lv: LineView, pos: Long): Int {
        val segs = lv.shownBody?.segs ?: return -1
        if (segs.isEmpty()) return -1
        val p = pos + LEAD
        if (p > maxOf(lv.line.endMs, segs.last().ms + 300) + 250) return segs.size
        var lo = 0
        var hi = segs.size - 1
        var k = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segs[mid].ms <= p) { k = mid; lo = mid + 1 } else hi = mid - 1
        }
        return k
    }

    /** Giữ câu đang phát (hoặc chữ đang nghe dở / câu mới nhất) trong khung nhìn. */
    private fun autoScroll() {
        val h = scroll.height
        if (h <= 0 || linesBox.height <= h || touching) return
        // câu mới chưa được đặt chỗ (vị trí còn là 0): đợi lượt sau
        if (linesBox.isLayoutRequested || scroll.isLayoutRequested) return
        val now = SystemClock.uptimeMillis()
        if (now < scrollAnimUntil) return
        val av = activeView
        val target: View = av?.box
            ?: (if (partialView.parent != null) partialView else null)
            ?: lines.getOrNull(insertIndex(posMs) - 1)?.let { views[it]?.box }
            ?: return
        if (target.parent !== linesBox || !target.isLaidOut) return
        val top = target.top
        val bottom = target.bottom
        val y = scroll.scrollY
        val margin = (h * 0.1f).toInt()
        if (top >= y + margin && bottom <= y + h - margin) return
        val dest = (if (av != null || bottom - top > h - 2 * margin) top - (h * 0.25f).toInt() else bottom - h + margin)
            .coerceIn(0, linesBox.height - h)
        if (abs(dest - y) < ctx.dp(4)) return
        scroll.smoothScrollTo(0, dest)
        scrollAnimUntil = now + 350
    }

    /** Chữ đang nghe dở: đặt ngay sau câu cuối cùng trước vị trí video (thường là cuối danh sách). */
    private fun placePartial() {
        partialView.textSize = prefs.textSize - 1f
        val show = partialText.isNotEmpty() || lines.isEmpty()
        if (!show) {
            if (partialView.parent != null) linesBox.removeView(partialView)
            return
        }
        val t = if (partialText.isNotEmpty()) partialText else "Đang nghe… câu sẽ hiện ở đây khi video có người nói."
        if (partialView.text.toString() != t) partialView.text = t
        val idx = insertIndex(posMs)
        val cur = linesBox.indexOfChild(partialView)
        if (cur != idx) {
            if (cur >= 0) linesBox.removeViewAt(cur)
            linesBox.addView(partialView, min(idx, linesBox.childCount))
        }
    }

    private fun cycleTextSize() {
        val sizes = intArrayOf(15, 17, 20, 23)
        val i = sizes.indexOf(prefs.textSize)
        prefs.textSize = sizes[(i + 1) % sizes.size]
        for (v in views.values) v.applySize()
        placePartial()
    }

    /* ---------------------------------------------------------------- một câu trên bong bóng */

    private inner class LineView(val line: Line) {
        val box = LinearLayout(ctx)
        private val py = TextView(ctx)
        private val tx = TextView(ctx)
        private val vi = TextView(ctx)
        private var body: Body? = null
        private var pin: PinyinText? = null
        private var active = false
        private var seg = Int.MIN_VALUE

        val shownBody: Body? get() = body

        init {
            box.orientation = LinearLayout.VERTICAL
            box.tag = line
            box.setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(5))
            py.setTextColor(Pal.PINYIN)
            tx.setTextColor(Pal.TEXT)
            vi.setTextColor(Pal.MUTED)
            box.addView(py)
            box.addView(tx)
            box.addView(vi)
            box.setOnClickListener { if (line.startMs >= 0) host.onSeekLine(line) }
            applySize()
            bind()
        }

        fun applySize() {
            val size = prefs.textSize.toFloat()
            tx.textSize = size
            py.textSize = size - 4f
            vi.textSize = size - 4f
        }

        fun bind() {
            val b = line.body
            body = b
            pin = line.pinyin?.takeIf { it.src == b.text && it.start.size == b.text.length }
            py.visibility = if (pin != null) View.VISIBLE else View.GONE
            val v = line.vi
            if (v.isNullOrEmpty()) vi.visibility = View.GONE
            else {
                if (vi.text.toString() != v) vi.text = v
                vi.visibility = View.VISIBLE
            }
            paint()
        }

        fun setActive(on: Boolean) {
            if (on == active) return
            active = on
            box.background = if (on) rounded(Pal.HL_BG, ctx.dp(10).toFloat()) else null
            vi.setTextColor(if (on) Pal.ACCENT else Pal.MUTED)
            vi.typeface = if (on) medium else Typeface.DEFAULT
            seg = Int.MIN_VALUE
            paint()
        }

        fun setSeg(k: Int) {
            if (k == seg) return
            seg = k
            paint()
        }

        /** Chữ đã nói: màu thường; từ đang nói: nền xanh; chữ chưa tới: xám. Bính âm tô theo cùng chữ Hán. */
        private fun paint() {
            val b = body ?: return
            val t = b.text
            val segs = b.segs
            val p = pin
            if (!active || segs.isEmpty() || seg == Int.MIN_VALUE) {
                tx.text = t
                if (p != null) py.text = p.text
                return
            }
            val k = seg
            val spoken = when {
                k < 0 -> 0
                k >= segs.size -> t.length
                else -> segs[k].to.coerceIn(0, t.length)
            }
            val cur = if (k in segs.indices) segs[k] else null
            val st = SpannableString(t)
            if (spoken < t.length) st.setSpan(ForegroundColorSpan(Pal.UPCOMING), spoken, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (cur != null) {
                val a = cur.from.coerceIn(0, t.length)
                val e = cur.to.coerceIn(a, t.length)
                if (e > a) st.setSpan(BackgroundColorSpan(Pal.HL_WORD), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            tx.text = st
            if (p != null && t.isNotEmpty()) {
                val pt = p.text
                val sp = if (spoken >= t.length) pt.length else p.start[spoken].coerceIn(0, pt.length)
                val ps = SpannableString(pt)
                if (sp < pt.length) ps.setSpan(ForegroundColorSpan(Pal.PINYIN_UP), sp, pt.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (cur != null) {
                    val a = p.start[cur.from.coerceIn(0, t.length - 1)].coerceIn(0, pt.length)
                    val e = p.end[(cur.to - 1).coerceIn(0, t.length - 1)].coerceIn(a, pt.length)
                    if (e > a) ps.setSpan(BackgroundColorSpan(Pal.HL_WORD), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                py.text = ps
            }
        }
    }

    /* ---------------------------------------------------------------- cuộn, thanh trượt, đổi chiều cao */

    /** Khung cuộn có chiều cao tối đa; báo khi người dùng tự vuốt. */
    private inner class ListScroll(c: Context) : ScrollView(c) {
        var maxH = 0
        private var downY = 0f
        private var dragging = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var hs = heightMeasureSpec
            if (maxH > 0) {
                val mode = MeasureSpec.getMode(heightMeasureSpec)
                val size = MeasureSpec.getSize(heightMeasureSpec)
                val cap = if (mode == MeasureSpec.UNSPECIFIED) maxH else min(maxH, size)
                hs = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
            }
            super.onMeasure(widthMeasureSpec, hs)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.y; dragging = false; touching = true }
                MotionEvent.ACTION_MOVE -> if (!dragging && abs(ev.y - downY) > ctx.dp(8)) {
                    dragging = true
                    if (linesBox.height > height) userScrolled()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    /** Thanh trượt bên phải danh sách: kéo nút xanh để lướt nhanh tới câu cũ. */
    private inner class Rail(c: Context) : View(c) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.LINE }
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.ACCENT }
        private val r = RectF()
        private val d = c.resources.displayMetrics.density

        /** [đỉnh, chiều cao] của nút kéo; null khi danh sách chưa dài quá khung. */
        private fun thumbOf(): FloatArray? {
            val content = linesBox.height
            val vis = scroll.height
            if (vis <= 0 || height <= 0 || content <= vis + 2) return null
            val h = height.toFloat()
            val th = maxOf(32 * d, h * vis / content).coerceAtMost(h)
            val top = (h - th) * scroll.scrollY / (content - vis)
            return floatArrayOf(top.coerceIn(0f, h - th), th)
        }

        override fun onDraw(canvas: Canvas) {
            val t = thumbOf() ?: return
            val cx = width - 7 * d
            r.set(cx - 1.5f * d, 2 * d, cx + 1.5f * d, height - 2 * d)
            canvas.drawRoundRect(r, 1.5f * d, 1.5f * d, track)
            r.set(cx - 3.5f * d, t[0], cx + 3.5f * d, t[0] + t[1])
            canvas.drawRoundRect(r, 3.5f * d, 3.5f * d, thumb)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val t = thumbOf() ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) scroll.fling(0)   // dừng hiệu ứng cuộn đang chạy
                    val room = height - t[1]
                    if (room >= 1f) {
                        val frac = ((e.y - t[1] / 2) / room).coerceIn(0f, 1f)
                        scroll.scrollTo(0, (frac * (linesBox.height - scroll.height)).toInt())
                    }
                    userScrolled()
                }
            }
            return true
        }
    }

    /** Kéo vạch dưới đáy bong bóng để đổi chiều cao danh sách câu (nhớ cho lần sau). Chạm nhầm thì không đổi gì. */
    private inner class ResizeTouch : View.OnTouchListener {
        private var downY = 0f
        private var startH = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val h = ctx.resources.displayMetrics.heightPixels
            val lo = ctx.dp(90)
            val hi = maxOf(lo, minOf((h * 0.8f).toInt(), roomForList()))
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!moved) {
                        if (abs(dy) < ctx.dp(8)) return true
                        moved = true
                        downY = e.rawY
                        startH = (if (scroll.height > 0) scroll.height else scroll.maxH).coerceIn(lo, hi)
                    }
                    setH((startH + (e.rawY - downY)).toInt().coerceIn(lo, hi), true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (moved) {
                    prefs.listFrac = scroll.maxH.toFloat() / h
                    setH(scroll.maxH, false)
                }
            }
            return true
        }

        private fun setH(px: Int, dragging: Boolean) {
            scroll.maxH = px
            // đang kéo: giữ đúng chiều cao để thấy khung to/nhỏ ra sao, kể cả khi chưa có nhiều câu
            scroll.minimumHeight = if (dragging) px else 0
            scroll.requestLayout()
        }
    }

    /* ---------------------------------------------------------------- kéo thả */

    private inner class DragTouch(
        private val view: View,
        private val lp: WindowManager.LayoutParams,
        private val horizontal: Boolean,
        private val onTap: (() -> Unit)?
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && abs(dx) < ctx.dp(6) && abs(dy) < ctx.dp(6)) return true
                    moved = true
                    if (horizontal) lp.x = startX + dx.toInt()
                    lp.y = maxOf(0, startY + dy.toInt())
                    try { wm.updateViewLayout(view, lp) } catch (ex: Exception) { }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    else if (view === panel) {
                        prefs.panelY = lp.y
                        applyListHeight(true)
                    }
                    return true
                }
            }
            return false
        }
    }

    /** Nút tròn khi thu nhỏ: ba nhát chém pastel trên nền xanh rêu. */
    private class BubbleView(c: Context) : View(c) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.ACCENT }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val colors = intArrayOf(0xFFEAF6E7.toInt(), 0xFFBFE3B8.toInt(), 0xFF8FCB86.toInt())

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val s = context.dp(52)
            setMeasuredDimension(s, s)
        }

        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            canvas.drawCircle(s / 2, s / 2, s / 2, bg)
            stroke.strokeWidth = s * 0.075f
            for (i in 0..2) {
                stroke.color = colors[i]
                val o = (i - 1) * s * 0.15f
                canvas.drawLine(s * 0.36f + o, s * 0.70f, s * 0.58f + o, s * 0.30f, stroke)
            }
        }
    }
}
