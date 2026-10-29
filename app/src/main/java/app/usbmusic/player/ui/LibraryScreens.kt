package app.usbmusic.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.usbmusic.player.MainViewModel
import app.usbmusic.player.data.Album
import app.usbmusic.player.data.Library
import app.usbmusic.player.data.Track
import app.usbmusic.player.data.VARIOUS_ARTISTS
import app.usbmusic.player.playback.PlayerConnection

private fun countText(albums: Int, tracks: Int): String =
    if (albums > 0) "${albums}枚のアルバム・${tracks}曲" else "${tracks}曲"

private fun yearOrEmpty(year: Int): String = if (year > 0) year.toString() else ""

@Composable
fun LibraryEmpty(vm: MainViewModel, onOpenSettings: () -> Unit) {
    if (vm.scanning || !vm.loaded) {
        CenterMessage("音楽を読み込んでいます…", if (vm.scanProgress > 0) "${vm.scanProgress}曲" else null) {
            CircularProgressIndicator(color = Pal.Accent)
        }
    } else {
        CenterMessage(
            "音楽が見つかりません",
            "USBメモリを挿して少し待つか、設定で読み込み方法を変えてください。",
        ) {
            Icon(Icons.Filled.Usb, contentDescription = null, tint = Pal.Sub, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            PillButton(Icons.Filled.Settings, "設定を開く", onOpenSettings)
        }
    }
}

// ---------------- アーティスト ----------------

@Composable
fun ArtistsScreen(lib: Library, onArtist: (String) -> Unit) {
    val state = rememberLazyListState()
    IndexedList(
        list = lib.artists,
        name = { it.name },
        state = state,
        header = { ScreenTitle("アーティスト") },
    ) { _, artist ->
        ListRow(
            title = artist.name,
            subtitle = countText(artist.albums.size, artist.tracks.size),
            leading = { AlbumArt(artist.albums.firstOrNull()?.cover, 56.dp, circle = true) },
        ) { onArtist(artist.name) }
    }
}

@Composable
fun ArtistDetailScreen(
    lib: Library,
    name: String,
    conn: PlayerConnection,
    onBack: () -> Unit,
    onAlbum: (String) -> Unit,
) {
    val artist = lib.artist(name)
    Column(Modifier.fillMaxSize()) {
        BackBar("アーティスト", onBack)
        if (artist == null) {
            CenterMessage("アーティストが見つかりません")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(190.dp),
                state = rememberLazyGridState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(
                            artist.name, color = Pal.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(countText(artist.albums.size, artist.tracks.size), color = Pal.Sub, fontSize = 17.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PillButton(Icons.Filled.PlayArrow, "再生") { conn.playTracks(artist.tracks) }
                            PillButton(Icons.Filled.Shuffle, "シャッフル") { conn.playTracks(artist.tracks, shuffled = true) }
                        }
                    }
                }
                items(artist.albums.size) { i ->
                    val album = artist.albums[i]
                    val sub = if (album.artist != artist.name) album.artist else yearOrEmpty(album.year)
                    AlbumTile(album, sub) { onAlbum(album.key) }
                }
            }
        }
    }
}

// ---------------- アルバム ----------------

@Composable
fun AlbumsScreen(lib: Library, onAlbum: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(190.dp),
        state = rememberLazyGridState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.offset(x = (-20).dp)) { ScreenTitle("アルバム") }
        }
        items(lib.albums.size) { i ->
            val album = lib.albums[i]
            AlbumTile(album, album.artist) { onAlbum(album.key) }
        }
    }
}

@Composable
fun AlbumDetailScreen(
    lib: Library,
    albumKey: String,
    conn: PlayerConnection,
    onBack: () -> Unit,
    onArtist: (String) -> Unit,
) {
    val album = lib.album(albumKey)
    Column(Modifier.fillMaxSize()) {
        BackBar("戻る", onBack)
        if (album == null) {
            CenterMessage("アルバムが見つかりません")
        } else {
            AlbumDetailBody(lib, album, conn, onArtist)
        }
    }
}

