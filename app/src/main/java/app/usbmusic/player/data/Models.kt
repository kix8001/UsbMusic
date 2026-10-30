package app.usbmusic.player.data

import androidx.media3.common.MediaItem

const val UNKNOWN_ARTIST = "不明なアーティスト"
const val UNKNOWN_ALBUM = "不明なアルバム"
const val VARIOUS_ARTISTS = "Various Artists"

private val DISC_DIR = Regex("""^(cd|disc|disk|ディスク)[\s_\-]*(\d+)$""", RegexOption.IGNORE_CASE)

/** "CD2" のようなフォルダ名からディスク番号を推定（タグにディスク番号が無い時用） */
fun discFromFolder(folder: String): Int =
    DISC_DIR.matchEntire(folder.trimEnd('/').substringAfterLast('/'))?.groupValues?.get(2)?.toIntOrNull() ?: 0

/** "Album/CD1" のようなディスク別フォルダは親フォルダを同じアルバムとして扱う */
fun albumFolder(folder: String): String {
    val t = folder.trimEnd('/')
    val last = t.substringAfterLast('/')
    return if (DISC_DIR.matches(last)) t.substringBeforeLast('/', "") else t
}

/** 1曲分の情報（IDタグから読み取った値） */
data class Track(
    val uri: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val trackNo: Int,
    val discNo: Int,
    val year: Int,
    val durationMs: Long,
    val folder: String,
    val cover: String? = null,
    val fromMediaStore: Boolean = true,
    val lastModified: Long = 0L,
    val fileName: String = "",
    /** 並べ替え用の読み（IDタグの「並べ替え用」欄） */
    val titleSort: String = "",
    val artistSort: String = "",
    val albumArtistSort: String = "",
    val albumSort: String = "",
    /** IDタグを自前で読み終えたか */
    val tagsRead: Boolean = false,
) {
    val displayArtist: String get() = artist.ifBlank { UNKNOWN_ARTIST }

    /** アーティスト一覧でまとめる名前（アルバムアーティスト優先） */
    val groupArtist: String get() = albumArtist.ifBlank { displayArtist }

    /** アーティストの並べ替えに使う読み（無ければ空） */
    val groupArtistSort: String
        get() = when {
            albumArtist.isBlank() -> artistSort
            albumArtistSort.isNotBlank() -> albumArtistSort
            albumArtist == artist -> artistSort
            else -> ""
        }

    val sortTitle: String get() = titleSort.ifBlank { title }

    /** 同じアルバムかどうかを判定するキー */
    val albumKey: String =
        if (albumArtist.isNotBlank()) "aa:${albumArtist.lowercase()}|${album.lowercase()}"
        else "f:${album.lowercase()}|${albumFolder(folder)}"

    companion object {
        fun fromItem(item: MediaItem): Track {
            val m = item.mediaMetadata
            return Track(
                uri = item.mediaId,
                title = m.title?.toString().orEmpty(),
                artist = m.artist?.toString().orEmpty(),
                albumArtist = m.albumArtist?.toString().orEmpty(),
                album = m.albumTitle?.toString().orEmpty(),
                trackNo = 0, discNo = 1, year = 0, durationMs = 0L,
                folder = item.mediaId,
            )
        }
    }
}

class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int,
    val tracks: List<Track>,
    val sortTitle: String = title,
) {
    val cover: Track get() = tracks.first()
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

class Artist(
    val name: String,
    val sortName: String,
    val albums: List<Album>,
    val tracks: List<Track>,
)

class SearchResult(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val songs: List<Track> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}
