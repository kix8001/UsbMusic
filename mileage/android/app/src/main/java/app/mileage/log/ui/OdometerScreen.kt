package app.mileage.log.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mileage.log.OdoEntry
import app.mileage.log.Store

/** 車のメーター（オドメーター）の値を手で記録する画面。前回からの差＝実際に走った距離 */
@Composable
fun OdometerScreen() {
    val odos by Store.odos.collectAsState()
    val trips by Store.trips.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<OdoEntry?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text("メーター", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
            Text("給油のときなどに車のメーターの値を記録すると、前回からの走行距離がわかります。",
                color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))

            if (odos.size >= 2) {
                val newest = odos.first()
                val oldest = odos.last()
                // 同じ期間に GPS で測った距離と比べる（GPS の誤差の目安になる）
                val gps = trips.filter { it.start in oldest.time..newest.time }.sumOf { it.km }
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${fmtDay(oldest.time)} 〜 ${fmtDay(newest.time)}", color = Color.Gray, fontSize = 13.sp)
                        Text("メーター：${fmtKm(newest.km - oldest.km)} km", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("同じ期間の GPS 記録：${fmtKm(gps)} km", color = Color.Gray)
                    }
                }
            } else {
                Spacer(Modifier.padding(8.dp))
            }

            if (odos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("右下の＋で記録します", color = Color.Gray)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(odos, key = { _, o -> o.id }) { i, o ->
                    val prev = odos.getOrNull(i + 1)
                    Row(
                        Modifier.fillMaxWidth().clickable { deleting = o }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${fmtKm(o.km)} km", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text(fmtFull(o.time) + if (o.note.isNotBlank()) " ・ ${o.note}" else "",
                                color = Color.Gray, fontSize = 13.sp, maxLines = 1)
                        }
                        if (prev != null) {
                            Text("+${fmtKm(o.km - prev.km)} km", color = MaterialTheme.colorScheme.secondary,
                                fontSize = 16.sp)
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 20.dp), color = Color(0x22FFFFFF))
                }
                item { Spacer(Modifier.padding(48.dp)) }
            }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Filled.Add, "メーターを記録") }
    }

    if (adding) {
        var km by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        val kmVal = parseKm(km)
        val last = odos.firstOrNull()
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("メーターを記録") },
            text = {
                Column {
                    if (last != null) Text("前回：${fmtKm(last.km)} km（${fmtDay(last.time)}）", color = Color.Gray)
                    OutlinedTextField(
                        value = km, onValueChange = { km = it }, label = { Text("メーターの値 (km)") },
                        singleLine = true, isError = km.isNotEmpty() && kmVal == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    if (last != null && kmVal != null && kmVal < last.km) {
                        Text("前回より小さい値です", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    OutlinedTextField(
                        value = note, onValueChange = { note = it }, label = { Text("メモ（給油など）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = kmVal != null, onClick = {
                    Store.addOdo(System.currentTimeMillis(), kmVal ?: 0.0, note.trim())
                    adding = false
                }) { Text("記録") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("キャンセル") } },
        )
    }

    deleting?.let { o ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("${fmtKm(o.km)} km（${fmtFull(o.time)}）") },
            text = { Text("この記録を削除しますか？") },
            confirmButton = {
                TextButton(onClick = { Store.deleteOdo(o.id); deleting = null }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("やめる") } },
        )
    }
}
