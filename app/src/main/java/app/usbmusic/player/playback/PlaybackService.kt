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
import org.json.JSONArray
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
        // 音のデータをビジュアライザー用に分岐させる
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(Spectrum)))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
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
                savePosition()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) errorStreak = 0 else savePosition()
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
        handler.postDelayed(periodicSave, 10_000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        savePosition()
        handler.removeCallbacks(periodicSave)
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
            val resolved = mediaItems.map { it.buildUpon().setUri(Uri.parse(it.mediaId)).build() }
            return Futures.immediateFuture(resolved.toMutableList())
        }
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
                MediaItems.build(o.getString("id"), o.optString("t"), o.optString("a"), o.optString("al"), o.optString("aa"))
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
