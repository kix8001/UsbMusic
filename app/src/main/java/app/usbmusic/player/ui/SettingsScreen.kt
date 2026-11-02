package app.usbmusic.player.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.usbmusic.player.MainViewModel
import app.usbmusic.player.data.Prefs

@Composable
fun SettingsScreen(vm: MainViewModel, onPickFolder: () -> Unit) {
    val lib = vm.library
    var includeInternal by remember { mutableStateOf(vm.prefs.includeInternal) }
    var autoResume by remember { mutableStateOf(vm.prefs.autoResume) }
    val usbLabels = remember(lib, vm.scanning) { runCatching { vm.repo.usbVolumeLabels() }.getOrDefault(emptyList()) }
    val folderLabel = vm.treeUri?.let { raw ->
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(raw)) }.getOrNull() ?: raw
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        ScreenTitle("設定")

        SectionHeader("音楽の読み込み元")
        OptionRow(
            title = "USBメモリを自動で検出（おすすめ）",
            body = "Androidのメディアライブラリから読み込みます。速く、USBを挿すと自動で更新されます。",
            selected = vm.sourceMode == Prefs.MODE_AUTO,
        ) { vm.setMode(Prefs.MODE_AUTO) }
        OptionRow(
            title = "フォルダを指定して直接読み込む",
            body = if (folderLabel != null) "選択中：$folderLabel" else "自動検出で曲が出ない時に使います。初回は時間がかかります。",
            selected = vm.sourceMode == Prefs.MODE_FOLDER,
        ) {
            if (vm.treeUri == null) onPickFolder() else vm.setMode(Prefs.MODE_FOLDER)
        }
        if (vm.sourceMode == Prefs.MODE_FOLDER) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                PillButton(Icons.Filled.Folder, "フォルダを選び直す", onPickFolder)
            }
        } else {
            SwitchRow("本体（内部ストレージ）の音楽も含める", includeInternal) {
                includeInternal = it
                vm.setIncludeInternal(it)
            }
        }

        // 通知がオフだと、ナビのメディア再生バー（ホーム画面下）に表示されない
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var notifOn by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
        LaunchedEffect(Unit) {
            while (true) {
                notifOn = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                kotlinx.coroutines.delay(1500)
            }
        }
        if (!notifOn) {
            SectionHeader("ナビのメディア再生バー")
            InfoRow("通知がオフのため、ナビのホーム画面下のメディア再生バーに mTunes が表示されません。")
            ActionRow("通知をオンにする（設定を開く）") {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                runCatching { ctx.startActivity(intent) }
            }
        }

        SectionHeader("再生")
        SwitchRow("アプリを開いたら前回の続きを自動再生", autoResume) {
            autoResume = it
            vm.prefs.autoResume = it
        }

        SectionHeader("音量")
        SwitchRow("音量を一定にする（曲・動画ごとの音量差をそろえる）", vm.leveler) { vm.changeLeveler(it) }

        SectionHeader("動画")
        SwitchRow("次の動画へ切り替わる時にクロスディゾルブする", vm.videoFade) { vm.changeVideoFade(it) }
        SwitchRow("再生の最初の10秒にタイトルとアーティストを表示", vm.videoTitles) { vm.changeVideoTitles(it) }

        SectionHeader("ビジュアライザー（再生中画面）")
        VisStyle.entries.forEach { st ->
            OptionRow(
                title = st.label,
                body = st.desc,
                selected = vm.visualizer == st,
            ) { vm.setVisualizerStyle(st) }
        }
        InfoRow("再生中画面のビジュアライザーをタップしても切り替えられます。")

        SectionHeader("ライブラリ")
        InfoRow(
            buildString {
                append("${lib.tracks.size}曲・${lib.artists.size}アーティスト・${lib.albums.size}アルバム")
                val tp = vm.tagProgress
                if (tp != null) append("（IDタグを読み込み中 ${tp.first}/${tp.second}）")
                else if (vm.scanning) append("（読み込み中）")
            }
        )
        InfoRow(
            if (usbLabels.isEmpty()) "USBメモリが見つかりません"
            else "検出されたUSB：" + usbLabels.joinToString("、")
        )
        ActionRow("ライブラリを再読み込み") { vm.rescan() }
        ActionRow(if (vm.mediaScanRunning) "登録中…（数分かかることがあります）" else "Androidに曲・動画を登録し直してもらう") { vm.requestMediaScan() }
        ActionRow("アルバムアートを読み込み直す") { vm.clearArtCache() }

        SectionHeader("このアプリについて")
        InfoRow("mTunes 0.7.3")
        InfoRow("MAKOTO MORITA MADE")
    }
}

@Composable
private fun OptionRow(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = Pal.Accent, unselectedColor = Pal.Sub),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Pal.Text, fontSize = 19.sp)
                Text(body, color = Pal.Sub, fontSize = 14.sp)
            }
        }
        HorizontalDivider(Modifier.padding(start = 20.dp), thickness = 0.5.dp, color = Pal.Divider)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Pal.Text, fontSize = 19.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Pal.Accent,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Pal.Surface2,
                    uncheckedThumbColor = Pal.Sub,
                    uncheckedBorderColor = Pal.Surface2,
                ),
            )
        }
        HorizontalDivider(Modifier.padding(start = 20.dp), thickness = 0.5.dp, color = Pal.Divider)
    }
}

@Composable
private fun InfoRow(text: String) {
    Text(
        text,
        color = Pal.Sub,
        fontSize = 17.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

@Composable
private fun ActionRow(title: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            title,
            color = Pal.Accent,
            fontSize = 19.sp,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 20.dp),
        )
        HorizontalDivider(Modifier.padding(start = 20.dp), thickness = 0.5.dp, color = Pal.Divider)
    }
}
