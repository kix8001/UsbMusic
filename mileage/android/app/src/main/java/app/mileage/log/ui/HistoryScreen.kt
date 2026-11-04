package app.mileage.log.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mileage.log.Category
import app.mileage.log.Store
import app.mileage.log.Trip
import java.util.Calendar
import java.util.TimeZone

@Composable
fun HistoryScreen() {
    val trips by Store.trips.collectAsState()
    var editing by remember { mutableStateOf<Trip?>(null) }
    var adding by remember { mutableStateOf(false) }
    /** null = すべて */
    var filter by remember { mutableStateOf<Category?>(null) }

    val now = System.currentTimeMillis()
    val year = yearOf(now)
    val month = monthKey(now)
    val claim = trips.filter { it.category == Category.COMMUTE || it.category == Category.BUSINESS }
    val monthKm = claim.filter { monthKey(it.start) == month }.sumOf { it.km }
    val yearKm = claim.filter { yearOf(it.start) == year }.sumOf { it.km }
    val unset = trips.count { it.category == Category.UNSET }
    val shown = if (filter == null) trips else trips.filter { it.category == filter }
    val groups = shown.groupBy { monthKey(it.start) }.toSortedMap(reverseOrder())

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text("履歴", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryCard("今月（通勤＋業務）", monthKm, Modifier.weight(1f))
                SummaryCard("${year}年（通勤＋業務）", yearKm, Modifier.weight(1f))
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("すべて") })
                listOf(Category.UNSET, Category.COMMUTE, Category.BUSINESS, Category.PRIVATE).forEach { c ->
                    val label = if (c == Category.UNSET && unset > 0) "${c.label}（$unset）" else c.label
                    FilterChip(selected = filter == c, onClick = { filter = c }, label = { Text(label) })
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (trips.isEmpty()) "まだ記録がありません" else "該当する記録はありません", color = Color.Gray)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (_, list) ->
                    item(key = "h" + list.first().start) {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)
                        ) {
                            Text(fmtMonth(list.first().start), fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Text("${fmtKm(list.sumOf { it.km })} km ・ ${list.size}回",
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    items(list, key = { it.id }) { t ->
                        TripRow(t) { editing = t }
                        HorizontalDivider(Modifier.padding(start = 20.dp), color = Color(0x22FFFFFF))
                    }
                }
                item { Spacer(Modifier.padding(48.dp)) }
            }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Filled.Add, "手入力で追加") }
    }

    editing?.let { t -> TripEditDialog(t, onDismiss = { editing = null }) }
    if (adding) ManualTripDialog(onDismiss = { adding = false })
}

