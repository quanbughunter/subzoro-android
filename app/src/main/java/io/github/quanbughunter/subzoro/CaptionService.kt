package io.github.quanbughunter.subzoro

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dịch vụ chạy nền khi bong bóng đang mở:
 *   - nghe tiếng YouTube (AudioPlaybackCapture, cần đồng ý "chia sẻ màn hình") hoặc nghe qua micro,
 *   - Vosk nhận dạng ngay, chữ hiện dần theo từng từ;
 *   - xong mỗi câu: nghe lại đoạn đó bằng mô hình chính xác (SenseVoice/GigaAM) nếu đã tải, thêm bính âm (tiếng Trung),
 *     dịch sang tiếng Việt;
 *   - gắn câu với thời điểm trong video (đọc phiên phát của YouTube) để chạm vào câu là tua lại;
 *   - câu xếp theo dòng thời gian của từng video: tua về đoạn đã có thì không nghe lại thành câu lặp,
 *     bong bóng chỉ tô sáng câu đó theo từng lời;
 *   - đổi ngôn ngữ ngay trên bong bóng, hoặc tự đoán theo tên video.
 */
class CaptionService : Service(), OverlayHost {
    companion object {
        const val ACTION_STOP = "io.github.quanbughunter.subzoro.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 7
        private const val CHANNEL = "subzoro"
        private const val RATE = 16000
        private const val LOUD = 0.0015      // ~ -56 dBFS: dưới mức này coi như im
        /** từ nằm trong một câu đã có, hoặc cách mép câu không quá chừng này (ms), coi là đã có */
        private const val COVER_PAD = 200L
        /** nhóm từ mới bắt đầu/kết thúc cách câu đã có không quá chừng này (ms) thì coi là sát câu cũ */
        private const val NEAR_PAD = 700L
        /** số câu tối đa giữ cho mỗi video */
        private const val MAX_LINES = 1500

        @Volatile var running = false
            private set

        /** data = kết quả hộp "chia sẻ màn hình" (null = nghe qua micro). */
        fun start(ctx: Context, code: Int, data: Intent?) {
            val i = Intent(ctx, CaptionService::class.java).putExtra(EXTRA_CODE, code)
            if (data != null) i.putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }
    }

    private enum class Source { APP, MIC }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var lang: Lang
    private var autoLang = true
    private var overlay: Overlay? = null
    private var clock: VideoClock? = null
    private var clockOk = false
    @Volatile private var projection: MediaProjection? = null
    private var engine: Engine? = null
    private var source = Source.MIC
    private var destroying = false
    private var lastTitle: String? = null
    private var lastKey: String? = null

    /** Câu của từng video (khoá = tên + độ dài video); video xem gần nhất ở cuối, giữ tối đa 4 video. */
    private val sessions = LinkedHashMap<String, ArrayList<Line>>()
    private var sessionKey: String? = null
    /** câu nghe được trước mẫu tiếng này (của luồng nghe hiện tại) thuộc video trước, không đưa vào video này */
    private var sessionFrom = 0L
    /** Câu của video đang xem, xếp theo thời điểm trong video (câu không rõ thời điểm ở cuối). */
    private var lines = ArrayList<Line>()
    /** Những đoạn đã có câu, cho luồng nghe biết để không nghe lại thành câu lặp. */
    @Volatile private var cover = Cover.EMPTY
    private var partialText = ""
    /** nghe lại câu bằng mô hình chính xác + bính âm (lần lượt từng câu) */
    private val work: ExecutorService = Executors.newSingleThreadExecutor()
    /** dịch (có thể phải chờ tải gói dịch) */
    private val tr: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var refiner: Refiner? = null
    @Volatile private var refinerLoading: String? = null

