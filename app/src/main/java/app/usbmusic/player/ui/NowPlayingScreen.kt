package app.usbmusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import app.usbmusic.player.data.Library
import app.usbmusic.player.data.Track
import app.usbmusic.player.playback.PlayerConnection
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(
    conn: PlayerConnection,
    lib: Library,
    onArtist: (String) -> Unit,
    onAlbum: (String) -> Unit,
) {
    val item = conn.current
    if (item == null) {
        CenterMessage("再生中の曲はありません", "アーティスト・アルバム・曲から選んでください")
        return
    }
    val track = remember(item.mediaId, lib) { lib.byUri(item.mediaId) ?: Track.fromItem(item) }
    var showQueue by rememberSaveable { mutableStateOf(false) }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            AlbumArt(track, min(maxWidth, maxHeight), corner = 12.dp)
        }
        Spacer(Modifier.width(40.dp))
        Box(Modifier.weight(1.15f).fillMaxHeight()) {
            if (showQueue) {
                QueuePanel(conn, lib) { showQueue = false }
            } else {
                PlayerPanel(conn, track, lib, onArtist, onAlbum) { showQueue = true }
            }
        }
    }
}

@Composable
private fun PlayerPanel(
    conn: PlayerConnection,
    track: Track,
    lib: Library,
    onArtist: (String) -> Unit,
    onAlbum: (String) -> Unit,
    onQueue: () -> Unit,
) {
    var position by remember { mutableLongStateOf(conn.position) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            position = conn.position
            delay(500)
        }
    }
    val duration = conn.duration.takeIf { it > 0 } ?: track.durationMs
    val artistTarget = lib.artist(track.groupArtist)?.name
    val albumTarget = lib.album(track.albumKey)?.key

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            track.title.ifBlank { "—" },
            color = Pal.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            track.displayArtist,
            color = Pal.Accent, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (artistTarget != null) Modifier.clickable { onArtist(artistTarget) } else Modifier,
        )
        Text(
            track.album,
            color = Pal.Sub, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (albumTarget != null) Modifier.clickable { onAlbum(albumTarget) } else Modifier,
        )
        Spacer(Modifier.height(24.dp))

        val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { conn.seekTo((it * duration).toLong()) }
                dragging = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Pal.Surface2,
            ),
        )
        val shown = dragging?.let { (it * duration).toLong() } ?: position
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(shown), color = Pal.Sub, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text("-" + fmtTime((duration - shown).coerceAtLeast(0)), color = Pal.Sub, fontSize = 15.sp)
        }
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BigButton(Icons.Filled.SkipPrevious, 76.dp) { conn.previous() }
            BigButton(if (conn.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, 100.dp) { conn.toggle() }
            BigButton(Icons.Filled.SkipNext, 76.dp) { conn.next() }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToggleIcon(Icons.Filled.Shuffle, conn.shuffle) { conn.toggleShuffle() }
            ToggleIcon(
                if (conn.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                conn.repeat != Player.REPEAT_MODE_OFF,
            ) { conn.cycleRepeat() }
            ToggleIcon(Icons.Filled.QueueMusic, false) { onQueue() }
        }
    }
}

@Composable
private fun QueuePanel(conn: PlayerConnection, lib: Library, onClose: () -> Unit) {
    val upcoming = remember(conn.queueVersion, conn.current?.mediaId, conn.shuffle) { conn.upcoming(200) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "次に再生", color = Pal.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("閉じる", color = Pal.Accent, fontSize = 19.sp) }
        }
        if (upcoming.isEmpty()) {
            Text("次の曲はありません", color = Pal.Sub, fontSize = 17.sp, modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(upcoming) { (index, item) ->
                val t = lib.byUri(item.mediaId) ?: Track.fromItem(item)
                ListRow(
                    title = t.title,
                    subtitle = t.displayArtist,
                    leading = { AlbumArt(t, 48.dp) },
                    chevron = false,
                ) { conn.jumpTo(index) }
            }
        }
    }
}

@Composable
private fun BigButton(icon: ImageVector, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.72f))
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active) Pal.Surface2 else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Pal.Accent else Pal.Sub, modifier = Modifier.size(34.dp))
    }
}
