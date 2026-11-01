package app.usbmusic.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.usbmusic.player.data.Track
import app.usbmusic.player.data.Video
import app.usbmusic.player.data.VideoPositions
import java.util.Locale
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
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set

    /** 今の曲・動画に切り替わった時刻（動画の冒頭タイトル表示・フェードに使う） */
    var itemChangedAt by mutableLongStateOf(0L)
        private set

    /** いま再生中なのが動画か */
    val isVideo: Boolean
        get() = MediaItems.isVideo(current) || tracks.containsType(C.TRACK_TYPE_VIDEO)

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
        if (p.currentMediaItem?.mediaId != current?.mediaId) itemChangedAt = SystemClock.elapsedRealtime()
        current = p.currentMediaItem
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        duration = p.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        tracks = p.currentTracks
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

    /** 動画を再生（前回の続きから）。リストの次の動画へも続けて進める */
    fun playVideos(context: Context, videos: List<Video>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (videos.isEmpty()) return
        val index = if (shuffled) Random.nextInt(videos.size) else start.coerceIn(0, videos.size - 1)
        val resume = VideoPositions.get(context, videos[index].key)?.first ?: 0L
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(videos.map(MediaItems::from), index, if (shuffled) 0L else resume)
        c.prepare()
        c.play()
    }

    /** 音声・字幕の選択肢 */
    class TrackOption(val label: String, val selected: Boolean, val group: TrackGroup?, val index: Int)

    fun options(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        for (g in tracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += TrackOption(describe(g.getTrackFormat(i), type, out.size + 1), g.isTrackSelected(i), g.mediaTrackGroup, i)
            }
        }
        return out
    }

    val subtitlesOn: Boolean
        get() = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }

    fun select(type: Int, option: TrackOption) {
        val c = controller ?: return
        val group = option.group ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group, option.index))
            .build()
    }

    fun subtitlesOff() {
        val c = controller ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun describe(f: Format, type: Int, n: Int): String {
        val parts = ArrayList<String>()
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            runCatching { Locale.forLanguageTag(it).getDisplayLanguage(Locale.JAPANESE) }.getOrNull()?.takeIf { d -> d.isNotBlank() } ?: it
        }
        f.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (lang != null && parts.none { it.contains(lang) }) parts += lang
        val mime = f.sampleMimeType.orEmpty()
        val codec = when {
            mime.contains("eac3") -> "Dolby Digital Plus"
            mime.contains("ac3") -> "AC3"
            mime.contains("dts") -> "DTS"
            mime.contains("truehd") -> "TrueHD"
            mime.contains("mp4a") -> "AAC"
            mime.contains("mpeg") && type == C.TRACK_TYPE_AUDIO -> "MP3"
            mime.contains("opus") -> "Opus"
            mime.contains("vorbis") -> "Vorbis"
            mime.contains("flac") -> "FLAC"
            mime.contains("subrip") -> "SRT"
            mime.contains("ssa") -> "ASS"
            mime.contains("pgs") -> "PGS"
            mime.contains("vobsub") -> "VobSub"
            mime.contains("vtt") -> "WebVTT"
            else -> null
        }
        codec?.let { parts += it }
        if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) {
            parts += when (f.channelCount) {
                1 -> "モノラル"
                2 -> "ステレオ"
                6 -> "5.1ch"
                8 -> "7.1ch"
                else -> "${f.channelCount}ch"
            }
        }
        return if (parts.isEmpty()) "トラック $n" else parts.joinToString(" · ")
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
