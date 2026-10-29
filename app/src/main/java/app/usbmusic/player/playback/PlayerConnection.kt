package app.usbmusic.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.usbmusic.player.data.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlin.random.Random

/** 画面側から再生サービスを操作し、再生状態を画面に反映するための窓口 */
class PlayerConnection(private val context: Context) {

    var controller by mutableStateOf<MediaController?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var current by mutableStateOf<MediaItem?>(null)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var queueVersion by mutableIntStateOf(0)
        private set

    private var future: ListenableFuture<MediaController>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player)
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueVersion++
        }
    }

    fun connect(onConnected: (MediaController) -> Unit) {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c != null && future === f) {
                controller = c
                c.addListener(listener)
                sync(c)
                queueVersion++
                onConnected(c)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    private fun sync(p: Player) {
        isPlaying = p.isPlaying
        current = p.currentMediaItem
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        duration = p.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
    }

    val position: Long get() = controller?.currentPosition ?: 0L

    fun playTracks(tracks: List<Track>, start: Int = 0, shuffled: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        val items = tracks.map(MediaItems::from)
        c.shuffleModeEnabled = shuffled
        val startIndex = if (shuffled) Random.nextInt(tracks.size) else start.coerceIn(0, tracks.size - 1)
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun next() {
        controller?.seekToNext()
    }

    /** 3秒以上再生していれば曲の頭へ、そうでなければ前の曲へ（CarPlayと同じ） */
    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun jumpTo(index: Int) {
        val c = controller ?: return
        c.seekToDefaultPosition(index)
        c.play()
    }

    /** 次に再生される曲（シャッフル順を反映） */
    fun upcoming(limit: Int): List<Pair<Int, MediaItem>> {
        val c = controller ?: return emptyList()
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = ArrayList<Pair<Int, MediaItem>>()
        var i = c.currentMediaItemIndex
        while (out.size < limit) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) break
            out += i to c.getMediaItemAt(i)
        }
        return out
    }
}
