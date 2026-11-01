package app.usbmusic.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.usbmusic.player.MainViewModel
import app.usbmusic.player.data.Library
import app.usbmusic.player.data.Track
import app.usbmusic.player.playback.PlayerConnection

sealed interface Screen {
    val key: String

    data object NowPlaying : Screen { override val key = "now" }
    data object Artists : Screen { override val key = "artists" }
    data object Albums : Screen { override val key = "albums" }
    data object Songs : Screen { override val key = "songs" }
    data object Search : Screen { override val key = "search" }
    data object Settings : Screen { override val key = "settings" }
    data object Videos : Screen { override val key = "videos" }
    data class VideoArtist(val name: String) : Screen { override val key: String get() = "vartist:$name" }
    data class ArtistDetail(val name: String) : Screen { override val key: String get() = "artist:$name" }
    data class AlbumDetail(val albumKey: String) : Screen { override val key: String get() = "album:$albumKey" }
}

enum class NavTab(val label: String, val icon: ImageVector, val root: Screen) {
    NOW("再生中", Icons.Filled.GraphicEq, Screen.NowPlaying),
    ARTISTS("アーティスト", Icons.Filled.Person, Screen.Artists),
    ALBUMS("アルバム", Icons.Filled.Album, Screen.Albums),
    A2Z("A2Z", Icons.Filled.SortByAlpha, Screen.Artists), // アーティストの頭文字一覧をすぐ開く
    SONGS("曲", Icons.Filled.MusicNote, Screen.Songs),
    VIDEOS("動画", Icons.Filled.Movie, Screen.Videos),
    SEARCH("検索", Icons.Filled.Search, Screen.Search),
    SETTINGS("設定", Icons.Filled.Settings, Screen.Settings),
}

@Composable
fun App(
    vm: MainViewModel,
    conn: PlayerConnection,
    permitted: Boolean,
    onRequestPermission: () -> Unit,
    onPickFolder: () -> Unit,
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Accent,
            background = Pal.Bg,
            onBackground = Pal.Text,
            surface = Pal.Surface,
            onSurface = Pal.Text,
        )
    ) {
        Surface(color = Pal.Bg, contentColor = Pal.Text, modifier = Modifier.fillMaxSize()) {
            if (!permitted) {
                CenterMessage(
                    "音楽ファイルへのアクセスを許可してください",
                    "USBメモリの曲を表示・再生するために必要です。",
                ) {
                    PillButton(Icons.Filled.LibraryMusic, "許可する", onRequestPermission)
                }
            } else {
                MainLayout(vm, conn, onPickFolder)
            }
        }
    }
}

