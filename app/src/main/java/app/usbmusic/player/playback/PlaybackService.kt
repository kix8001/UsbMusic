package app.usbmusic.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import app.usbmusic.player.data.VideoPositions
import android.os.SystemClock
import app.usbmusic.player.data.Prefs
import org.json.JSONArray
import kotlin.math.abs
import org.json.JSONObject

/**
 * バックグラウンド再生サービス。
 * MediaSession を持つので、ナビのハンドルボタン・画面上のメディア操作・通知からも操作できる。
 * 再生キューと再生位置を保存し、次にナビを起動した時に続きから再生できるようにする。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private var errorStreak = 0

    private val periodicSave = object : Runnable {
        override fun run() {
            if (player.isPlaying) savePosition()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs(this).let {
            VolumeLeveler.enabled = it.leveler
            PlaybackOptions.videoFade = it.videoFade
        }
        // 音のデータをビジュアライザー用に分岐させる
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(VolumeLeveler, TeeAudioProcessor(Spectrum)))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
            // AC3・DTS など、ナビが再生できない音声は FFmpeg で再生する
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val sessionActivity = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity)
            .build()

        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                itemStart = SystemClock.elapsedRealtime()
                applyItemGain()
                flushVideo()
                trackVideo()
                savePosition()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) errorStreak = 0 else {
                    trackVideo()
                    flushVideo()
                    savePosition()
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                savePosition()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                savePosition()
            }

            override fun onPlayerError(error: PlaybackException) {
                // 読めないファイル（USBが抜けた・壊れたファイル等）は飛ばして次へ
                if (errorStreak < 5 && player.hasNextMediaItem()) {
                    errorStreak++
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        })

        restoreState()
        applyItemGain()
        handler.postDelayed(periodicSave, 10_000)
        handler.postDelayed(videoTick, 1_000)
        handler.post(fadeTick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        trackVideo()
        flushVideo()
        savePosition()
        handler.removeCallbacks(periodicSave)
        handler.removeCallbacks(videoTick)
        handler.removeCallbacks(fadeTick)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        // 画面側から渡された曲に、再生用のURIを付け直す
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { MediaItems.withLocal(it) }
            return Futures.immediateFuture(resolved.toMutableList())
        }
    }

    /** この曲・動画だけの音量（記憶してある分）を適用 */
    private fun applyItemGain() {
        val item = player.currentMediaItem
        VolumeLeveler.itemGain = if (item == null) 1f else ItemGains.linear(ItemGains.get(this, MediaItems.keyOf(item)))
    }

    // ---------- 動画の切り替え時のフェード（音） ----------

    private var itemStart = 0L

    private val fadeTick = object : Runnable {
        override fun run() {
            val v = if (PlaybackOptions.videoFade && MediaItems.isVideo(player.currentMediaItem) && player.isPlaying) {
                fadeLevel(SystemClock.elapsedRealtime() - itemStart, player.currentPosition, player.duration)
            } else 1f
            if (abs(player.volume - v) > 0.01f) player.volume = v
            handler.postDelayed(this, 40)
        }
    }

    // ---------- 動画の「どこまで見たか」 ----------

    private var videoId: String? = null
    private var videoPos = 0L
    private var videoDur = 0L

    private val videoTick = object : Runnable {
        override fun run() {
            if (player.isPlaying) trackVideo()
            handler.postDelayed(this, 1_000)
        }
    }

    /** いま再生中の動画の位置を手元に控える */
    private fun trackVideo() {
        val item = player.currentMediaItem
        if (MediaItems.isVideo(item)) {
            videoId = MediaItems.videoKeyOf(item!!)
            videoPos = player.currentPosition
            videoDur = player.duration.takeIf { it > 0 } ?: 0L
        } else {
            videoId = null
        }
    }

    /** 控えた位置を保存（次に見る時はここから） */
    private fun flushVideo() {
        val id = videoId ?: return
        VideoPositions.put(this, id, videoPos, videoDur)
    }

    // ---------- 続きから再生のための保存・復元 ----------

    private val store by lazy { getSharedPreferences("playback", MODE_PRIVATE) }

    private fun saveQueue() {
        val arr = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val m = item.mediaMetadata
            arr.put(
                JSONObject()
                    .put("id", item.mediaId)
                    .put("t", m.title?.toString() ?: "")
                    .put("a", m.artist?.toString() ?: "")
                    .put("al", m.albumTitle?.toString() ?: "")
                    .put("aa", m.albumArtist?.toString() ?: "")
                    .put("v", MediaItems.isVideo(item))
                    .put("s", JSONArray(MediaItems.subtitlesOf(item)))
                    .put("k", if (MediaItems.isVideo(item)) MediaItems.videoKeyOf(item) else "")
            )
        }
        store.edit().putString("queue", arr.toString()).apply()
        savePosition()
    }

    private fun savePosition() {
        if (player.mediaItemCount == 0) return
        store.edit()
            .putInt("index", player.currentMediaItemIndex)
            .putLong("pos", player.currentPosition.coerceAtLeast(0))
            .putBoolean("shuffle", player.shuffleModeEnabled)
            .putInt("repeat", player.repeatMode)
            .apply()
    }

    private fun restoreState() {
        val json = store.getString("queue", null) ?: return
        val items = runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val subs = o.optJSONArray("s")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
                MediaItems.build(
                    o.getString("id"), o.optString("t"), o.optString("a"), o.optString("al"), o.optString("aa"),
                    video = o.optBoolean("v", false), subs = subs,
                    videoKey = o.optString("k").ifBlank { null },
                )
            }
        }.getOrNull()
        if (items.isNullOrEmpty()) return
        val index = store.getInt("index", 0).coerceIn(0, items.size - 1)
        player.shuffleModeEnabled = store.getBoolean("shuffle", false)
        player.repeatMode = store.getInt("repeat", Player.REPEAT_MODE_OFF)
        player.setMediaItems(items, index, store.getLong("pos", 0L))
        player.prepare()
    }
}
