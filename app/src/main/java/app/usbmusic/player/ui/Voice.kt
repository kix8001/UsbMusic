package app.usbmusic.player.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.usbmusic.player.MainViewModel
import app.usbmusic.player.data.SortKey
import app.usbmusic.player.playback.ItemGains
import app.usbmusic.player.playback.MediaItems
import app.usbmusic.player.playback.PlayerConnection
import app.usbmusic.player.playback.VolumeLeveler
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ---------------- 音声コマンドの解釈 ----------------

object VoiceCommands {
    private val PLAY_SUFFIX = Regex(
        "(を|の)?((再生|プレイ|プレー)(して|させて)?|(かけ|流し|ながし|聴い|聞い|きい|見せ|みせ|見|み)て|(聴き|聞き|きき|見|み)たい)" +
            "(ください|下さい|くれ|ほしい|欲しい)?$"
    )
    private val NEXT = Regex("^(次|つぎ)(の曲|の動画|のきょく)?(へ|に)?(進んで|すすんで|いって|行って|お願い|おねがい)?$")
    private val PREV = Regex("^(前|まえ|一つ前|ひとつ前|一曲前|ひとつまえ)?(の曲|の動画|のきょく)?(へ|に)?(戻って|もどって|戻る|もどる|お願い|おねがい)?$")
    private val HEAD = Regex("^(mtunes|エムチューンズ|エムチューン)(で|の)?")

    private fun clean(s: String) = s.replace(Regex("[\\s。、！!？?「」]"), "")

    private fun anyOf(n: String, vararg words: String) = words.any { n.contains(it) }

    /** 話した言葉を実行し、画面に出す結果の説明を返す */
    fun run(raw: String, vm: MainViewModel, conn: PlayerConnection, context: Context): String {
        val n = clean(raw).lowercase().replace(HEAD, "")
        val am = context.getSystemService(AudioManager::class.java)
        val lib = vm.library

        // --- 音量 ---
        if (anyOf(n, "音量", "ボリューム", "音を", "おと")) {
            val up = anyOf(n, "上げ", "あげ", "大きく", "おおきく", "アップ")
            val down = anyOf(n, "下げ", "さげ", "小さく", "ちいさく", "ダウン")
            if (up || down) {
                repeat(2) {
                    am?.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0,
                    )
                }
                return if (up) "音量を上げました" else "音量を下げました"
            }
        }
        // --- 基本操作 ---
        when {
            NEXT.matches(n) || anyOf(n, "スキップ", "飛ばして", "とばして") -> {
                conn.next(); return "次へ"
            }
            n.isNotEmpty() && !n.startsWith("の") && PREV.matches(n) -> {
                conn.previous(); return "前へ"
            }
            anyOf(n, "止めて", "とめて", "停止", "ストップ", "ポーズ", "一時停止", "止まって") -> {
                conn.controller?.pause(); return "一時停止しました"
            }
            n in setOf("再生", "再開", "続き", "続きを再生", "再生して", "スタート", "プレイ") -> {
                conn.controller?.let {
                    if (it.playbackState == androidx.media3.common.Player.STATE_IDLE) it.prepare()
                    it.play()
                }
                return "再生します"
            }
        }

        // --- 何を再生するか ---
        var q = n.replace(PLAY_SUFFIX, "")
        val shuffle = q.contains("シャッフル") || q.contains("ランダム")
        q = q.replace("シャッフル", "").replace("ランダム", "").replace(PLAY_SUFFIX, "")
        val wantVideo = anyOf(q, "動画", "ビデオ", "映像", "ムービー")
        q = q.replace(Regex("[をで]+$"), "")
            .replace(Regex("(の)?(動画|ビデオ|映像|ムービー|曲|歌|うた|アルバム)$"), "")
            .replace(Regex("^(動画|ビデオ|映像)(で|の)?"), "")
            .replace(Regex("^(アルバム|曲)(の)?"), "")
            .replace(Regex("[をで]+$"), "")
            .trim()

