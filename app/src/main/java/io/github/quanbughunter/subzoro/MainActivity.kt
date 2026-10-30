package io.github.quanbughunter.subzoro

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Màn hình cài đặt: cấp quyền, tải mô hình nghe, chọn nguồn tiếng, bật/tắt bong bóng.
 * Giao diện dựng bằng mã cho gọn.
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_PROJECTION = 11
        private const val REQ_PERMS = 12
        private const val YT = "com.google.android.youtube"
    }

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private val rows = ArrayList<PermRow>()
    private val packRows = ArrayList<PackRow>()

    private lateinit var srcNote: TextView
    private lateinit var startBtn: TextView
    private lateinit var startNote: TextView

    private val onDownloads: () -> Unit = { for (r in packRows) r.refresh(); refreshStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(build())
        // chữ/biểu tượng thanh trạng thái màu tối trên nền sáng.
        // Phải gọi SAU setContentView: trước đó cửa sổ chưa có khung (bản 0.1.1 văng ngay khi mở vì gọi sớm).
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.decorView.windowInsetsController?.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } catch (e: Exception) { }
        }
    }

    override fun onResume() {
        super.onResume()
        Downloads.addListener(onDownloads)
        refresh()
        ui.postDelayed(poll, 1000)
    }

    override fun onPause() {
        super.onPause()
        Downloads.removeListener(onDownloads)
        ui.removeCallbacks(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            refreshStart()
            ui.postDelayed(this, 1000)
        }
    }

    /* ---------------------------------------------------------------- dựng giao diện */

    private fun text(s: String, size: Float = 15f, color: Int = Pal.TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = if (primary) 16f else 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(if (primary) 13 else 8), dp(16), dp(if (primary) 13 else 8))
        setOnClickListener { if (isEnabled) onClick() }
        styleButton(this, primary, true)
    }

    private fun styleButton(b: TextView, primary: Boolean, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.45f
        if (primary) {
            b.background = rounded(Pal.ACCENT, dp(12).toFloat())
            b.setTextColor(0xFFFFFFFF.toInt())
        } else {
            b.background = rounded(Pal.SURFACE, dp(10).toFloat(), Pal.ACCENT, dp(1))
            b.setTextColor(Pal.ACCENT)
        }
    }

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Pal.SURFACE, dp(16).toFloat(), Pal.LINE, dp(1))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(text(title, 13f, Pal.ACCENT, true).apply { letterSpacing = 0.04f })
    }

    private fun LinearLayout.gap(h: Int) = addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(h)))

    private fun check(label: String, on: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        textSize = 15f
        setTextColor(Pal.TEXT)
        isChecked = on
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        root.addView(text("subZoro", 28f, Pal.ACCENT, true))
        root.addView(text("Phụ đề nổi trên YouTube — bản thử $version", 14f, Pal.MUTED))
        root.gap(16)

        // Lần trước app bị văng: hiện lỗi để chụp gửi
        val crash = File(filesDir, App.CRASH_FILE)
        if (crash.exists()) {
            val log = try { crash.readText() } catch (e: Exception) { "" }
            val box = card("SUBZORO BỊ LỖI LẦN TRƯỚC")
            box.gap(6)
            box.addView(text("Chụp màn hình phần dưới (hoặc bấm Sao chép rồi dán) gửi để sửa.", 13f, Pal.MUTED))
            box.gap(6)
            box.addView(TextView(this).apply {
                text = if (log.length > 3500) log.substring(0, 3500) + "\n…" else log
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(Pal.DANGER)
                setTextIsSelectable(true)
            })
            box.gap(8)
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            acts.addView(button("Sao chép lỗi", false) {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("subZoro lỗi", log))
                Toast.makeText(this, "Đã sao chép", Toast.LENGTH_SHORT).show()
            })
            acts.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
            acts.addView(button("Xoá", false) {
                crash.delete()
                root.removeView(box)
            })
            box.addView(acts)
            root.addView(box)
            root.gap(12)
        }

        // 1. Quyền
        val perms = card("1 · QUYỀN CẦN CẤP")
        rows += PermRow("Hiển thị trên ứng dụng khác", "Để bong bóng phụ đề nổi trên YouTube.",
            { Settings.canDrawOverlays(this) },
            { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        rows += PermRow("Micro", "Android bắt buộc quyền này cả khi chỉ nghe tiếng phát ra từ YouTube.",
            { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            { requestPerms() })
        if (Build.VERSION.SDK_INT >= 33) rows += PermRow("Thông báo", "Hiện thông báo “subZoro đang nghe” có nút Tắt.",
            { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
            { requestPerms() })
        rows += PermRow("Truy cập thông báo (nên có)",
            "Để biết video đang chạy tới giây nào (chạm câu là tua lại) và đọc tên video để tự đoán ngôn ngữ. Nếu mục subZoro bị mờ: mở Thông tin ứng dụng → ⋮ → Cho phép cài đặt bị hạn chế, rồi bật lại.",
            { VideoClock.hasAccess(this) },
            { openListenerSettings() },
            extra = "Thông tin ứng dụng" to { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) || Build.BRAND.equals("Redmi", ignoreCase = true)) {
            rows += PermRow("Xiaomi: cửa sổ bật lên",
                "Máy Xiaomi/Redmi cần bật thêm “Hiển thị cửa sổ bật lên khi đang chạy trong nền” cho subZoro.",
                null, { openXiaomiPerms() })
        }
        for (r in rows) { perms.gap(12); perms.addView(r.view) }
        root.addView(perms)
        root.gap(12)

        // 2. Ngôn ngữ + mô hình
        val models = card("2 · NGÔN NGỮ VÀ MÔ HÌNH NGHE")
        models.gap(4)
        models.addView(text("Đổi ngôn ngữ ngay trên bong bóng bằng nút EN / RU / 中文. Mỗi thứ tiếng cần tải mô hình của nó một lần.", 13f, Pal.MUTED))
        models.addView(check("Tự đoán ngôn ngữ theo tên video", prefs.autoLang) { prefs.autoLang = it })
        models.gap(4)
        models.addView(text("Ngôn ngữ khi không đoán được:", 13.5f, Pal.TEXT))
        val group = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (l in Langs.ALL) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = l.name
                textSize = 14f
                setTextColor(Pal.TEXT)
                isChecked = l.code == prefs.lang
                setOnCheckedChangeListener { _, on -> if (on) { prefs.lang = l.code; refreshStart() } }
            })
        }
        models.addView(group)
        models.gap(10)
        models.addView(text("Nghe nhanh — chữ hiện ngay theo từng từ (Vosk)", 14f, Pal.TEXT, true))
        for (code in listOf("en", "ru", "zh")) {
            val r = PackRow(Packs.vosk(code))
            packRows += r
            models.gap(6)
            models.addView(r.view)
        }
        models.gap(12)
        models.addView(text("Nghe chính xác (tuỳ chọn) — mỗi câu được nghe lại và sửa sau ~1 giây, có dấu câu", 14f, Pal.TEXT, true))
        models.addView(text("SenseVoice dùng chung cho tiếng Trung và tiếng Anh; GigaAM v2 cho tiếng Nga. Nên tải bằng Wi-Fi.", 13f, Pal.MUTED))
        for (p in listOf(Packs.SENSEVOICE, Packs.GIGAAM)) {
            val r = PackRow(p)
            packRows += r
            models.gap(6)
            models.addView(r.view)
        }
        models.addView(check("Dùng mô hình chính xác khi đã tải", prefs.accurate) { prefs.accurate = it })
        root.addView(models)
        root.gap(12)

        // 3. Nguồn tiếng + dịch
        val srcCard = card("3 · NGHE TỪ ĐÂU, DỊCH")
        val sg = RadioGroup(this)
        sg.addView(RadioButton(this).apply {
            id = View.generateViewId()
            text = "Tiếng phát ra từ YouTube (rõ nhất)"
            textSize = 15f
            setTextColor(Pal.TEXT)
            isChecked = prefs.source == "app"
            setOnCheckedChangeListener { _, on -> if (on) prefs.source = "app" }
        })
        sg.addView(RadioButton(this).apply {
            id = View.generateViewId()
            text = "Micro (khi YouTube chặn bắt tiếng)"
            textSize = 15f
            setTextColor(Pal.TEXT)
            isChecked = prefs.source == "mic"
            setOnCheckedChangeListener { _, on -> if (on) prefs.source = "mic" }
        })
        srcCard.addView(sg)
        srcNote = text("", 13f, Pal.MUTED)
        srcCard.addView(srcNote)
        srcCard.gap(8)
        srcCard.addView(check("Dịch sang tiếng Việt", prefs.translate) { prefs.translate = it })
        srcCard.addView(text("Dịch ngay trên máy bằng bộ dịch của Google (tự tải gói ~30 MB mỗi thứ tiếng lần đầu, sau đó không cần mạng). Không được thì dùng Google Dịch qua mạng.", 13f, Pal.MUTED))
        root.addView(srcCard)
        root.gap(16)

        // 4. Bật
        startBtn = button("Bật bong bóng phụ đề", true) { onStartButton() }
        root.addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.gap(6)
        startNote = text("", 13f, Pal.MUTED).apply { gravity = Gravity.CENTER }
        root.addView(startNote)
        root.gap(16)

        val tips = card("MẸO")
        tips.gap(6)
        tips.addView(text(
            "• Khi Android hỏi chia sẻ/ghi màn hình, bấm Bắt đầu: subZoro chỉ lấy tiếng, không ghi hình, không gửi tiếng đi đâu.\n" +
                "• Trên bong bóng: nút ngôn ngữ (EN/RU/中文) để đổi thứ tiếng; chạm vào câu để tua lại câu đó, ↺ nghe lại câu vừa rồi, Aa đổi cỡ chữ, — thu nhỏ thành nút tròn, ✕ tắt. Kéo dòng trạng thái để dời bong bóng lên/xuống.\n" +
                "• Tiếng Trung: dòng trên là bính âm, dòng dưới là nghĩa tiếng Việt.\n" +
                "• Samsung: Cài đặt → Chăm sóc thiết bị/Pin → Giới hạn sử dụng nền → Ứng dụng không bao giờ ngủ → thêm subZoro.\n" +
                "• Xiaomi/Redmi: Cài đặt → Ứng dụng → subZoro → Tiết kiệm pin → Không giới hạn.",
            13.5f, Pal.TEXT))
        root.addView(tips)

        return ScrollView(this).apply {
            setBackgroundColor(Pal.BG)
            addView(root)
            // Android 15 vẽ tràn dưới thanh trạng thái/thanh điều hướng: chừa chỗ cho chúng
            setOnApplyWindowInsetsListener { _, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = insets.getInsets(WindowInsets.Type.systemBars())
                    top = b.top
                    bottom = b.bottom
                } else {
                    @Suppress("DEPRECATION")
                    top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottom = insets.systemWindowInsetBottom
                }
                root.setPadding(dp(16), dp(20) + top, dp(16), dp(28) + bottom)
                insets
            }
        }
    }

    /** Một dòng quyền: tên, giải thích, nút Cấp quyền / ✓ Đã cấp. granted = null nếu không kiểm tra được. */
    private inner class PermRow(
        title: String,
        desc: String,
        private val granted: (() -> Boolean)?,
        private val request: () -> Unit,
        extra: Pair<String, () -> Unit>? = null
    ) {
        val view = LinearLayout(this@MainActivity)
        private val btn: TextView

        init {
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER_VERTICAL
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(title, 15f, Pal.TEXT, true))
            texts.addView(text(desc, 13f, Pal.MUTED))
            if (extra != null) {
                texts.addView(text(extra.first, 13f, Pal.ACCENT, true).apply {
                    setPadding(0, dp(4), 0, 0)
                    setOnClickListener { extra.second() }
                })
            }
            view.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
            btn = button(if (granted == null) "Mở" else "Cấp quyền", false) { request() }
            view.addView(btn)
        }

        fun refresh() {
            val g = granted ?: return
            val ok = g()
            btn.text = if (ok) "✓ Đã cấp" else "Cấp quyền"
            styleButton(btn, false, !ok)
        }
    }

    /** Một gói mô hình: tên + dung lượng + trạng thái, nút Tải / Huỷ / Xoá, thanh tiến độ. */
    private inner class PackRow(private val p: Pack) {
        val view = LinearLayout(this@MainActivity)
        private val info: TextView
        private val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        private val btn: TextView

        init {
            view.orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(p.name + "  ·  ~${p.mb} MB", 14.5f, Pal.TEXT))
            info = text("", 12.5f, Pal.MUTED)
            texts.addView(info)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
            btn = button("Tải", false) { onButton() }
            row.addView(btn)
            view.addView(row)
            view.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(4) })
            refresh()
        }

        private fun onButton() {
            when {
                Downloads.running(p) -> Downloads.cancel(p)
                Packs.ready(this@MainActivity, p) -> {
                    Packs.delete(this@MainActivity, p)
                    refresh()
                    refreshStart()
                }
                else -> Downloads.start(this@MainActivity, p)
            }
        }

        fun refresh() {
            val running = Downloads.running(p)
            val s = Downloads.state(p)
            when {
                running -> {
                    btn.text = "Huỷ"
                    bar.visibility = View.VISIBLE
                    if (s != null && s.total > 0) {
                        bar.isIndeterminate = false
                        bar.progress = (s.done * 1000 / s.total).toInt()
                    } else bar.isIndeterminate = true
                    info.text = Downloads.describe(p) ?: "Đang tải…"
                }
                Packs.ready(this@MainActivity, p) -> {
                    btn.text = "Xoá"
                    bar.visibility = View.GONE
                    info.text = "✓ Đã có trên máy"
                }
                else -> {
                    btn.text = "Tải"
                    bar.visibility = View.GONE
                    info.text = s?.error?.let { if (it == "đã huỷ") "Đã huỷ tải — bấm Tải để tải tiếp phần còn lại." else "Tải không được: $it. Kiểm tra mạng (có thể cần VPN) rồi bấm Tải lại — phần đã tải được giữ lại." } ?: "Chưa tải"
                }
            }
        }
    }

    /* ---------------------------------------------------------------- cập nhật */

    private fun refresh() {
        for (r in rows) r.refresh()
        for (r in packRows) r.refresh()
        srcNote.text = when (prefs.appCapture) {
            "ok" -> "✓ Máy này bắt được tiếng YouTube."
            "silent" -> "Lần trước không nghe được tiếng YouTube trên máy này — nếu vẫn vậy, chọn Micro."
            else -> "Chưa thử. Nếu bong bóng báo không nghe thấy tiếng, chuyển sang Micro."
        }
        refreshStart()
    }

    private fun anyVosk() = Langs.ALL.any { Packs.ready(this, Packs.vosk(it.code)) }

    private fun refreshStart() {
        if (!::startBtn.isInitialized) return
        val running = CaptionService.running
        val needOverlay = !Settings.canDrawOverlays(this)
        val needMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        val needModel = !anyVosk()
        startBtn.text = if (running) "Tắt bong bóng" else "Bật bong bóng phụ đề"
        val can = running || (!needOverlay && !needMic && !needModel)
        styleButton(startBtn, true, can)
        startNote.text = when {
            running -> "Bong bóng đang chạy. Mở YouTube để xem phụ đề."
            needOverlay || needMic -> "Cấp quyền hiển thị trên ứng dụng khác và micro trước."
            needModel -> "Tải ít nhất một mô hình “Nghe nhanh” ở mục 2."
            else -> "Bấm xong, subZoro sẽ mở YouTube cho bạn."
        }
    }

    /* ---------------------------------------------------------------- hành động */

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e2: Exception) { }
        }
    }

    private fun requestPerms() {
        val list = ArrayList<String>()
        list += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(list.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun openListenerSettings() {
        val i = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, MediaListener::class.java).flattenToString())
        } else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        try { startActivity(i) } catch (e: Exception) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun openXiaomiPerms() {
        val i = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", packageName)
        open(i)
    }

    private fun onStartButton() {
        if (CaptionService.running) {
            stopService(Intent(this, CaptionService::class.java))
            ui.postDelayed({ refreshStart() }, 300)
            return
        }
        if (prefs.source == "app") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_PROJECTION)
            } catch (e: Exception) {
                Toast.makeText(this, "Máy không mở được hộp chia sẻ màn hình: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            CaptionService.start(this, 0, null)
            openYouTube()
        }
    }

    @Deprecated("Activity API cũ, đủ dùng ở đây")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            CaptionService.start(this, resultCode, data)
            openYouTube()
        } else {
            Toast.makeText(this, "Cần bấm Bắt đầu để subZoro nghe được tiếng YouTube (hoặc chọn nguồn Micro).", Toast.LENGTH_LONG).show()
        }
    }

    private fun openYouTube() {
        ui.postDelayed({
            val i = packageManager.getLaunchIntentForPackage(YT)
            if (i != null) try { startActivity(i) } catch (e: Exception) { }
            else Toast.makeText(this, "Mở app YouTube và phát video.", Toast.LENGTH_LONG).show()
        }, 600)
    }
}
