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
import kotlin.math.sqrt

/**
 * Dịch vụ chạy nền khi bong bóng đang mở:
 *   - nghe tiếng YouTube (AudioPlaybackCapture, cần người dùng đồng ý "chia sẻ màn hình") hoặc nghe qua micro,
 *   - đưa tiếng cho Vosk nhận dạng ngay trên máy, chữ hiện dần theo từng từ,
 *   - gắn mỗi câu với thời điểm trong video (đọc phiên phát của YouTube) để chạm vào câu là tua lại đúng chỗ,
 *   - dịch câu sang tiếng Việt nếu bật.
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
    private var overlay: Overlay? = null
    private var clock: VideoClock? = null
    private var clockOk = false
    @Volatile private var projection: MediaProjection? = null
    private var engine: Engine? = null
    private var source = Source.MIC
    private var destroying = false

    private val lines = ArrayList<Line>()
    private var partialText = ""
    private val tr: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var lastLoudAt = 0L
    @Volatile private var listenSince = 0L
    @Volatile private var engineState = "loading"   // loading | listening | error
    private var warned = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
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
        overlay = Overlay(this, this).also { it.show() }
        clock = VideoClock(this) { ui.post { refreshStatus() } }
        clockOk = clock?.start() == true
        startEngine(source)
        ui.post(ticker)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroying = true
        running = false
        ui.removeCallbacks(ticker)
        val e = engine
        engine = null
        if (e != null) Thread { e.halt() }.start()
        overlay?.hide()
        overlay = null
        clock?.stop()
        clock = null
        try { projection?.stop() } catch (ex: Exception) { }
        projection = null
        tr.shutdownNow()
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

    /* ---------------------------------------------------------------- trạng thái */

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            refreshStatus()
            checkSilence()
            ui.postDelayed(this, 500)
        }
    }

    private fun refreshStatus() {
        val o = overlay ?: return
        val c = clock
        if (c != null && clockOk && !c.connected) c.refresh()
        val parts = ArrayList<String>()
        parts += when (engineState) {
            "listening" -> if (source == Source.APP) "Nghe tiếng YouTube" else "Nghe qua micro"
            "error" -> "Lỗi"
            else -> "Đang nạp mô hình ${lang.name}…"
        }
        when {
            c != null && c.connected -> parts += fmtTime(c.positionMs()) + if (c.isPlaying) "" else " · đang dừng"
            !clockOk -> parts += "chưa có quyền đọc vị trí video"
            else -> parts += "chưa thấy YouTube phát"
        }
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
    }

    /** Bắt tiếng YouTube mà toàn im lặng khi video đang chạy: có thể YouTube không cho app khác bắt tiếng. */
    private fun checkSilence() {
        if (engineState != "listening" || source != Source.APP) return
        val now = SystemClock.elapsedRealtime()
        val heard = lastLoudAt > listenSince
        if (heard && prefs.appCapture != "ok") prefs.appCapture = "ok"
        if (warned && now - lastLoudAt < 1500) {
            warned = false
            overlay?.setWarning(null, null)
            return
        }
        if (warned) return
        val c = clock
        val videoPlaying = c == null || !c.connected || c.isPlaying
        if (videoPlaying && now - listenSince > 7000 && now - lastLoudAt > 6000) {
            warned = true
            if (!heard) {
                prefs.appCapture = "silent"
                overlay?.setWarning("Không nghe thấy tiếng nào từ YouTube. Có thể YouTube không cho app khác bắt tiếng (hoặc video đang tắt tiếng).", "Dùng micro")
            } else {
                overlay?.setWarning("Một lúc rồi không nghe thấy tiếng từ video.", null)
            }
        }
    }

    /* ---------------------------------------------------------------- nút trên bong bóng */

    override fun onReplay() {
        val l = lines.lastOrNull { it.startMs >= 0 }
        if (l == null) { needClock(); return }
        onSeekLine(l)
    }

    override fun onSeekLine(line: Line) {
        val c = clock
        if (c == null || !c.connected || line.startMs < 0) { needClock(); return }
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

    override fun onWarnAction() {
        overlay?.setWarning(null, null)
        warned = false
        val old = engine
        engine = null
        engineState = "loading"
        Thread {
            old?.halt()
            ui.post { if (running) { source = Source.MIC; startEngine(Source.MIC) } }
        }.start()
    }

    private fun needClock() {
        toast(if (!clockOk) "Cần bật “Truy cập thông báo” cho subZoro (trong app subZoro) để tua video." else "Chưa thấy YouTube đang phát.")
    }

    /* ---------------------------------------------------------------- câu */

    private fun addLine(l: Line) {
        lines.add(l)
        if (lines.size > 300) lines.subList(0, 100).clear()
        partialText = ""
        overlay?.setLines(lines, partialText)
        if (prefs.translate) {
            val sl = lang.gtx
            try {
                tr.execute {
                    val vi = Translator.translate(l.text, sl)
                    if (vi != null && !vi.equals(l.text, ignoreCase = true)) {
                        l.vi = vi
                        ui.post { overlay?.setLines(lines, partialText) }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun setPartial(p: String) {
        partialText = p
        overlay?.setPartial(p)
    }

    /* ---------------------------------------------------------------- nghe + nhận dạng */

    private fun startEngine(s: Source) {
        engineState = "loading"
        warned = false
        engine = Engine(s, lang).also { it.start() }
    }

    private class Anchor(val sample: Long, val videoMs: Long, val playing: Boolean, val speed: Float)

    private inner class Engine(private val src: Source, private val lang: Lang) : Thread("subzoro-nghe") {
        @Volatile private var stopFlag = false
        private val anchors = ArrayList<Anchor>()

        fun halt() {
            stopFlag = true
            try { join(4000) } catch (e: InterruptedException) { }
        }

        override fun run() {
            var rec: AudioRecord? = null
            var model: Model? = null
            var recog: Recognizer? = null
            try {
                model = Model(Models.dir(this@CaptionService, lang).absolutePath)
                recog = Recognizer(model, RATE.toFloat())
                recog.setWords(true)
                rec = buildRecorder()
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("không bắt đầu ghi được (micro đang bị app khác dùng?)")
                listenSince = SystemClock.elapsedRealtime()
                lastLoudAt = 0L
                engineState = "listening"
                val buf = ShortArray(RATE / 10)
                var total = 0L
                var lastPartial = ""
                while (!stopFlag) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) throw IllegalStateException("lỗi đọc tiếng ($n)")
                    if (n == 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                    if (sqrt(sum / n) > LOUD) lastLoudAt = SystemClock.elapsedRealtime()
                    addAnchor(total, n)
                    total += n
                    if (recog.acceptWaveForm(buf, n)) {
                        handleFinal(recog.result)
                        lastPartial = ""
                    } else {
                        val p = cleanup(JSONObject(recog.partialResult).optString("partial"))
                        if (p != lastPartial) {
                            lastPartial = p
                            ui.post { setPartial(p) }
                        }
                    }
                }
                handleFinal(recog.finalResult)
            } catch (e: Throwable) {
                if (!stopFlag) {
                    engineState = "error"
                    val msg = e.message ?: e.toString()
                    ui.post { overlay?.setWarning("Lỗi khi nghe: $msg", null) }
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

        /** Ghi mốc: mẫu âm thanh thứ `sample` ứng với giây nào của video (đọc ngay lúc gói tiếng vừa tới). */
        private fun addAnchor(sample: Long, n: Int) {
            val c = clock
            val pos = if (c != null && c.connected) c.positionMs() else -1L
            val playing = c?.isPlaying == true
            val speed = c?.speed ?: 1f
            val start = if (pos >= 0 && playing) pos - (n * 1000L / RATE * speed).toLong() else pos
            synchronized(anchors) {
                anchors.add(Anchor(sample, start, playing, speed))
                if (anchors.size > 9000) anchors.subList(0, 3000).clear()   // giữ ~10 phút gần nhất
            }
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

        private fun cleanup(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return ""
            return if (lang.code == "zh") t.replace(" ", "") else t.replaceFirstChar { it.uppercase() }
        }

        private fun handleFinal(json: String) {
            val o = try { JSONObject(json) } catch (e: Exception) { return }
            val text = cleanup(o.optString("text"))
            if (text.isEmpty()) return
            val words = o.optJSONArray("result")
            // một từ lẻ mà máy không chắc: thường là tiếng nhạc/tiếng ồn
            if (words != null && words.length() == 1 && words.getJSONObject(0).optDouble("conf", 1.0) < 0.8) {
                ui.post { setPartial("") }
                return
            }
            var start = -1L
            var end = -1L
            if (words != null && words.length() > 0) {
                start = videoAt((words.getJSONObject(0).optDouble("start", -1.0) * RATE).toLong())
                end = videoAt((words.getJSONObject(words.length() - 1).optDouble("end", -1.0) * RATE).toLong())
            }
            val line = Line(text, start, end)
            ui.post { addLine(line) }
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