        if (q.isBlank()) {
            if (wantVideo) {
                if (vm.videos.isEmpty()) return "動画がありません"
                conn.playVideos(context, vm.videos, 0, shuffled = true)
                return "動画をシャッフル再生します"
            }
            if (shuffle) {
                if (lib.songs.isEmpty()) return "曲がありません"
                conn.playTracks(lib.songs, shuffled = true)
                return "全曲をシャッフル再生します"
            }
            return "「$raw」を聞き取れませんでした"
        }

        val nq = SortKey.searchNorm(q)
        fun eq(s: String) = SortKey.searchNorm(s) == nq

        // 動画
        val videoHits = vm.videos.filter {
            val t = SortKey.searchNorm(it.title + " " + it.titleSort + " " + it.artist + " " + it.artistSort + " " + it.album)
            t.contains(nq)
        }
        if (wantVideo || (lib.tracks.isEmpty() && videoHits.isNotEmpty())) {
            if (videoHits.isEmpty()) return "「$q」の動画が見つかりません"
            val byArtist = videoHits.filter { eq(it.artist) || eq(it.artistSort) }
            val list = byArtist.ifEmpty { videoHits }
            conn.playVideos(context, list, 0, shuffled = shuffle)
            return "動画「${list.first().title}」${if (list.size > 1) "ほか${list.size - 1}本" else ""}を再生します"
        }

        // 音楽：アーティスト → アルバム → 曲 の順に、完全一致を優先
        val r = lib.search(q)
        val artist = r.artists.firstOrNull { eq(it.name) || eq(it.sortName) }
        val album = r.albums.firstOrNull { eq(it.title) || eq(it.sortTitle) }
        val song = r.songs.firstOrNull { eq(it.title) || eq(it.titleSort) }
        when {
            artist != null -> {
                conn.playTracks(artist.tracks, shuffled = shuffle); return "${artist.name}を再生します"
            }
            album != null -> {
                conn.playTracks(album.tracks, shuffled = shuffle); return "アルバム「${album.title}」を再生します"
            }
            song != null -> {
                val a = lib.album(song.albumKey)
                if (a != null) conn.playTracks(a.tracks, a.tracks.indexOf(song).coerceAtLeast(0)) else conn.playTracks(listOf(song))
                return "「${song.title}」を再生します"
            }
            r.artists.isNotEmpty() -> {
                val a = r.artists.first(); conn.playTracks(a.tracks, shuffled = shuffle); return "${a.name}を再生します"
            }
            r.albums.isNotEmpty() -> {
                val a = r.albums.first(); conn.playTracks(a.tracks, shuffled = shuffle); return "アルバム「${a.title}」を再生します"
            }
            r.songs.isNotEmpty() -> {
                val s = r.songs.first()
                val a = lib.album(s.albumKey)
                if (a != null) conn.playTracks(a.tracks, a.tracks.indexOf(s).coerceAtLeast(0)) else conn.playTracks(listOf(s))
                return "「${s.title}」を再生します"
            }
            videoHits.isNotEmpty() -> {
                conn.playVideos(context, videoHits, 0, shuffled = shuffle)
                return "動画「${videoHits.first().title}」を再生します"
            }
        }
        return "「$q」が見つかりませんでした"
    }
}

// ---------------- マイクボタン ----------------

@Composable
fun VoiceButton(vm: MainViewModel, conn: PlayerConnection, size: Dp = 56.dp, onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            vm.showVoiceMessage("🎤 「$text」\n" + VoiceCommands.run(text, vm, conn, context))
            onDone()
        }
    }
    IconButton(
        onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "「山下達郎を再生」「次の曲」「動画をシャッフル」など")
            try {
                launcher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                vm.showVoiceMessage("この機器では音声入力が使えません")
            }
        },
        modifier = Modifier.size(size).clip(CircleShape).background(Pal.Surface2),
    ) {
        Icon(Icons.Filled.Mic, contentDescription = "音声操作", tint = Pal.Accent, modifier = Modifier.size(size * 0.55f))
    }
}