    @Volatile private var lastLoudAt = 0L
    @Volatile private var listenSince = 0L
    @Volatile private var engineState = "loading"   // loading | listening | error | nomodel
    private var warned = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        autoLang = prefs.autoLang
        lang = Langs.of(prefs.lang)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bong bóng phụ đề", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) {
            overlay?.expand()
            return START_NOT_STICKY
        }
        val data = intent?.let { intentExtra(it, EXTRA_DATA) }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val types = if (data != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, buildNotification(), types)
        } catch (e: Exception) {
            toast("Không chạy nền được: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (!Settings.canDrawOverlays(this)) {
            toast("subZoro chưa có quyền hiển thị trên ứng dụng khác.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (data != null) {
            try {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projection = mpm.getMediaProjection(code, data)?.also { it.registerCallback(projectionCallback, ui) }
            } catch (e: Exception) {
                toast("Không mở được quyền nghe tiếng: ${e.message}")
            }
        }
        source = if (projection != null) Source.APP else Source.MIC
        clock = VideoClock(this) { ui.post { refreshStatus() } }
        clockOk = clock?.start() == true
        lastTitle = clock?.title
        lastKey = clock?.videoKey
        lastKey?.let { sessionKey = it; sessions[it] = lines }
        // tự đoán ngôn ngữ theo video đang mở (nếu đã có mô hình)
        if (autoLang) {
            val g = Langs.guess(lastTitle)
            if (g != null && Packs.ready(this, Packs.vosk(g))) lang = Langs.of(g)
        }
        overlay = Overlay(this, this).also {
            it.setLang(lang.code, autoLang)
            it.show()
        }
        redraw()
        Downloads.addListener(onDownloads)
        startEngineIfReady()
        ui.post(ticker)
        ui.post(posTicker)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroying = true
        running = false
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(posTicker)
        Downloads.removeListener(onDownloads)
        val e = engine
        engine = null
        if (e != null) Thread { e.halt() }.start()
        overlay?.hide()
        overlay = null
        clock?.stop()
        clock = null
        try { projection?.stop() } catch (ex: Exception) { }
        projection = null
        work.execute {
            refiner?.release()
            refiner = null
        }
        work.shutdown()
        tr.shutdownNow()
        Translator.close()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.relayout()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // người dùng bấm "dừng chia sẻ" trên thanh trạng thái
            if (!destroying && source == Source.APP) {
                toast("Đã dừng chia sẻ — subZoro tắt bong bóng.")
                stopSelf()
            }
        }
    }

    /* ---------------------------------------------------------------- ngôn ngữ + mô hình */

    override fun onPickLang(code: String) {
        if (code == "auto") {
            autoLang = true
            prefs.autoLang = true
            val g = Langs.guess(clock?.title)
            switchLang(if (g != null && Packs.ready(this, Packs.vosk(g))) g else lang.code)
        } else {
            autoLang = false
            prefs.autoLang = false
            prefs.lang = code
            switchLang(code)
        }
        overlay?.setLang(lang.code, autoLang)
    }

    private fun switchLang(code: String) {
        if (code == lang.code && engine != null) return
        lang = Langs.of(code)
        rebuildCover()
        redraw()
        overlay?.setLang(lang.code, autoLang)
        val old = engine
        engine = null
        engineState = "loading"
        setPartial("")
        Thread {
            old?.halt()
            ui.post { if (running) startEngineIfReady() }
        }.start()
    }

    private fun startEngineIfReady() {
        if (engine != null) return
        val p = Packs.vosk(lang.code)
        if (!Packs.ready(this, p)) {
            engineState = "nomodel"
            if (Downloads.running(p)) overlay?.setWarning("Đang tải mô hình nghe ${lang.name}…")
            else overlay?.setWarning("Chưa có mô hình nghe ${lang.name} trên máy (~${p.mb} MB).", "Tải") {
                Downloads.start(this, p)
                overlay?.setWarning("Đang tải mô hình nghe ${lang.name}…")
            }
            return
        }
        overlay?.setWarning(null)
        warned = false
        engineState = "loading"
        sessionFrom = 0L          // luồng nghe mới đếm mẫu tiếng lại từ 0
        engine = Engine(source, lang).also { it.start() }
        prepareRefiner(lang.code)
    }

    /** Nạp mô hình nghe chính xác cho ngôn ngữ đang nghe (nếu đã tải); làm trên luồng nền vì nạp mất vài giây. */
    private fun prepareRefiner(code: String) {
        val p = Packs.accurate(code)
        if (!prefs.accurate || p == null || !Packs.ready(this, p)) {
            // ngôn ngữ này chưa có mô hình chính xác: trả lại bộ nhớ của mô hình cũ (~250 MB)
            if (refiner != null && refiner?.lang != code) {
                try { work.execute { refiner?.release(); refiner = null } } catch (e: Exception) { }
            }
            return
        }
        if (refiner?.lang == code) return
        refinerLoading = code
        try {
            work.execute {
                try {
                    if (refiner?.lang != code) {
                        refiner?.release()
                        refiner = null
                        refiner = Refiner.create(this, code)
                    }
                } catch (e: Throwable) {
                    refiner = null
                    ui.post { overlay?.setWarning("Không nạp được mô hình nghe chính xác: ${e.message}") }
                } finally {
                    if (refinerLoading == code) refinerLoading = null
                }
            }
        } catch (e: Exception) { }
    }

    private val onDownloads: () -> Unit = {
        if (running) {
            if (engine != null) prepareRefiner(lang.code)
            if (engine == null && engineState == "nomodel" && Packs.ready(this, Packs.vosk(lang.code))) startEngineIfReady()
            else if (engine == null && engineState == "nomodel") {
                val p = Packs.vosk(lang.code)
                val s = Downloads.state(p)
                if (s != null && s.finished && s.error != null) {
                    overlay?.setWarning("Tải mô hình ${lang.name} không được: ${s.error}", "Thử lại") { Downloads.start(this, p) }
                }
            }
            refreshStatus()
        }
    }

    /* ---------------------------------------------------------------- trạng thái */

    private var tick = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            // chưa thấy YouTube: 3 giây hỏi lại danh sách phiên phát một lần (phòng khi bỏ lỡ sự kiện)
            val c = clock
            if (c != null && clockOk && !c.connected && ++tick % 6 == 0) c.refresh()
            checkTitle()
            refreshStatus()
            checkSilence()
            ui.postDelayed(this, 500)
        }
    }

    /** Vị trí video cho bong bóng tô câu đang phát (10 lần/giây khi bong bóng đang mở). */
    private val posTicker = object : Runnable {
        override fun run() {
            if (!running) return
            val o = overlay
            if (o != null && o.expanded) {
                val c = clock
                o.tick(if (c != null && c.connected) c.positionMs() else -1L)
                ui.postDelayed(this, 100)
            } else {
                ui.postDelayed(this, 400)
            }
        }
    }

    /**
     * Video khác (tên hoặc độ dài khác, kể cả quảng cáo): chuyển sang danh sách câu của video đó;
     * nếu đang "Tự động" thì đoán lại ngôn ngữ (chỉ đổi khi đã có mô hình nghe của ngôn ngữ đoán được).
     */
    private fun checkTitle() {
        val c = clock ?: return
        val key = c.videoKey ?: return
        if (key != lastKey) {
            lastKey = key
            switchSession(key)
        }
        val t = c.title ?: return
        if (t == lastTitle) return
        lastTitle = t
        if (!autoLang) return
        val g = Langs.guess(t) ?: return
        if (g != lang.code && Packs.ready(this, Packs.vosk(g))) switchLang(g)
    }

    private fun switchSession(key: String) {
        if (key == sessionKey) return
        val old = sessionKey
        // cùng video mà tạm không báo độ dài: giữ nguyên
        if (old != null && key.endsWith("|0") && old.substringBeforeLast('|') == key.substringBeforeLast('|')) return
        if (old == null || (old.substringBeforeLast('|') == key.substringBeforeLast('|') && old.endsWith("|0"))) {
            // video đầu tiên nhận ra được, hoặc cùng video vừa báo thêm độ dài: giữ những câu đã nghe
            if (old != null) sessions.remove(old)
            sessionKey = key
            sessions[key] = lines
            return
        }
        sessionKey = key
        // câu nghe trước lúc đổi video (tính từ lần tua/nhảy gần nhất của luồng nghe) không thuộc video này
        val e = engine
        sessionFrom = if (e == null) 0L else if (e.total - e.lastJump < RATE * 10L) e.lastJump else e.total
        val l = sessions.remove(key) ?: ArrayList()
        sessions[key] = l          // đưa lên cuối = xem gần nhất
        while (sessions.size > 4) sessions.remove(sessions.keys.first())
        lines = l
        partialText = ""
        rebuildCover()
        overlay?.setPartial("")
        redraw()
    }

    private fun rebuildCover() {
        val code = lang.code
        val ls = lines.filter { it.lang == code && it.startMs >= 0 && it.endMs >= it.startMs }
        cover = Cover(code, LongArray(ls.size) { ls[it].startMs }, LongArray(ls.size) { ls[it].endMs })
    }

    private fun refreshStatus() {
        val o = overlay ?: return
        val c = clock
        val parts = ArrayList<String>()
        when {
            c != null && c.connected -> parts += fmtTime(c.positionMs()) + if (c.isPlaying) "" else " (dừng)"
            !clockOk -> parts += "chưa đọc được vị trí video"
            else -> parts += "chưa thấy YouTube phát"
        }
        parts += when (engineState) {
            "listening" -> if (source == Source.APP) "nghe YouTube" else "nghe micro"
            "error" -> "lỗi"
            "nomodel" -> "chưa có mô hình"
            else -> "đang nạp mô hình…"
        }
        if (refiner?.lang == lang.code) parts += "chính xác"
        o.setStatus(parts.joinToString(" · "))
        o.setPlaying(c?.isPlaying == true)
        val now = SystemClock.elapsedRealtime()
        o.setDot(
            when {
                engineState == "error" -> 3
                engineState != "listening" -> 0
                now - lastLoudAt < 600 -> 2
                else -> 1
            }
        )
        // dòng thông tin: tiến độ tải > trạng thái dịch > mẹo tải mô hình chính xác
        val dl = Packs.ALL.firstNotNullOfOrNull { if (Downloads.running(it)) Downloads.describe(it) else null }
        val acc = Packs.accurate(lang.code)
        val info = when {
            dl != null -> dl
            refinerLoading != null -> "Đang nạp mô hình nghe chính xác…"
            prefs.translate && Translator.status != null -> Translator.status
            engineState == "listening" && prefs.accurate && acc != null && !Packs.ready(this, acc) && now - listenSince < 15000 ->
                "Mẹo: tải “Nghe chính xác” (${acc.mb} MB) trong app subZoro để câu đúng hơn, có dấu câu."
            else -> null
        }
        o.setInfo(info)
    }

    /** Bắt tiếng YouTube mà toàn im lặng khi video đang chạy: có thể YouTube không cho app khác bắt tiếng. */
    private fun checkSilence() {
        if (engineState != "listening" || source != Source.APP) return
        val now = SystemClock.elapsedRealtime()
        val heard = lastLoudAt > listenSince
        if (heard && prefs.appCapture != "ok") prefs.appCapture = "ok"
        if (warned && now - lastLoudAt < 1500) {
            warned = false
            overlay?.setWarning(null)
            return
        }
        if (warned) return
        val c = clock
        val videoPlaying = c == null || !c.connected || c.isPlaying
        if (videoPlaying && now - listenSince > 7000 && now - lastLoudAt > 6000) {
            warned = true
            if (!heard) {
                prefs.appCapture = "silent"
                overlay?.setWarning("Không nghe thấy tiếng nào từ YouTube. Có thể YouTube không cho app khác bắt tiếng (hoặc video đang tắt tiếng).", "Dùng micro") { useMic() }
            } else {
                overlay?.setWarning("Một lúc rồi không nghe thấy tiếng từ video.")
            }
        }
    }

    private fun useMic() {
        overlay?.setWarning(null)
        warned = false
        val old = engine
        engine = null
        engineState = "loading"
        Thread {
            old?.halt()
            ui.post { if (running) { source = Source.MIC; startEngineIfReady() } }
        }.start()
    }

    /* ---------------------------------------------------------------- nút trên bong bóng */

    /** ↺: nghe lại câu đang nói (vừa sang câu mới chưa tới 1 giây thì về câu trước). */
    override fun onReplay() {
        val c = clock
        if (c == null || !c.connected) { needClock(); return }
        val timed = lines.filter { it.startMs >= 0 }
        if (timed.isEmpty()) { toast("Chưa có câu nào để nghe lại."); return }
        val pos = c.positionMs()
        val l = timed.lastOrNull { it.startMs <= pos - 1000 } ?: timed.first()
        onSeekLine(l)
    }

    override fun onSeekLine(line: Line) {
        val c = clock
        if (c == null || !c.connected || line.startMs < 0) { needClock(); return }
        overlay?.seekingTo(line.startMs)
        c.seekTo(line.startMs - 250)
        c.play()
    }

    override fun onTogglePlay() {
        val c = clock
        if (c == null || !c.connected) { needClock(); return }
        c.togglePlay()
    }

    override fun onClose() {
        stopSelf()
    }

    private fun needClock() {
        toast(if (!clockOk) "Cần bật “Truy cập thông báo” cho subZoro (trong app subZoro) để tua video." else "Chưa thấy YouTube đang phát.")
    }

    /* ---------------------------------------------------------------- câu */

    /** Bong bóng chỉ hiện câu của ngôn ngữ đang nghe (đổi ngôn ngữ thì câu ngôn ngữ cũ tạm ẩn). */
    private fun redraw() {
        val code = lang.code
        overlay?.setLines(if (lines.all { it.lang == code }) ArrayList(lines) else lines.filter { it.lang == code })
    }

    /** Câu Vosk vừa nghe xong, chưa lọc phần đã có. audio = đoạn tiếng quanh câu, bắt đầu từ mẫu audioFrom. */
    private class Candidate(
        val text: String,
        val words: List<Word>,
        val audio: FloatArray?,
        val audioFrom: Long,
        val map: TimeMap?,
        val lang: String,
        val src: Any
    )

    /**
     * Nhận câu mới từ luồng nghe: bỏ những từ rơi vào đoạn đã có câu (tua lại / chạm vào câu cũ),
     * phần còn lại thành câu mới, chèn đúng chỗ theo thời gian trong video.
     */
    private fun addCandidate(c0: Candidate) {
        if (c0.lang != lang.code || c0.src !== engine) return
        setPartial("")
        checkTitle()     // đổi video chưa kịp nhận ra thì chuyển trước, để câu vào đúng video
        if (c0.lang != lang.code || c0.src !== engine) return
        // từ nghe được trước lúc đổi sang video này thì bỏ
        val words0 = c0.words.filter { it.s0 >= sessionFrom }
        if (c0.words.isNotEmpty() && words0.isEmpty()) return
        // từ nào không rõ thời điểm trong video (vừa mất phiên phát) thì bỏ, nếu các từ khác vẫn rõ
        val timedWords = words0.filter { it.startMs >= 0 && it.endMs >= 0 }
        if (timedWords.isEmpty()) {
            // chưa đọc được vị trí video: giữ cách cũ, nối vào cuối
            val text = Timing.clean(c0.text, c0.lang)
            if (text.isNotEmpty()) insertLine(Line(Body(text, emptyList()), -1, -1, c0.lang), c0.audio, c0.audioFrom, null)
            rebuildCover()
            redraw()
            return
        }
        val words = timedWords
        val cv = cover
        val sameLang = cv.lang == c0.lang
        val covered = BooleanArray(words.size) { i ->
            val w = words[i]
            sameLang && cv.has((w.startMs + w.endMs) / 2, COVER_PAD)
        }
        for (g in Timing.groups(words, covered)) {
            val ws = words.subList(g.first, g.last + 1)
            val cutBefore = g.first > 0 && covered[g.first - 1]
            val cutAfter = g.last < words.size - 1 && covered[g.last + 1]
            // nằm sát một câu đã có (kể cả câu đó không nằm trong lượt chốt này)
            val nearOld = cutBefore || cutAfter ||
                (sameLang && (cv.has(ws.first().startMs, NEAR_PAD) || cv.has(ws.last().endMs, NEAR_PAD)))
            // chữ Hán tính 2 (một chữ đã là một tiếng: 好、对、是)
            var letters = 0
            for (w in ws) for (ch in w.text) letters += if (Timing.isHan(ch)) 2 else if (ch.isLetterOrDigit()) 1 else 0
            if (letters < 2) continue
            // một hai từ sát câu đã có (từ lẻ, hoặc máy không chắc): thường là mẩu của câu cũ nghe lại
            if (nearOld && (ws.size == 1 || (ws.size == 2 && ws.sumOf { it.conf } / 2 < 0.7))) continue
            val body = Timing.fromWords(ws, c0.lang)
            if (body.text.isEmpty()) continue
            val line = Line(body, ws.first().startMs, ws.last().endMs, c0.lang)
            // đoạn tiếng cho lượt nghe chính xác: phía sát câu cũ chỉ chừa 0,08 giây để khỏi lẫn chữ của câu cũ
            var sub: FloatArray? = null
            var subFrom = c0.audioFrom
            val audio = c0.audio
            if (audio != null) {
                val padL = if (cutBefore) RATE * 8 / 100 else RATE * 3 / 10
                val padR = if (cutAfter) RATE * 8 / 100 else RATE * 35 / 100
                val x = (ws.first().s0 - padL - c0.audioFrom).coerceIn(0L, audio.size.toLong()).toInt()
                val y = (ws.last().s1 + padR - c0.audioFrom).coerceIn(x.toLong(), audio.size.toLong()).toInt()
                if (y - x >= RATE / 4) { sub = audio.copyOfRange(x, y); subFrom = c0.audioFrom + x }
            }
            insertLine(line, sub, subFrom, c0.map)
        }
        rebuildCover()
        redraw()
    }

    private fun insertLine(l: Line, audio: FloatArray?, audioFrom: Long, map: TimeMap?) {
        // chèn theo thời điểm trong video (thường là cuối danh sách)
        var i = lines.size
        if (l.startMs >= 0) while (i > 0 && (lines[i - 1].startMs < 0 || lines[i - 1].startMs > l.startMs)) i--
        lines.add(i, l)
        if (lines.size > MAX_LINES) trimLines(i)
        process(l, audio, audioFrom, map)
    }

    /** Quá nhiều câu: bỏ bớt ở đầu xa vị trí đang nghe (câu vừa chèn ở vị trí i). */
    private fun trimLines(i: Int) {
        val n = MAX_LINES / 5
        if (i >= lines.size / 2) lines.subList(0, n).clear()
        else lines.subList(lines.size - n, lines.size).clear()
    }

    /** Nghe lại bằng mô hình chính xác, thêm bính âm, dịch — làm trên luồng nền rồi cập nhật bong bóng. */
    private fun process(l: Line, audio: FloatArray?, audioFrom: Long, map: TimeMap?) {
        try {
            work.execute {
                // 1) nghe lại bằng mô hình chính xác (kèm mốc từng chữ để tô theo lời)
                val r = refiner
                if (r != null && r.lang == l.lang && audio != null && audio.size > RATE / 4) {
                    try {
                        val res = r.recognize(audio)
                        val text = Timing.clean(res.text, l.lang)
                        if (text.isNotEmpty()) {
                            val timed = if (map == null) null else try {
                                Timing.fromTokens(res.tokens, res.times, l.lang) { t -> map.at(audioFrom + (t * RATE).toLong()) }
                            } catch (e: Throwable) { null }
                            l.body = if (timed != null && timed.text.isNotEmpty()) timed
                            else Timing.proportional(text, l.lang, l.startMs, l.endMs)
                            l.refined = true
                        }
                    } catch (e: Throwable) { }
                }
                // 2) bính âm cho tiếng Trung
                if (l.lang == "zh") {
                    try { l.pinyin = Pinyin.mapped(this, l.text) } catch (e: Throwable) { }
                }
                ui.post { overlay?.updateLine(l) }
                // 3) dịch
                if (prefs.translate) {
                    try {
                        tr.execute {
                            val src = l.text
                            val vi = Translator.translate(src, Langs.of(l.lang))
                            if (vi != null && !vi.equals(src, ignoreCase = true)) {
                                l.vi = vi
                                ui.post { overlay?.updateLine(l) }
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { }
    }

    private fun setPartial(p: String) {
        partialText = p
        overlay?.setPartial(p)
    }

    /* ---------------------------------------------------------------- nghe + nhận dạng */

    private class Anchor(val sample: Long, val videoMs: Long, val playing: Boolean, val speed: Float)

    private inner class Engine(private val src: Source, private val lang: Lang) : Thread("subzoro-nghe") {
        @Volatile private var stopFlag = false
        private val anchors = ArrayList<Anchor>()
        /** 45 giây tiếng gần nhất, để nghe lại từng câu bằng mô hình chính xác */
        private val ring = ShortArray(RATE * 45)
        @Volatile var total = 0L
            private set
        /** mẫu tiếng lúc vị trí video nhảy gần nhất (tua, đổi video) */
        @Volatile var lastJump = 0L
            private set
        private var lastFinalEnd = 0L
        private var prevAnchor: Anchor? = null
        private var prevN = 0

        fun halt() {
            stopFlag = true
            try { join(4000) } catch (e: InterruptedException) { }
        }

        override fun run() {
            var rec: AudioRecord? = null
            var model: Model? = null
            var recog: Recognizer? = null
            try {
                model = Model(Packs.dir(this@CaptionService, Packs.vosk(lang.code)).absolutePath)
                recog = Recognizer(model, RATE.toFloat())
                recog.setWords(true)
                rec = buildRecorder()
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("không bắt đầu ghi được (micro đang bị app khác dùng?)")
                listenSince = SystemClock.elapsedRealtime()
                lastLoudAt = 0L
                if (!stopFlag) engineState = "listening"
                val buf = ShortArray(RATE / 10)
                var lastPartial = ""
                var wasCovered = false
                while (!stopFlag) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) throw IllegalStateException("lỗi đọc tiếng ($n)")
                    if (n == 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                    if (sqrt(sum / n) > LOUD) lastLoudAt = SystemClock.elapsedRealtime()
                    val jumped = addAnchor(total, n)
                    for (i in 0 until n) ring[((total + i) % ring.size).toInt()] = buf[i]
                    total += n
                    // gói tiếng này rơi vào đoạn video đã có câu chưa
                    val v = videoAt(total)
                    val cv = cover
                    val covered = v >= 0 && cv.lang == lang.code && cv.has(v, COVER_PAD)
                    if (jumped) {
                        // vừa tua / đổi video: chốt câu đang nghe dở. YouTube báo vị trí mới chậm một chút, nên tiếng
                        // ngay trước lúc nhận ra có thể đã là tiếng sau khi tua (mốc sai): bỏ các từ trong 0,6 giây cuối.
                        lastJump = total - n
                        handleFinal(recog.finalResult, total - n - RATE * 6 / 10)
                    } else if (covered != wasCovered) {
                        // bước vào / ra khỏi đoạn đã có câu: chốt câu đang nghe (giữ nguyên bộ nhận dạng), phần trùng sẽ bị lọc bỏ
                        handleFinal(recog.result)
                    }
                    if (jumped || covered != wasCovered) {
                        if (lastPartial.isNotEmpty()) {
                            lastPartial = ""
                            ui.post { if (engine === this) setPartial("") }
                        }
                    }
                    wasCovered = covered
                    if (recog.acceptWaveForm(buf, n)) {
                        handleFinal(recog.result)
                        lastPartial = ""
                    } else {
                        // đang phát lại đoạn đã có: không hiện chữ nghe dở (câu đó đã nằm sẵn trên bong bóng)
                        val p = if (covered) "" else cleanup(JSONObject(recog.partialResult).optString("partial"))
                        if (p != lastPartial) {
                            lastPartial = p
                            ui.post { if (engine === this) setPartial(p) }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (!stopFlag) {
                    engineState = "error"
                    val msg = e.message ?: e.toString()
                    val me = this
                    ui.post {
                        if (engine === me) {
                            engine = null
                            overlay?.setWarning("Lỗi khi nghe: $msg", "Thử lại") { startEngineIfReady() }
                        }
                    }
                }
            } finally {
                try { rec?.stop() } catch (e: Exception) { }
                rec?.release()
                recog?.close()
                model?.close()
            }
        }

        @SuppressLint("MissingPermission")
        private fun buildRecorder(): AudioRecord {
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val b = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, RATE * 2))
            if (src == Source.APP) {
                val p = projection ?: throw IllegalStateException("chưa được phép nghe tiếng của app khác")
                val conf = AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                b.setAudioPlaybackCaptureConfig(conf)
            } else {
                b.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            return b.build()
        }

        /**
         * Ghi mốc: mẫu âm thanh thứ `sample` ứng với giây nào của video (đọc ngay lúc gói tiếng vừa tới).
         * Trả về true nếu vị trí video nhảy hẳn so với gói trước (người dùng vừa tua, hoặc sang video khác).
         */
        private fun addAnchor(sample: Long, n: Int): Boolean {
            val c = clock
            val pos = if (c != null && c.connected) c.positionMs() else -1L
            val playing = c?.isPlaying == true
            val speed = c?.speed ?: 1f
            val start = if (pos >= 0 && playing) pos - (n * 1000L / RATE * speed).toLong() else pos
            val a = Anchor(sample, start, playing, speed)
            val p = prevAnchor
            var jump = false
            if (p != null && p.videoMs >= 0 && start >= 0) {
                val expect = if (p.playing) p.videoMs + (prevN * 1000L / RATE * p.speed).toLong() else p.videoMs
                jump = abs(start - expect) > 1200
            }
            prevAnchor = a
            prevN = n
            synchronized(anchors) {
                anchors.add(a)
                if (anchors.size > 9000) anchors.subList(0, 3000).clear()   // giữ ~10 phút gần nhất
            }
            return jump
        }

        private fun videoAt(sample: Long): Long {
            if (sample < 0) return -1
            synchronized(anchors) {
                var lo = 0
                var hi = anchors.size - 1
                var k = -1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (anchors[mid].sample <= sample) { k = mid; lo = mid + 1 } else hi = mid - 1
                }
                if (k < 0) return -1
                val a = anchors[k]
                if (a.videoMs < 0) return -1
                return if (a.playing) a.videoMs + ((sample - a.sample) * 1000 / RATE * a.speed).toLong() else a.videoMs
            }
        }

        /** Lấy lại đoạn tiếng [from, to) (số mẫu tính từ lúc bắt đầu nghe); trả về mẫu bắt đầu thật + tiếng. */
        private fun slice(from: Long, to: Long): Pair<Long, FloatArray>? {
            val a = maxOf(from, total - ring.size, 0L)
            val b = minOf(to, total)
            if (b - a < RATE / 4) return null
            return a to FloatArray((b - a).toInt()) { ring[((a + it) % ring.size).toInt()] / 32768f }
        }

        /** Bảng tra mẫu tiếng → ms video cho đoạn [from, to), mỗi 0,1 giây một mốc. */
        private fun timeMap(from: Long, to: Long): TimeMap {
            val step = RATE / 10
            val n = ((to - from) / step + 2).toInt().coerceIn(1, 2000)
            return TimeMap(from, step, LongArray(n) { videoAt(from + it.toLong() * step) })
        }

        private fun cleanup(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return ""
            return if (lang.code == "zh") t.replace(" ", "") else t.replaceFirstChar { it.uppercase() }
        }

        /** maxSample: bỏ những từ kết thúc sau mẫu tiếng này. */
        private fun handleFinal(json: String, maxSample: Long = Long.MAX_VALUE) {
            val o = try { JSONObject(json) } catch (e: Exception) { return }
            val text = cleanup(o.optString("text"))
            val now = total
            if (text.isEmpty()) { lastFinalEnd = now; return }
            val arr = o.optJSONArray("result")
            // một từ lẻ mà máy không chắc: thường là tiếng nhạc/tiếng ồn
            if (arr != null && arr.length() == 1 && arr.getJSONObject(0).optDouble("conf", 1.0) < 0.8) {
                lastFinalEnd = now
                ui.post { if (engine === this) setPartial("") }
                return
            }
            val words = ArrayList<Word>()
            if (arr != null) for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                val t = w.optString("word")
                val st = w.optDouble("start", -1.0)
                val en = w.optDouble("end", -1.0)
                if (t.isBlank() || st < 0 || en < 0) continue
                val s0 = (st * RATE).toLong()
                val s1 = (en * RATE).toLong()
                if (s1 > maxSample) continue
                words += Word(t, videoAt(s0), videoAt(s1), s0, s1, w.optDouble("conf", 1.0))
            }
            if (maxSample != Long.MAX_VALUE && words.isEmpty()) { lastFinalEnd = now; return }
            var from = lastFinalEnd
            var to = now
            if (words.isNotEmpty()) {
                from = words.first().s0 - RATE * 3 / 10
                to = words.last().s1 + RATE * 35 / 100
            }
            lastFinalEnd = now
            val sl = slice(from, to)
            val map = if (sl != null) timeMap(sl.first, sl.first + sl.second.size) else null
            val c = Candidate(text, words, sl?.second, sl?.first ?: 0L, map, lang.code, this)
            ui.post { if (running) addCandidate(c) }
        }
    }

    /* ---------------------------------------------------------------- tiện ích */

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_subzoro)
            .setContentTitle("subZoro đang nghe phụ đề")
            .setContentText("Chạm để mở subZoro · bấm Tắt để dừng")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Tắt", stop).build())
            .build()
    }

    @Suppress("DEPRECATION")
    private fun intentExtra(i: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(key, Intent::class.java) else i.getParcelableExtra(key)

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
