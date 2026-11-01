package app.usbmusic.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import app.usbmusic.player.data.Track
import app.usbmusic.player.data.Video

object MediaItems {
    private const val KEY_SUBS = "subs"
    private const val KEY_VKEY = "vkey"

    fun from(t: Track): MediaItem = build(t.uri, t.title, t.displayArtist, t.album, t.albumArtist, videoKey = t.pathKey.ifBlank { null })

    fun from(v: Video): MediaItem = build(
        v.uri, v.title, v.artist.ifBlank { v.folderName.ifBlank { "動画" } }, v.album.ifBlank { null }, null,
        video = true, subs = v.subtitles, videoKey = v.key,
    )

    fun build(
        id: String,
        title: String?,
        artist: String?,
        album: String?,
        albumArtist: String?,
        video: Boolean = false,
        subs: List<String> = emptyList(),
        videoKey: String? = null,
    ): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtist?.ifBlank { null })
            .setIsPlayable(true)
            .setMediaType(if (video) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
        if (subs.isNotEmpty() || videoKey != null) meta.setExtras(
            Bundle().apply {
                if (subs.isNotEmpty()) putStringArrayList(KEY_SUBS, ArrayList(subs))
                if (videoKey != null) putString(KEY_VKEY, videoKey)
            }
        )
        return withLocal(MediaItem.Builder().setMediaId(id).setMediaMetadata(meta.build()).build())
    }

    fun isVideo(item: MediaItem?): Boolean = item?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO

    /** 挿し直しても変わらないキー（動画の続きから再生・曲ごとの音量に使う） */
    fun keyOf(item: MediaItem): String = videoKeyOf(item)

    /** 動画の「続きから再生」用のキー */
    fun videoKeyOf(item: MediaItem): String = item.mediaMetadata.extras?.getString(KEY_VKEY) ?: item.mediaId

    fun subtitlesOf(item: MediaItem): List<String> =
        item.mediaMetadata.extras?.getStringArrayList(KEY_SUBS).orEmpty()

    /** 再生に必要な情報（ファイルの場所・外付け字幕）を付け直す。画面→サービスの受け渡しで消えるため */
    fun withLocal(item: MediaItem): MediaItem {
        val subs = subtitlesOf(item).map { uri ->
            val name = Uri.decode(uri).substringAfterLast('/').substringAfterLast(':')
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = when (ext) {
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
                .setMimeType(mime)
                .setLabel(name)
                .build()
        }
        return item.buildUpon()
            .setUri(Uri.parse(item.mediaId))
            .setSubtitleConfigurations(subs)
            .build()
    }
}
