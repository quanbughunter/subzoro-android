package io.github.quanbughunter.subzoro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
 *   - Mở: nút ngôn ngữ + trạng thái + nút điều khiển; 3 câu gần nhất (tiếng Trung có bính âm phía trên)
 *     và nghĩa tiếng Việt nhỏ ngay bên dưới; câu đang nghe dở in nghiêng.
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
    private val linesBox = LinearLayout(ctx)
    private val partialView = TextView(ctx)
    private val bubble = BubbleView(ctx)

    private val panelLp = baseParams()
    private val bubbleLp = baseParams()
    private var panelShown = false
    private var bubbleShown = false
    private var dotState = -1
    private var warnAction: (() -> Unit)? = null

    private var lines: List<Line> = emptyList()
    private var partialText = ""
    private var curLang = "en"
    private var autoLang = true

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
        panel.setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(8), ctx.dp(10))

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

        linesBox.orientation = LinearLayout.VERTICAL
        panel.addView(linesBox)

        partialView.setTextColor(Pal.MUTED)
        partialView.setTypeface(null, Typeface.ITALIC)
        panel.addView(partialView)
        renderLang()
        renderLines()
    }

    private fun toggleLangRow() {
        langScroll.visibility = if (langScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        renderLang()
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
        if (text.isNullOrEmpty()) { infoView.visibility = View.GONE; return }
        if (infoView.text.toString() != text) infoView.text = text
        infoView.visibility = View.VISIBLE
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
        if (text == null) { warnBox.visibility = View.GONE; warnAction = null; return }
        warnText.text = text
        warnBtn.text = action ?: ""
        warnBtn.visibility = if (action == null) View.GONE else View.VISIBLE
        warnAction = onAction
        warnBox.visibility = View.VISIBLE
    }

    fun setLines(all: List<Line>, partial: String) {
        lines = all.takeLast(3).toList()
        partialText = partial
        renderLines()
    }

    fun setPartial(partial: String) {
        if (partial == partialText) return
        partialText = partial
        renderPartial()
    }

    private fun renderLines() {
        linesBox.removeAllViews()
        val size = prefs.textSize.toFloat()
        lines.forEachIndexed { i, l ->
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            box.alpha = if (i == lines.size - 1) 1f else 0.6f
            val py = l.pinyin
            if (!py.isNullOrEmpty()) box.addView(TextView(ctx).apply {
                text = py
                textSize = size - 4f
                setTextColor(Pal.PINYIN)
            })
            box.addView(TextView(ctx).apply {
                text = l.text
                textSize = size
                setTextColor(Pal.TEXT)
            })
            val vi = l.vi
            if (!vi.isNullOrEmpty()) box.addView(TextView(ctx).apply {
                text = vi
                textSize = size - 4f
                setTextColor(Pal.MUTED)
            })
            if (l.startMs >= 0) box.setOnClickListener { host.onSeekLine(l) }
            linesBox.addView(box)
        }
        renderPartial()
    }

    private fun renderPartial() {
        val size = prefs.textSize.toFloat()
        partialView.textSize = size - 1f
        when {
            partialText.isNotEmpty() -> { partialView.text = partialText; partialView.visibility = View.VISIBLE }
            lines.isEmpty() -> { partialView.text = "Đang nghe… câu sẽ hiện ở đây khi video có người nói."; partialView.visibility = View.VISIBLE }
            else -> partialView.visibility = View.GONE
        }
    }

    private fun cycleTextSize() {
        val sizes = intArrayOf(15, 17, 20, 23)
        val i = sizes.indexOf(prefs.textSize)
        prefs.textSize = sizes[(i + 1) % sizes.size]
        renderLines()
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
                    else if (view === panel) prefs.panelY = lp.y
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