@Composable
private fun TripRow(t: Trip, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(t.category)
                Spacer(Modifier.width(8.dp))
                Text(fmtDay(t.start), fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (t.manual) "手入力" else "${fmtTime(t.start)}〜${fmtTime(t.end)}",
                    color = Color.Gray, fontSize = 13.sp)
            }
            val what = listOf(t.destination, t.purpose, t.note).filter { it.isNotBlank() }.joinToString(" ・ ")
            if (what.isNotEmpty()) Text(what, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.startAddr.isNotBlank() || t.endAddr.isNotBlank()) {
                Text("${t.startAddr.ifBlank { "?" }} → ${t.endAddr.ifBlank { "?" }}",
                    color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text("${fmtKm(t.km)} km", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 文字列を km の数値に（全角数字やカンマも受け付ける） */
fun parseKm(s: String): Double? {
    val half = s.map { c -> if (c in '０'..'９') ('0' + (c - '０')) else if (c == '．') '.' else c }
        .joinToString("").replace(",", "").replace("，", "").trim()
    return half.toDoubleOrNull()?.takeIf { it >= 0 }
}

/** 行き先・用件などの入力欄をまとめたもの */
@Composable
private fun TripFields(
    category: Category, onCategory: (Category) -> Unit,
    km: String, onKm: (String) -> Unit, kmError: Boolean,
    destination: String, onDestination: (String) -> Unit,
    purpose: String, onPurpose: (String) -> Unit,
    startAddr: String, onStartAddr: (String) -> Unit,
    endAddr: String, onEndAddr: (String) -> Unit,
    note: String, onNote: (String) -> Unit,
) {
    CategoryPicker(category, onCategory, Modifier.padding(top = 4.dp))
    OutlinedTextField(
        value = km, onValueChange = onKm, label = { Text("距離 (km)") },
        singleLine = true, isError = kmError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(destination, onDestination, label = { Text("行き先（訪問先など）") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    OutlinedTextField(purpose, onPurpose, label = { Text("用件") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    OutlinedTextField(startAddr, onStartAddr, label = { Text("出発地") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    OutlinedTextField(endAddr, onEndAddr, label = { Text("到着地") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    OutlinedTextField(note, onNote, label = { Text("メモ") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable
private fun TripEditDialog(t: Trip, onDismiss: () -> Unit) {
    var category by remember { mutableStateOf(t.category) }
    var km by remember { mutableStateOf(String.format(java.util.Locale.US, "%.2f", t.km)) }
    var destination by remember { mutableStateOf(t.destination) }
    var purpose by remember { mutableStateOf(t.purpose) }
    var startAddr by remember { mutableStateOf(t.startAddr) }
    var endAddr by remember { mutableStateOf(t.endAddr) }
    var note by remember { mutableStateOf(t.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    val kmVal = parseKm(km)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("この記録を削除しますか？") },
            confirmButton = {
                TextButton(onClick = { Store.deleteTrip(t.id); onDismiss() }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fmtFull(t.start)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!t.manual) Text("${fmtTime(t.start)}〜${fmtTime(t.end)}（${fmtDuration(t.end - t.start)}）",
                    color = Color.Gray)
                TripFields(
                    category, { category = it }, km, { km = it }, kmVal == null,
                    destination, { destination = it }, purpose, { purpose = it },
                    startAddr, { startAddr = it }, endAddr, { endAddr = it }, note, { note = it },
                )
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 12.dp)) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = kmVal != null, onClick = {
                Store.updateTrip(t.copy(
                    meters = (kmVal ?: t.km) * 1000,
                    category = category,
                    destination = destination.trim(), purpose = purpose.trim(),
                    startAddr = startAddr.trim(), endAddr = endAddr.trim(), note = note.trim(),
                ))
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualTripDialog(onDismiss: () -> Unit) {
    var category by remember { mutableStateOf(Store.settings.value.nextCategory) }
    var km by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var startAddr by remember { mutableStateOf("") }
    var endAddr by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(startOfToday()) }
    var picking by remember { mutableStateOf(false) }
    val kmVal = parseKm(km)

    if (picking) {
        // DatePicker は UTC の0時で日付を返すので、端末の時刻帯の0時に直す
        val utcDay = Calendar.getInstance().apply { timeInMillis = day }.let { c ->
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = utcDay)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { sel ->
                        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                        day = Calendar.getInstance().apply {
                            clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
                        }.timeInMillis
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("キャンセル") } },
        ) { DatePicker(state = state) }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("走行を手入力") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(fmtDay(day))
                }
                TripFields(
                    category, { category = it }, km, { km = it }, km.isNotEmpty() && kmVal == null,
                    destination, { destination = it }, purpose, { purpose = it },
                    startAddr, { startAddr = it }, endAddr, { endAddr = it }, note, { note = it },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = kmVal != null && kmVal > 0, onClick = {
                // 時刻は正午にしておく（日付の境目で別の日に入らないように）
                val t = day + 12 * 60 * 60 * 1000L
                Store.addTrip(Trip(
                    id = Store.newId(), start = t, end = t, meters = (kmVal ?: 0.0) * 1000,
                    note = note.trim(), manual = true, category = category,
                    destination = destination.trim(), purpose = purpose.trim(),
                    startAddr = startAddr.trim(), endAddr = endAddr.trim(),
                ))
                onDismiss()
            }) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
