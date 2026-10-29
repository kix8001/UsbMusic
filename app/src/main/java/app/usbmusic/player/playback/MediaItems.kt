package app.usbmusic.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.usbmusic.player.data.Track

object MediaItems {
    fun from(t: Track): MediaItem = build(t.uri, t.title, t.displayArtist, t.album, t.albumArtist)

    fun build(id: String, title: String?, artist: String?, album: String?, albumArtist: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.parse(id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setAlbumArtist(albumArtist?.ifBlank { null })
                    .setIsPlayable(true)
                    .build()
            )
            .build()
}
