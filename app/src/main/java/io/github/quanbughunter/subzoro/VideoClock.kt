package io.github.quanbughunter.subzoro

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/** Chỉ cần khai báo: có quyền "Truy cập thông báo" thì mới đọc được phiên phát của app khác. */
class MediaListener : NotificationListenerService()

/**
 * Đồng hồ video: đọc phiên phát (MediaSession) của app YouTube — vị trí, đang phát/dừng, tốc độ, tên video —
 * và điều khiển ngược lại (tua, phát/dừng). Đây chính là thanh tua trong khung điều khiển nhạc khi kéo thông báo xuống.
 */
class VideoClock(private val ctx: Context, private val onChange: () -> Unit) {
    companion object {
        val YT_PACKAGES = setOf(
            "com.google.android.youtube",
            "app.revanced.android.youtube",
            "app.rvx.android.youtube",
            "com.vanced.android.youtube"
        )

        fun hasAccess(ctx: Context): Boolean {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
            return nm.isNotificationListenerAccessGranted(ComponentName(ctx, MediaListener::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val comp = ComponentName(ctx, MediaListener::class.java)

    @Volatile var controller: MediaController? = null
        private set
    private var started = false

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
        override fun onSessionDestroyed() {
            controller = null
            refresh()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    /** false nếu chưa có quyền "Truy cập thông báo". */
    fun start(): Boolean {
        if (msm == null || !hasAccess(ctx)) return false
        return try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, comp, handler)
            started = true
            refresh()
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        if (started) try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { }
        controller?.unregisterCallback(cb)
        controller = null
        started = false
    }

    fun refresh() {
        if (!started) return
        try { pick(msm?.getActiveSessions(comp)) } catch (e: SecurityException) { }
    }

    private fun pick(list: List<MediaController>?) {
        val yts = list.orEmpty().filter { it.packageName in YT_PACKAGES }
        val best = yts.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: yts.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb, handler)
            onChange()       // chỉ báo khi đổi phiên (báo mỗi lần sẽ thành vòng lặp với refreshStatus)
        }
    }

    val connected: Boolean get() = controller != null

    val isPlaying: Boolean get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    val speed: Float
        get() {
            val s = controller?.playbackState ?: return 1f
            return if (s.playbackSpeed > 0f) s.playbackSpeed else 1f
        }

    val title: String? get() = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

    /** Khoá phân biệt video đang phát: tên + độ dài (giây). Độ dài 0 = chưa biết / phát trực tiếp. */
    val videoKey: String?
        get() {
            val m = controller?.metadata ?: return null
            val t = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
            return t + "|" + maxOf(0L, m.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000)
        }

    /** Vị trí hiện tại trong video (ms), -1 nếu không biết. */
    fun positionMs(): Long {
        val s = controller?.playbackState ?: return -1
        if (s.position < 0) return -1
        var p = s.position
        if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * speed).toLong()
        }
        return p
    }

    fun seekTo(ms: Long) {
        controller?.transportControls?.seekTo(maxOf(0L, ms))
    }

    fun play() { controller?.transportControls?.play() }

    fun togglePlay() {
        val c = controller ?: return
        if (isPlaying) c.transportControls.pause() else c.transportControls.play()
    }
}