@Composable
private fun MainLayout(vm: MainViewModel, conn: PlayerConnection, onPickFolder: () -> Unit) {
    val lib = vm.library
    var tab by rememberSaveable { mutableStateOf(NavTab.ARTISTS) }
    val stacks = remember { mutableStateMapOf<NavTab, List<Screen>>() }
    val stack = stacks[tab] ?: listOf(tab.root)
    val screen = stack.last()
    val holder = rememberSaveableStateHolder()

    val push: (Screen) -> Unit = { s -> stacks[tab] = (stacks[tab] ?: listOf(tab.root)) + s }
    val pop: () -> Unit = {
        val current = stacks[tab] ?: listOf(tab.root)
        if (current.size > 1) stacks[tab] = current.dropLast(1)
    }
    val open: (NavTab, Screen?) -> Unit = { t, s ->
        tab = t
        stacks[t] = if (s == null) listOf(t.root) else listOf(t.root, s)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    val selectTab: (NavTab) -> Unit = { t ->
        if (t == NavTab.A2Z) {
            tab = NavTab.ARTISTS
            stacks[NavTab.ARTISTS] = listOf(NavTab.ARTISTS.root)
            vm.requestJump()
        } else if (t == tab) stacks[t] = listOf(t.root) else tab = t
    }
    val content: @Composable ColumnScope.() -> Unit = {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            holder.SaveableStateProvider(screen.key) {
                ScreenContent(
                    screen = screen,
                    vm = vm,
                    lib = lib,
                    conn = conn,
                    push = push,
                    pop = pop,
                    open = open,
                    onPickFolder = onPickFolder,
                )
            }
        }
        if (conn.current != null && screen != Screen.NowPlaying) {
            MiniPlayer(conn, lib) { open(NavTab.NOW, null) }
        }
    }

    // 動画のフル画面は、動画以外になったら自動で閉じる
    LaunchedEffect(conn.isVideo) { if (!conn.isVideo) vm.fullscreen = false }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (vm.fullscreen && conn.isVideo) {
            FullscreenVideo(conn) { vm.fullscreen = false }
        } else if (maxWidth < 600.dp) {
            // 縦長（分割表示）：メニューを下に置き、画面の横幅をすべて使う
            Column(Modifier.fillMaxSize()) {
                content()
                BottomBar(tab, selectTab)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Sidebar(tab, selectTab)
                Column(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }
}

@Composable
private fun BottomBar(current: NavTab, onSelect: (NavTab) -> Unit) {
    Row(Modifier.fillMaxWidth().height(68.dp).background(Pal.Surface)) {
        NavTab.entries.forEach { t ->
            val selected = t == current
            val tint = if (selected) Pal.Accent else Pal.Sub
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(28.dp))
                Text(t.label, color = tint, fontSize = 10.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    vm: MainViewModel,
    lib: Library,
    conn: PlayerConnection,
    push: (Screen) -> Unit,
    pop: () -> Unit,
    open: (NavTab, Screen?) -> Unit,
    onPickFolder: () -> Unit,
) {
    val needsLibrary = screen == Screen.Artists || screen == Screen.Albums ||
        screen == Screen.Songs || screen == Screen.Search
    if (needsLibrary && lib.tracks.isEmpty()) {
        LibraryEmpty(vm) { open(NavTab.SETTINGS, null) }
        return
    }
    when (screen) {
        Screen.NowPlaying -> NowPlayingScreen(
            conn = conn,
            lib = lib,
            visStyle = vm.visualizer,
            onVisTap = { vm.nextVisualizer() },
            fullscreen = vm.fullscreen,
            onFullscreen = { vm.fullscreen = true },
            onArtist = { open(NavTab.ARTISTS, Screen.ArtistDetail(it)) },
            onAlbum = { open(NavTab.ALBUMS, Screen.AlbumDetail(it)) },
        )
        Screen.Artists -> ArtistsScreen(
            lib = lib,
            jumpRequested = vm.jumpRequested,
            onJumpHandled = { vm.jumpHandled() },
            onVideos = { open(NavTab.VIDEOS, null) },
        ) { push(Screen.ArtistDetail(it)) }
        Screen.Videos -> VideosScreen(vm, conn, onArtist = { push(Screen.VideoArtist(it)) }) { open(NavTab.NOW, null) }
        is Screen.VideoArtist -> VideoArtistScreen(vm, conn, screen.name, onBack = pop) { open(NavTab.NOW, null) }
        Screen.Albums -> AlbumsScreen(lib) { push(Screen.AlbumDetail(it)) }
        Screen.Songs -> SongsScreen(lib, conn)
        Screen.Search -> SearchScreen(
            lib = lib,
            conn = conn,
            onArtist = { push(Screen.ArtistDetail(it)) },
            onAlbum = { push(Screen.AlbumDetail(it)) },
        )
        Screen.Settings -> SettingsScreen(vm, onPickFolder)
        is Screen.ArtistDetail -> ArtistDetailScreen(
            lib = lib,
            name = screen.name,
            conn = conn,
            onBack = pop,
            onAlbum = { push(Screen.AlbumDetail(it)) },
        )
        is Screen.AlbumDetail -> AlbumDetailScreen(
            lib = lib,
            albumKey = screen.albumKey,
            conn = conn,
            onBack = pop,
            onArtist = { push(Screen.ArtistDetail(it)) },
        )
    }
}

@Composable
private fun Sidebar(current: NavTab, onSelect: (NavTab) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(112.dp).background(Pal.Surface).padding(vertical = 8.dp),
    ) {
        NavTab.entries.forEach { t ->
            val selected = t == current
            val tint = if (selected) Pal.Accent else Pal.Sub
            Column(
                Modifier.weight(1f).fillMaxWidth().clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    t.label, color = tint, fontSize = 13.sp, maxLines = 1, softWrap = false,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(conn: PlayerConnection, lib: Library, onOpen: () -> Unit) {
    val item = conn.current ?: return
    val track = remember(item.mediaId, lib) { lib.byUri(item.mediaId) ?: Track.fromItem(item) }
    Row(
        Modifier.fillMaxWidth().height(84.dp).background(Pal.Surface)
            .clickable(onClick = onOpen).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(track, 60.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title.ifBlank { "—" }, color = Pal.Text, fontSize = 20.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.displayArtist, color = Pal.Sub, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { conn.toggle() }, modifier = Modifier.size(72.dp)) {
            Icon(
                if (conn.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp),
            )
        }
        IconButton(onClick = { conn.next() }, modifier = Modifier.size(72.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
    }
}
