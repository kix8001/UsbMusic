package app.usbmusic.player.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import app.usbmusic.player.playback.fadeLevel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.usbmusic.player.MainViewModel
import app.usbmusic.player.data.SortKey
import app.usbmusic.player.data.TagReader
import app.usbmusic.player.data.Video
import app.usbmusic.player.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

// ---------------- 動画の表示面 ----------------

/** 動画を映す面。字幕もここに表示される */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(conn: PlayerConnection, modifier: Modifier = Modifier, large: Boolean = false) {
    val controller = conn.controller
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                subtitleView?.setApplyEmbeddedStyles(true)
                subtitleView?.setFractionalTextSize(if (large) 0.06f else 0.075f)
            }
        },
        update = { if (it.player !== controller) it.player = controller },
        onRelease = { it.player = null },
        modifier = modifier.background(Color.Black),
    )
}

/**
 * 動画の表示面に、切り替え時のフェードと、最初の10秒間のタイトル・アーティスト表示を重ねたもの
 */
@Composable
fun VideoLayer(
    conn: PlayerConnection,
    fade: Boolean,
    titles: Boolean,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Box(modifier) {
        VideoSurface(conn, Modifier.fillMaxSize(), large)
        val id = conn.current?.mediaId
        val changedAt = conn.itemChangedAt
        if (fade) {
            var alpha by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(id, changedAt) {
                while (true) {
                    val level = if (conn.isPlaying) {
                        fadeLevel(SystemClock.elapsedRealtime() - changedAt, conn.position, conn.duration)
                    } else 1f
                    alpha = 1f - level
                    delay(33)
                }
            }
            if (alpha > 0.01f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
        }
        if (titles && id != null) {
            var show by remember { mutableStateOf(false) }
            LaunchedEffect(id, changedAt) {
                val left = 10_000L - (SystemClock.elapsedRealtime() - changedAt)
                if (left > 0) {
                    delay(600) // フェードインの後に出す
                    show = true
                    delay((left - 600).coerceAtLeast(0))
                }
                show = false
            }
            val meta = conn.current?.mediaMetadata
            AnimatedVisibility(
                visible = show,
                enter = fadeIn(tween(900)),
                exit = fadeOut(tween(1400)),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                        .padding(horizontal = if (large) 48.dp else 16.dp, vertical = if (large) 40.dp else 12.dp)
                ) {
                    Text(
                        meta?.title?.toString().orEmpty(),
                        color = Color.White, fontSize = if (large) 36.sp else 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val artist = meta?.artist?.toString().orEmpty()
                    if (artist.isNotBlank()) {
                        Text(artist, color = Color(0xFFFF8FA0), fontSize = if (large) 24.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val album = meta?.albumTitle?.toString().orEmpty()
                    if (album.isNotBlank()) {
                        Text(album, color = Color(0xCCFFFFFF), fontSize = if (large) 18.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---------------- サムネイル ----------------

private object VideoThumbs {
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val gate = Semaphore(2)

    /** USBを挿し直しても同じになるキー（ファイルの場所＋更新日時） */
    private fun keyOf(v: Video) = v.key + "@" + v.lastModified

    fun peek(v: Video): Bitmap? = memory.get(keyOf(v))

    suspend fun load(context: Context, v: Video): Bitmap? {
        val key = keyOf(v)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                memory.get(key) ?: run {
                    val file = File(context.cacheDir, "vthumb/${md5(key)}.jpg")
                    val bmp = if (file.exists()) {
                        android.graphics.BitmapFactory.decodeFile(file.path)
                    } else {
                        extract(context, v.uri)?.also { b ->
                            runCatching {
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                            }
                        }
                    }
                    bmp?.also { memory.put(key, it) }
                }
            }
        }
    }

    private fun extract(context: Context, uri: String): Bitmap? {
        val u = Uri.parse(uri)
        // MP4/M4V にカバー画像（ポスター）が埋め込まれていればそれを使う
        TagReader.mp4Cover(context, u)?.let { bytes ->
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 400 && opts.outHeight / (sample * 2) >= 400) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { return it }
        }
        if (u.authority == "media") {
            runCatching { return context.contentResolver.loadThumbnail(u, Size(384, 216), null) }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, u)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.getScaledFrameAtTime(
                (dur * 1000 / 10).coerceAtLeast(1_000_000L),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 216,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Composable
fun VideoThumb(video: Video, modifier: Modifier = Modifier, progress: Float? = null) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(VideoThumbs.peek(video)?.asImageBitmap(), video.key, video.lastModified) {
        if (value == null) value = VideoThumbs.load(context, video)?.asImageBitmap()
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Pal.Surface2), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.Movie, contentDescription = null, tint = Pal.Sub, modifier = Modifier.size(32.dp))
        }
        if (progress != null && progress > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Pal.Accent))
            }
        }
    }
}

// ---------------- 動画の一覧 ----------------

private const val NO_ARTIST = "アーティスト未設定"

/** 動画のアーティスト（タグが無いものは「アーティスト未設定」にまとめる） */
class VideoArtist(val name: String, val sortName: String, val videos: List<Video>)

fun videoArtists(videos: List<Video>): List<VideoArtist> {
    val groups = videos.groupBy { it.artist.ifBlank { NO_ARTIST } }.map { (name, list) ->
        VideoArtist(
            name,
            if (name == NO_ARTIST) "\uFFFF" else list.firstOrNull { it.artistSort.isNotBlank() }?.artistSort ?: name,
            list.sortedWith(compareBy<Video>({ it.album }, { it.sortTitle })),
        )
    }
    val tagged = SortKey.sorted(groups.filter { it.name != NO_ARTIST }) { it.sortName }
    return tagged + groups.filter { it.name == NO_ARTIST }
}

@Composable
private fun VideoRow(vm: MainViewModel, v: Video, currentId: String?, showArtist: Boolean, onClick: () -> Unit) {
    val rec = vm.videoPositions[v.key]
    val dur = v.durationMs.takeIf { it > 0 } ?: rec?.second ?: 0L
    val progress = if (rec != null && dur > 0) rec.first.toFloat() / dur else null
    val sub = buildList {
        if (showArtist && v.artist.isNotBlank()) add(v.artist)
        if (v.album.isNotBlank()) add(v.album)
        if (dur > 0) add(fmtTime(dur))
        if (rec != null) add("続きから ${fmtTime(rec.first)}")
        if (v.subtitles.isNotEmpty()) add("字幕ファイルあり")
        if (v.artist.isBlank() && v.album.isBlank() && v.folderName.isNotBlank()) add(v.folderName)
    }.joinToString(" ・ ")
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VideoThumb(v, Modifier.size(width = 128.dp, height = 72.dp), progress)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    v.title, color = if (currentId == v.uri) Pal.Accent else Pal.Text, fontSize = 19.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (sub.isNotBlank()) Text(sub, color = Pal.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(Modifier.padding(start = 164.dp), thickness = 0.5.dp, color = Pal.Divider)
    }
}

@Composable
fun VideosScreen(vm: MainViewModel, conn: PlayerConnection, onArtist: (String) -> Unit, onPlayed: () -> Unit) {
    val context = LocalContext.current
    val videos = vm.videos
    LaunchedEffect(Unit) { vm.refreshVideoPositions() }
    if (videos.isEmpty()) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) vm.rescan()
        }
        if (vm.scanning || !vm.loaded) {
            CenterMessage("読み込んでいます…")
        } else {
            CenterMessage(
                "動画が見つかりません",
                if (!granted) "動画へのアクセスを許可してください。"
                else "MP4・MKVなどを音楽と同じUSBメモリに入れて、「USBを登録し直す」を押してください。",
            ) {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = Pal.Sub, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!granted) {
                        PillButton(Icons.Filled.Movie, "動画へのアクセスを許可") {
                            launcher.launch(Manifest.permission.READ_MEDIA_VIDEO)
                        }
                    } else {
                        PillButton(Icons.Filled.Refresh, "再読み込み") { vm.rescan() }
                        PillButton(Icons.Filled.Usb, "USBを登録し直す") { vm.requestMediaScan() }
                    }
                }
                if (vm.mediaScanRunning) {
                    Spacer(Modifier.height(12.dp))
                    Text("Androidが登録しています…（数分かかることがあります）", color = Pal.Text, fontSize = 16.sp)
                }
                if (vm.videoReport.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(vm.videoReport, color = Pal.Sub, fontSize = 13.sp)
                }
            }
        }
        return
    }
    var byArtist by rememberSaveable { mutableStateOf(false) }
    val currentId = conn.current?.mediaId
    val header: @Composable () -> Unit = {
        ScreenTitle("動画") {
            IconButton(onClick = {
                conn.playVideos(context, videos, 0, shuffled = true)
                onPlayed()
            }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.Shuffle, contentDescription = "シャッフル", tint = Pal.Accent, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Pal.Surface2)) {
                listOf(false to "タイトル", true to "アーティスト").forEach { (mode, label) ->
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (byArtist == mode) Pal.Accent else Color.Transparent)
                            .clickable { byArtist = mode }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text(label, color = if (byArtist == mode) Color.White else Pal.Sub, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    if (byArtist) {
        val artists = remember(videos) { videoArtists(videos) }
        IndexedList(
            list = artists,
            name = { it.sortName },
            state = rememberLazyListState(),
            header = header,
        ) { _, a ->
            Column(Modifier.fillMaxWidth().clickable { onArtist(a.name) }) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VideoThumb(a.videos.first(), Modifier.size(width = 96.dp, height = 54.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, color = Pal.Text, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${a.videos.size}本", color = Pal.Sub, fontSize = 15.sp)
                    }
                    Icon(androidx.compose.material.icons.Icons.Filled.ChevronRight, contentDescription = null, tint = Pal.Sub, modifier = Modifier.size(28.dp))
                }
                HorizontalDivider(Modifier.padding(start = 132.dp), thickness = 0.5.dp, color = Pal.Divider)
            }
        }
    } else {
        IndexedList(
            list = videos,
            name = { it.sortTitle },
            state = rememberLazyListState(),
            header = header,
        ) { i, v ->
            VideoRow(vm, v, currentId, showArtist = true) {
                conn.playVideos(context, videos, i)
                onPlayed()
            }
        }
    }
}

/** アーティストごとの動画一覧 */
@Composable
fun VideoArtistScreen(vm: MainViewModel, conn: PlayerConnection, name: String, onBack: () -> Unit, onPlayed: () -> Unit) {
    val context = LocalContext.current
    val artist = remember(vm.videos, name) { videoArtists(vm.videos).firstOrNull { it.name == name } }
    LaunchedEffect(Unit) { vm.refreshVideoPositions() }
    val currentId = conn.current?.mediaId
    Column(Modifier.fillMaxSize()) {
        BackBar("動画", onBack)
        if (artist == null) {
            CenterMessage("見つかりません")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                    Text(artist.name, color = Pal.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${artist.videos.size}本", color = Pal.Sub, fontSize = 17.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(Icons.Filled.PlayArrow, "最初から順に再生") {
                            conn.playVideos(context, artist.videos, 0)
                            onPlayed()
                        }
                        PillButton(Icons.Filled.Shuffle, "シャッフル") {
                            conn.playVideos(context, artist.videos, 0, shuffled = true)
                            onPlayed()
                        }
                    }
                }
            }
            artist.videos.forEachIndexed { i, v ->
                // 作品（アルバム・シリーズ）が変わるところに見出し
                if (v.album.isNotBlank() && (i == 0 || artist.videos[i - 1].album != v.album)) {
                    item { SectionHeader(v.album) }
                }
                item {
                    VideoRow(vm, v, currentId, showArtist = false) {
                        conn.playVideos(context, artist.videos, i)
                        onPlayed()
                    }
                }
            }
        }
    }
}

