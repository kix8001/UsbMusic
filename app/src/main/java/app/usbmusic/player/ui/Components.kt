package app.usbmusic.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.usbmusic.player.data.Album
import app.usbmusic.player.data.ArtLoader
import app.usbmusic.player.data.SortKey
import app.usbmusic.player.data.Track
import kotlinx.coroutines.launch

/** CarPlay風の配色 */
object Pal {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF1C1C1E)
    val Surface2 = Color(0xFF2C2C2E)
    val Divider = Color(0xFF38383A)
    val Text = Color(0xFFFFFFFF)
    val Sub = Color(0xFF8E8E93)
    val Accent = Color(0xFFFA2D48)
}

fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
fun AlbumArt(
    track: Track?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 6.dp,
    circle: Boolean = false,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val shape = if (circle) CircleShape else RoundedCornerShape(corner)
    val bitmap by produceState<ImageBitmap?>(
        track?.let { ArtLoader.peek(it, px) }?.asImageBitmap(),
        track?.albumKey,
        px,
    ) {
        value = track?.let { ArtLoader.peek(it, px) }?.asImageBitmap()
        if (value == null && track != null) {
            value = ArtLoader.load(context, track, px)?.asImageBitmap()
        }
    }
    Box(
        modifier.size(size).clip(shape).background(Pal.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Pal.Sub, modifier = Modifier.size(size * 0.45f))
        }
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    highlight: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (highlight) Pal.Accent else Pal.Text,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, color = Pal.Sub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (chevron) {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Pal.Sub, modifier = Modifier.size(28.dp))
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = if (leading != null) 92.dp else 20.dp),
            thickness = 0.5.dp,
            color = Pal.Divider,
        )
    }
}

@Composable
fun TrackRow(
    number: String,
    title: String,
    subtitle: String?,
    durationMs: Long,
    playing: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterStart) {
                if (playing) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Pal.Accent, modifier = Modifier.size(22.dp))
                } else {
                    Text(number, color = Pal.Sub, fontSize = 17.sp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (playing) Pal.Accent else Pal.Text,
                    fontSize = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, color = Pal.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(fmtTime(durationMs), color = Pal.Sub, fontSize = 15.sp)
        }
        HorizontalDivider(modifier = Modifier.padding(start = 56.dp), thickness = 0.5.dp, color = Pal.Divider)
    }
}

@Composable
fun ScreenTitle(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = Pal.Text,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

@Composable
fun BackBar(label: String, onBack: () -> Unit) {
    Row(
        Modifier.padding(start = 4.dp, top = 4.dp).height(56.dp).clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onBack).padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.ChevronLeft, contentDescription = "戻る", tint = Pal.Accent, modifier = Modifier.size(40.dp))
        Text(label, color = Pal.Accent, fontSize = 20.sp)
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        color = Pal.Sub,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 6.dp),
    )
}

@Composable
fun PillButton(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.height(52.dp).clip(RoundedCornerShape(12.dp)).background(Pal.Surface2)
            .clickable(onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Pal.Accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Pal.Accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CenterMessage(title: String, body: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Pal.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, color = Pal.Sub, fontSize = 17.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
fun AlbumTile(album: Album, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            AlbumArt(album.cover, maxWidth, corner = 8.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(album.title, color = Pal.Text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Pal.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 右端の索引（A〜Z・あ〜わ）。タップ・なぞりでその位置へジャンプ */
@Composable
fun IndexBar(letters: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var heightPx by remember { mutableIntStateOf(1) }
    var last by remember { mutableStateOf<String?>(null) }
    val currentLetters by rememberUpdatedState(letters)
    val select by rememberUpdatedState(onSelect)

    fun pick(y: Float) {
        val ls = currentLetters
        if (ls.isEmpty()) return
        val i = ((y / heightPx) * ls.size).toInt().coerceIn(0, ls.lastIndex)
        if (ls[i] != last) {
            last = ls[i]
            select(ls[i])
        }
    }

    BoxWithConstraints(modifier.width(32.dp).fillMaxHeight().padding(vertical = 6.dp)) {
        val per = maxHeight / letters.size.coerceAtLeast(1)
        val fontSize = (per.value * 0.72f).coerceIn(7f, 14f).sp
        Column(
            Modifier.fillMaxSize()
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = { offset ->
                        pick(offset.y)
                        tryAwaitRelease()
                        last = null
                    })
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { offset -> pick(offset.y) },
                        onDragEnd = { last = null },
                        onDragCancel = { last = null },
                    ) { change, _ -> pick(change.position.y) }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach {
                Text(it, color = Pal.Accent, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** 索引付きのリスト（アーティスト・曲の一覧で使用） */
@Composable
fun <T> IndexedList(
    list: List<T>,
    name: (T) -> String,
    state: LazyListState,
    header: @Composable () -> Unit,
    row: @Composable (Int, T) -> Unit,
) {
    val groups = remember(list) {
        val m = LinkedHashMap<String, Int>()
        list.forEachIndexed { i, t ->
            val g = SortKey.groupOf(name(t))
            if (!m.containsKey(g)) m[g] = i
        }
        m
    }
    val scope = rememberCoroutineScope()
    val showIndex = groups.size > 1
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = if (showIndex) 32.dp else 0.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item { header() }
            items(list.size) { i -> row(i, list[i]) }
        }
        if (showIndex) {
            IndexBar(groups.keys.toList(), Modifier.align(Alignment.CenterEnd)) { g ->
                scope.launch { state.scrollToItem((groups[g] ?: 0) + 1) }
            }
        }
    }
}
