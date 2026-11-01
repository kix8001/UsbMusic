package app.usbmusic.player.ui

import android.app.Activity
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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

// ---------------- サムネイル ----------------

private object VideoThumbs {
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val gate = Semaphore(2)

    fun peek(uri: String): Bitmap? = memory.get(uri)

    suspend fun load(context: Context, uri: String): Bitmap? {
        memory.get(uri)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                memory.get(uri) ?: run {
                    val file = File(context.cacheDir, "vthumb/${md5(uri)}.jpg")
                    val bmp = if (file.exists()) {
                        android.graphics.BitmapFactory.decodeFile(file.path)
                    } else {
                        extract(context, uri)?.also { b ->
                            runCatching {
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                            }
                        }
                    }
                    bmp?.also { memory.put(uri, it) }
                }
            }
        }
    }

    private fun extract(context: Context, uri: String): Bitmap? {
        val u = Uri.parse(uri)
        if (u.authority == "media") {
            runCatching { return context.contentResolver.loadThumbnail(u, Size(384, 216), null) }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, u)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = r.getScaledFrameAtTime(
                (dur * 1000 / 10).coerceAtLeast(1_000_000L),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 216,
            )
            frame
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
    val bmp by produceState<ImageBitmap?>(VideoThumbs.peek(video.uri)?.asImageBitmap(), video.uri) {
        if (value == null) value = VideoThumbs.load(context, video.uri)?.asImageBitmap()
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

@Composable
fun VideosScreen(vm: MainViewModel, conn: PlayerConnection, onPlayed: () -> Unit) {
    val context = LocalContext.current
    val videos = vm.videos
    LaunchedEffect(Unit) { vm.refreshVideoPositions() }
    if (videos.isEmpty()) {
        if (vm.scanning || !vm.loaded) {
            CenterMessage("読み込んでいます…")
        } else {
            CenterMessage("動画が見つかりません", "MP4・MKVなどの動画を音楽と同じUSBメモリに入れてください。") {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = Pal.Sub, modifier = Modifier.size(56.dp))
            }
        }
        return
    }
    val state = rememberLazyListState()
    val currentId = conn.current?.mediaId
    IndexedList(
        list = videos,
        name = { it.title },
        state = state,
        header = { ScreenTitle("動画") },
    ) { i, v ->
        val rec = vm.videoPositions[v.uri]
        val dur = v.durationMs.takeIf { it > 0 } ?: rec?.second ?: 0L
        val progress = if (rec != null && dur > 0) rec.first.toFloat() / dur else null
        val sub = buildList {
            if (dur > 0) add(fmtTime(dur))
            if (rec != null) add("続きから ${fmtTime(rec.first)}")
            if (v.subtitles.isNotEmpty()) add("字幕ファイルあり")
            if (v.folderName.isNotBlank()) add(v.folderName)
        }.joinToString(" ・ ")
        Column(Modifier.fillMaxWidth().clickable {
            conn.playVideos(context, videos, i)
            onPlayed()
        }) {
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
fun FullscreenVideo(conn: PlayerConnection, onExit: () -> Unit) {
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
        VideoSurface(conn, Modifier.fillMaxSize(), large = true)
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
