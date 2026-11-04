package app.mileage.log.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.mileage.log.Category
import app.mileage.log.MonthReport
import app.mileage.log.Settings
import app.mileage.log.Store
import java.io.File
import java.util.Calendar

@Composable
fun ReportScreen() {
    val context = LocalContext.current
    val trips by Store.trips.collectAsState()
    val settings by Store.settings.collectAsState()
    val cal = Calendar.getInstance()
    var year by rememberSaveable { mutableIntStateOf(cal.get(Calendar.YEAR)) }
    var month by rememberSaveable { mutableIntStateOf(cal.get(Calendar.MONTH) + 1) }
    var editSettings by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val r = MonthReport(year, month, trips, settings)

    fun shift(d: Int) {
        val m = year * 12 + (month - 1) + d
        year = m / 12; month = m % 12 + 1
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("レポート", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { editSettings = true }) { Icon(Icons.Filled.Settings, "単価・氏名の設定") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "前の月") }
            Text(r.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { shift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "次の月") }
        }

        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("支給額", color = Color.Gray)
                Text(fmtYen(r.totalAmount), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                for (c in listOf(Category.COMMUTE, Category.BUSINESS)) {
                    AmountRow(c, "${r.count(c)}回 ${fmtKm(r.km(c))} km × ${MonthReport.fmtRate(settings.rate(c))}円",
                        fmtYen(r.amount(c)))
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color(0x33FFFFFF))
                AmountRow(Category.PRIVATE, "${r.count(Category.PRIVATE)}回 ${fmtKm(r.km(Category.PRIVATE))} km", "対象外")
                if (r.count(Category.UNSET) > 0) {
                    AmountRow(Category.UNSET, "${r.count(Category.UNSET)}回 ${fmtKm(r.km(Category.UNSET))} km", "未計上")
                }
            }
        }
        if (r.count(Category.UNSET) > 0) {
            Text(
                "未分類の走行が ${r.count(Category.UNSET)} 件あります。履歴で「未分類」を選び、通勤・業務・私用に分けてください。",
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("会社に出すレポート（通勤・業務の走行のみ）", color = Color.Gray, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    try { share(context, r.pdfFile(context), "application/pdf", r.title) }
                    catch (e: Exception) { error = e.message ?: e.toString() }
                },
                modifier = Modifier.weight(1f),
            ) { Text("PDF を共有") }
            OutlinedButton(
                onClick = {
                    try { share(context, r.csvFile(context), "text/csv", r.title) }
                    catch (e: Exception) { error = e.message ?: e.toString() }
                },
                modifier = Modifier.weight(1f),
            ) { Text("CSV を共有") }
        }
        Text("メール・LINE・ドライブなど、共有先を選べます。CSV は Excel でそのまま開けます。",
            color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))

        if (r.claimTrips.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("明細", fontWeight = FontWeight.Bold)
            r.claimTrips.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(t.category)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(fmtDay(t.start), fontSize = 14.sp)
                        val what = listOf(t.destination, t.purpose).filter { it.isNotBlank() }.joinToString(" ・ ")
                        if (what.isNotEmpty()) Text(what, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${fmtKm(t.reportKm)} km", fontSize = 14.sp)
                        Text(fmtYen(r.tripAmount(t)), color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (editSettings) SettingsDialog(settings) { editSettings = false }
    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("レポートを作れませんでした") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun AmountRow(c: Category, detail: String, amount: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryBadge(c)
        Spacer(Modifier.width(8.dp))
        Text(detail, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(amount, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun share(context: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "走行距離報告書 $title")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "レポートを共有"))
}

@Composable
private fun SettingsDialog(s: Settings, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(s.name) }
    var commute by remember { mutableStateOf(MonthReport.fmtRate(s.commuteRate)) }
    var business by remember { mutableStateOf(MonthReport.fmtRate(s.businessRate)) }
    val c = parseKm(commute)
    val b = parseKm(business)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("単価・氏名の設定") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("氏名（レポートに載せる）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(commute, { commute = it }, label = { Text("通勤 1km あたり（円）") },
                    singleLine = true, isError = c == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(business, { business = it }, label = { Text("業務 1km あたり（円）") },
                    singleLine = true, isError = b == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("単価を変えると、過去の月の金額も新しい単価で計算し直されます。",
                    color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = c != null && b != null, onClick = {
                Store.updateSettings(Store.settings.value.copy(
                    name = name.trim(), commuteRate = c ?: s.commuteRate, businessRate = b ?: s.businessRate,
                ))
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
