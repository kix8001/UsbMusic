package app.usbmusic.player.data

/** 読み込んだ全曲から、アーティスト・アルバム・曲の一覧を組み立てる */
class Library(val tracks: List<Track>) {
    val albums: List<Album>
    val artists: List<Artist>
    val songs: List<Track>
    private val albumMap: Map<String, Album>
    private val artistMap: Map<String, Artist>
    private val uriMap: Map<String, Track> by lazy { tracks.associateBy { it.uri } }

    private val artistNorm by lazy { artists.map { SortKey.searchNorm(it.name) } }
    private val albumNorm by lazy { albums.map { SortKey.searchNorm(it.title + " " + it.artist) } }
    private val songNorm by lazy { songs.map { SortKey.searchNorm(it.title) } }

    init {
        // アルバム内の曲順：ディスク番号 → トラック番号 → 曲名
        val trackOrder = compareBy<Track>(
            { it.discNo },
            { if (it.trackNo > 0) it.trackNo else Int.MAX_VALUE },
            { it.title },
        )

        val builtAlbums = tracks.groupBy { it.albumKey }.map { (key, list) ->
            val sorted = list.sortedWith(trackOrder)
            val tagged = sorted.firstOrNull { it.albumArtist.isNotBlank() }?.albumArtist
            val names = sorted.map { it.displayArtist }.distinct()
            val artistName = tagged ?: if (names.size == 1) names[0] else VARIOUS_ARTISTS
            Album(
                key = key,
                title = sorted.first().album.ifBlank { UNKNOWN_ALBUM },
                artist = artistName,
                year = sorted.maxOf { it.year },
                tracks = sorted,
            )
        }
        albums = SortKey.sorted(builtAlbums) { it.title }
        albumMap = albums.associateBy { it.key }

        val albumOrder = compareBy<Album>(
            { if (it.year > 0) it.year else Int.MAX_VALUE },
            { it.title },
        )
        val builtArtists = tracks.groupBy { it.groupArtist }.map { (name, list) ->
            val albs = list.map { it.albumKey }.distinct()
                .mapNotNull { albumMap[it] }
                .sortedWith(albumOrder)
            val artistTracks = albs.flatMap { a -> a.tracks.filter { it.groupArtist == name } }
            Artist(name, albs, artistTracks)
        }
        artists = SortKey.sorted(builtArtists) { it.name }
        artistMap = artists.associateBy { it.name }

        songs = SortKey.sorted(tracks) { it.title }
    }

    fun album(key: String): Album? = albumMap[key]
    fun artist(name: String): Artist? = artistMap[name]
    fun byUri(uri: String): Track? = uriMap[uri]

    fun search(query: String): SearchResult {
        val q = SortKey.searchNorm(query)
        if (q.isEmpty()) return SearchResult()
        return SearchResult(
            artists = pick(artists, artistNorm, q, 8),
            albums = pick(albums, albumNorm, q, 12),
            songs = pick(songs, songNorm, q, 80),
        )
    }

    private fun <T> pick(list: List<T>, norms: List<String>, q: String, limit: Int): List<T> {
        val prefix = ArrayList<T>()
        val contains = ArrayList<T>()
        for (i in list.indices) {
            val n = norms[i]
            if (n.startsWith(q)) prefix += list[i] else if (n.contains(q)) contains += list[i]
            if (prefix.size >= limit) return prefix
        }
        return (prefix + contains).take(limit)
    }
}
