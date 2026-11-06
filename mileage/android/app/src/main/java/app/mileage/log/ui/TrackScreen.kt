package app.mileage.log.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.mileage.log.Store
import app.mileage.log.Tracking
import app.mileage.log.TrackingService
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun TrackScreen() {
    val context = LocalContext.current
    val s by Tracking.state.collectAsState()
    val trips by Store.trips.collectAsState()
    val settings by Store.settings.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var denied by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var editSettings by remember { mutableStateOf(false) }

    LaunchedEffect(s.running) {
        while (s.running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) TrackingService.start(context)
        else denied = true
    }

    fun onStart() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val needNotify = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (fine && !needNotify) {
            TrackingService.start(context)
        } else {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            launcher.launch(perms.toTypedArray())
        }
    }

    val today = startOfToday()
    val todayKm = trips.filter { it.start >= today }.sumOf { it.km } + if (s.running) s.meters / 1000 else 0.0
    val thisMonth = monthKey(System.currentTimeMillis())
    val monthKm = trips.filter { monthKey(it.start) == thisMonth }.sumOf { it.km } + if (s.running) s.meters / 1000 else 0.0

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { editSettings = true }) { Icon(Icons.Filled.Settings, "設定") }
        }
        Text(
            if (s.running) "計測中" else "停止中",
            color = if (s.running) MaterialTheme.colorScheme.secondary else Color.Gray,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                fmtKm2(if (s.running) s.meters / 1000 else 0.0),
                fontSize = 72.sp, fontWeight = FontWeight.Bold,
            )
            Text(" km", fontSize = 24.sp, modifier = Modifier.padding(bottom = 14.dp))
        }
        if (s.running) {
            Text(fmtDuration(now - s.startTime), fontSize = 22.sp, color = Color.LightGray)
            Spacer(Modifier.height(4.dp))
            Text(
                if (s.hasFix) String.format(Locale.JAPAN, "%.0f km/h ・ GPS精度 %s", s.speedKmh,
                    s.accuracy?.let { "±%.0fm".format(it) } ?: "-")
                else "GPS を探しています…",
                color = if (s.hasFix) Color.Gray else MaterialTheme.colorScheme.error,
            )
        } else {
            Text(" ", fontSize = 22.sp)
            Text(" ")
        }

        Spacer(Modifier.weight(1f))

        Text(if (s.running) "この走行の区分" else "次の走行の区分", color = Color.Gray, fontSize = 13.sp)
        CategoryPicker(
            selected = settings.nextCategory,
            onSelect = { Store.updateSettings(settings.copy(nextCategory = it)) },
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        Button(
            onClick = { if (s.running) confirmStop = true else onStart() },
            shape = CircleShape,
            modifier = Modifier.size(180.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (s.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(if (s.running) "停止" else "開始", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("今日", todayKm, Modifier.weight(1f))
            SummaryCard("今月", monthKm, Modifier.weight(1f))
        }
    }

    if (editSettings) SettingsDialog(settings) { editSettings = false }

    if (s.running && s.askEnd) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("運転を終了しましたか？") },
            text = {
                Text("${fmtTime(s.stoppedAt)} から止まっています。答えがなければ ${fmtTime(s.autoSaveAt)} に、" +
                    "${fmtTime(s.stoppedAt)} に終了したものとして自動で保存します。")
            },
            confirmButton = {
                TextButton(onClick = { TrackingService.endAtStop(context) }) { Text("終了して保存") }
            },
            dismissButton = {
                TextButton(onClick = { TrackingService.continueDriving(context) }) { Text("まだ運転中") }
            },
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("計測を終了しますか？") },
            text = { Text("この走行（${fmtKm2(s.meters / 1000)} km・${settings.nextCategory.label}）を履歴に保存します。区分や行き先は履歴であとから直せます。50m 未満の場合は保存しません。") },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; TrackingService.stop(context) }) { Text("終了して保存") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("続ける") } },
        )
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text("位置情報の許可が必要です") },
            text = { Text("走行距離を GPS で測るため、「正確な位置情報」を許可してください。設定アプリの「アプリ」→「走行距離ログ」→「権限」からも変更できます。") },
            confirmButton = { TextButton(onClick = { denied = false }) { Text("OK") } },
        )
    }
}

@Composable
fun SummaryCard(label: String, km: Double, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.Gray, fontSize = 14.sp)
            Text("${fmtKm(km)} km", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}