// ---------------- 音声・字幕の選択 ----------------

@Composable
fun TrackPicker(conn: PlayerConnection, type: Int, onClose: () -> Unit) {
    val options = remember(conn.tracks) { conn.options(type) }
    val isText = type == C.TRACK_TYPE_TEXT
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(min = 320.dp, max = 560.dp).clip(RoundedCornerShape(16.dp))
                .background(Pal.Surface).padding(vertical = 12.dp)
        ) {
            Text(
                if (isText) "字幕" else "音声",
                color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (isText) {
                    item {
                        PickerRow("オフ", !conn.subtitlesOn) {
                            conn.subtitlesOff()
                            onClose()
                        }
                    }
                }
                items(options) { o ->
                    PickerRow(o.label, o.selected) {
                        conn.select(type, o)
                        onClose()
                    }
                }
                if (options.isEmpty()) {
                    item {
                        Text(
                            if (isText) "この動画には字幕がありません" else "音声トラックがありません",
                            color = Pal.Sub, fontSize = 17.sp, modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) Pal.Accent else Pal.Text, fontSize = 19.sp, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = Pal.Accent, fontSize = 22.sp)
    }
}

/** 再生中画面に出す「音声」「字幕」「全画面」ボタン */
@Composable
fun VideoButtons(conn: PlayerConnection, onFullscreen: () -> Unit) {
    var picker by remember { mutableStateOf<Int?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton(Icons.Filled.Audiotrack, "音声") { picker = C.TRACK_TYPE_AUDIO }
        PillButton(Icons.Filled.Subtitles, if (conn.subtitlesOn) "字幕 オン" else "字幕 オフ") { picker = C.TRACK_TYPE_TEXT }
        PillButton(Icons.Filled.Fullscreen, "全画面", onFullscreen)
    }
    picker?.let { TrackPicker(conn, it) { picker = null } }
}

// ---------------- フル画面 ----------------

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun FullscreenVideo(conn: PlayerConnection, fade: Boolean = true, titles: Boolean = true, onExit: () -> Unit) {
    val view = LocalView.current
    // ステータスバー等を隠して、画面全体で再生
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(onBack = onExit)

    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var picker by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(lastTouch, controlsVisible, conn.isPlaying, picker) {
        if (controlsVisible && conn.isPlaying && picker == null) {
            delay(3500)
            controlsVisible = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            controlsVisible = !controlsVisible
            lastTouch = System.currentTimeMillis()
        }
    ) {
        VideoLayer(conn, fade, titles, Modifier.fillMaxSize(), large = true)
        if (controlsVisible) {
            var position by remember { mutableLongStateOf(conn.position) }
            var dragging by remember { mutableStateOf<Float?>(null) }
            LaunchedEffect(Unit) {
                while (true) {
                    position = conn.position
                    delay(500)
                }
            }
            val duration = conn.duration
            // 上：タイトルと閉じる
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "閉じる", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Text(
                    conn.current?.mediaMetadata?.title?.toString().orEmpty(),
                    color = Color.White, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            // 下：シークと操作
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtTime(dragging?.let { (it * duration).toLong() } ?: position), color = Color.White, fontSize = 15.sp)
                    Slider(
                        value = fraction,
                        onValueChange = { dragging = it; lastTouch = System.currentTimeMillis() },
                        onValueChangeFinished = {
                            dragging?.let { conn.seekTo((it * duration).toLong()) }
                            dragging = null
                        },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Pal.Accent, inactiveTrackColor = Color(0x66FFFFFF)),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text(fmtTime(duration), color = Color.White, fontSize = 15.sp)
                }
                VolumeSlider(Modifier.fillMaxWidth(0.6f).align(Alignment.CenterHorizontally), light = true)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FsButton(Icons.Filled.Audiotrack, "音声") { picker = C.TRACK_TYPE_AUDIO }
                    FsButton(Icons.Filled.SkipPrevious, null) { conn.seekTo((conn.position - 10_000).coerceAtLeast(0)); lastTouch = System.currentTimeMillis() }
                    FsButton(if (conn.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, big = true) { conn.toggle(); lastTouch = System.currentTimeMillis() }
                    FsButton(Icons.Filled.SkipNext, null) { conn.seekTo(conn.position + 30_000); lastTouch = System.currentTimeMillis() }
                    FsButton(Icons.Filled.Subtitles, "字幕") { picker = C.TRACK_TYPE_TEXT }
                    FsButton(Icons.Filled.FullscreenExit, "戻る", onClick = onExit)
                }
                Text(
                    "◀◀ 10秒戻る　／　30秒進む ▶▶",
                    color = Color(0x99FFFFFF), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
    picker?.let { TrackPicker(conn, it) { picker = null; lastTouch = System.currentTimeMillis() } }
}

@Composable
private fun FsButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, big: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(if (big) 64.dp else 40.dp))
        if (label != null) Text(label, color = Color.White, fontSize = 13.sp)
    }
}