/** 音声操作の結果を画面上部に表示 */
@Composable
fun VoiceBanner(vm: MainViewModel, modifier: Modifier = Modifier) {
    val msg = vm.voiceMessage
    var last by remember { mutableStateOf("") }
    if (msg != null) last = msg
    AnimatedVisibility(
        visible = msg != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier,
    ) {
        Text(
            last,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(16.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xEE2C2C2E)).padding(horizontal = 22.dp, vertical = 14.dp),
        )
    }
}

// ---------------- 音量スライダー ----------------

@Composable
fun VolumeSlider(modifier: Modifier = Modifier, light: Boolean = false) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15 }
    var vol by remember { mutableFloatStateOf(am?.getStreamVolume(AudioManager.STREAM_MUSIC)?.toFloat() ?: 0f) }
    var dragging by remember { mutableStateOf(false) }
    // ハンドルのボタン等で音量が変わっても追従する
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) am?.getStreamVolume(AudioManager.STREAM_MUSIC)?.let { vol = it.toFloat() }
            delay(600)
        }
    }
    fun set(v: Float) {
        vol = v.coerceIn(0f, max.toFloat())
        am?.setStreamVolume(AudioManager.STREAM_MUSIC, vol.roundToInt(), 0)
    }
    val tint = if (light) Color.White else Pal.Sub
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { set(vol - 1) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.VolumeDown, contentDescription = "音量を下げる", tint = tint, modifier = Modifier.size(28.dp))
        }
        Slider(
            value = vol / max,
            onValueChange = { dragging = true; set(it * max) },
            onValueChangeFinished = { dragging = false },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = if (light) Pal.Accent else Color.White,
                inactiveTrackColor = if (light) Color(0x66FFFFFF) else Pal.Surface2,
            ),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { set(vol + 1) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.VolumeUp, contentDescription = "音量を上げる", tint = tint, modifier = Modifier.size(28.dp))
        }
    }
}

// ---------------- この曲・動画だけの音量 ----------------

/** 「この曲 ±0dB」ボタン。押すと、この曲だけの音量を上げ下げできる（曲ごとに記憶） */
@Composable
fun ItemGainButton(conn: PlayerConnection) {
    val context = LocalContext.current
    val item = conn.current ?: return
    val key = remember(item.mediaId) { MediaItems.keyOf(item) }
    var db by remember(key) { mutableIntStateOf(ItemGains.get(context, key)) }
    var open by remember { mutableStateOf(false) }
    val what = if (conn.isVideo) "この動画" else "この曲"
    fun change(v: Int) {
        db = v.coerceIn(-12, 12)
        ItemGains.put(context, key, db)
        VolumeLeveler.itemGain = ItemGains.linear(db)
    }
    val label = if (db > 0) "+${db}dB" else "${db}dB"
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(Pal.Surface2)
            .clickable { open = true }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$what $label", color = if (db != 0) Pal.Accent else Pal.Sub, fontSize = 15.sp, maxLines = 1, softWrap = false)
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Pal.Surface).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${what}だけの音量", color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "次に再生した時も覚えています。「音量を一定にする」と一緒に使えます。",
                    color = Pal.Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GainStep("−", 72.dp) { change(db - 1) }
                    Text(label, color = Pal.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(140.dp), textAlign = TextAlign.Center)
                    GainStep("＋", 72.dp) { change(db + 1) }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(Icons.Filled.VolumeDown, "元に戻す") { change(0) }
                    PillButton(Icons.Filled.Mic, "閉じる") { open = false }
                }
            }
        }
    }
}

@Composable
private fun GainStep(text: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Pal.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Pal.Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    }
}