@Composable
private fun AlbumDetailBody(lib: Library, album: Album, conn: PlayerConnection, onArtist: (String) -> Unit) {
    val multiDisc = remember(album) { album.tracks.map { it.discNo }.distinct().size > 1 }
    val showTrackArtist = album.artist == VARIOUS_ARTISTS ||
        album.tracks.any { it.displayArtist != album.artist }
    val artistExists = lib.artist(album.artist) != null
    val meta = listOf(
        yearOrEmpty(album.year),
        "${album.tracks.size}曲",
        fmtTime(album.durationMs),
    ).filter { it.isNotEmpty() }.joinToString("・")
    val currentId = conn.current?.mediaId

    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(
            Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(end = 24.dp, bottom = 24.dp)
        ) {
            AlbumArt(album.cover, 276.dp, corner = 10.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                album.title, color = Pal.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Text(
                album.artist,
                color = Pal.Accent,
                fontSize = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (artistExists) Modifier.clickable { onArtist(album.artist) } else Modifier,
            )
            Text(meta, color = Pal.Sub, fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(Icons.Filled.PlayArrow, "再生") { conn.playTracks(album.tracks) }
                PillButton(Icons.Filled.Shuffle, "シャッフル") { conn.playTracks(album.tracks, shuffled = true) }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) {
            album.tracks.forEachIndexed { i, t ->
                if (multiDisc && (i == 0 || album.tracks[i - 1].discNo != t.discNo)) {
                    item {
                        Text(
                            "ディスク ${t.discNo}",
                            color = Pal.Sub,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                }
                item {
                    TrackRow(
                        number = if (t.trackNo > 0) t.trackNo.toString() else "${i + 1}",
                        title = t.title,
                        subtitle = if (showTrackArtist) t.displayArtist else null,
                        durationMs = t.durationMs,
                        playing = currentId == t.uri,
                    ) { conn.playTracks(album.tracks, i) }
                }
            }
        }
    }
}

// ---------------- 曲 ----------------

@Composable
fun SongsScreen(lib: Library, conn: PlayerConnection) {
    val state = rememberLazyListState()
    val currentId = conn.current?.mediaId
    IndexedList(
        list = lib.songs,
        name = { it.title },
        state = state,
        header = {
            ScreenTitle("曲") {
                PillButton(Icons.Filled.Shuffle, "シャッフル") { conn.playTracks(lib.songs, shuffled = true) }
            }
        },
    ) { i, t ->
        ListRow(
            title = t.title,
            subtitle = "${t.displayArtist} ・ ${t.album}",
            highlight = currentId == t.uri,
            leading = { AlbumArt(t, 52.dp) },
            chevron = false,
        ) { conn.playTracks(lib.songs, i) }
    }
}

// ---------------- 検索 ----------------

@Composable
fun SearchScreen(
    lib: Library,
    conn: PlayerConnection,
    onArtist: (String) -> Unit,
    onAlbum: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val result = remember(query, lib) { lib.search(query) }
    val currentId = conn.current?.mediaId

    fun playSong(t: Track) {
        val album = lib.album(t.albumKey)
        if (album != null) conn.playTracks(album.tracks, album.tracks.indexOf(t).coerceAtLeast(0))
        else conn.playTracks(listOf(t))
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("検索")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            placeholder = { Text("アーティスト、アルバム、曲名", fontSize = 19.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Pal.Sub) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "消去", tint = Pal.Sub)
                    }
                }
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 20.sp, color = Pal.Text),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Pal.Text,
                unfocusedTextColor = Pal.Text,
                focusedContainerColor = Pal.Surface,
                unfocusedContainerColor = Pal.Surface,
                focusedBorderColor = Pal.Accent,
                unfocusedBorderColor = Pal.Surface2,
                cursorColor = Pal.Accent,
                focusedPlaceholderColor = Pal.Sub,
                unfocusedPlaceholderColor = Pal.Sub,
            ),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (result.artists.isNotEmpty()) {
                item { SectionHeader("アーティスト") }
                items(result.artists) { a ->
                    ListRow(
                        title = a.name,
                        subtitle = countText(a.albums.size, a.tracks.size),
                        leading = { AlbumArt(a.albums.firstOrNull()?.cover, 52.dp, circle = true) },
                    ) { onArtist(a.name) }
                }
            }
            if (result.albums.isNotEmpty()) {
                item { SectionHeader("アルバム") }
                items(result.albums) { al ->
                    ListRow(
                        title = al.title,
                        subtitle = al.artist,
                        leading = { AlbumArt(al.cover, 52.dp) },
                    ) { onAlbum(al.key) }
                }
            }
            if (result.songs.isNotEmpty()) {
                item { SectionHeader("曲") }
                items(result.songs) { t ->
                    ListRow(
                        title = t.title,
                        subtitle = "${t.displayArtist} ・ ${t.album}",
                        highlight = currentId == t.uri,
                        leading = { AlbumArt(t, 52.dp) },
                        chevron = false,
                    ) { playSong(t) }
                }
            }
            if (query.isNotBlank() && result.isEmpty) {
                item {
                    Text(
                        "「$query」は見つかりませんでした",
                        color = Pal.Sub,
                        fontSize = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
        }
    }
}
