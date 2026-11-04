package app.mileage.log.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun fmtKm(km: Double): String = String.format(Locale.JAPAN, "%,.1f", km)
fun fmtKm2(km: Double): String = String.format(Locale.JAPAN, "%,.2f", km)

fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.JAPAN, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
}

private val dayFmt = SimpleDateFormat("M月d日(E)", Locale.JAPAN)
private val timeFmt = SimpleDateFormat("H:mm", Locale.JAPAN)
private val fullFmt = SimpleDateFormat("yyyy年M月d日 H:mm", Locale.JAPAN)
private val monthFmt = SimpleDateFormat("yyyy年M月", Locale.JAPAN)

fun fmtDay(t: Long): String = dayFmt.format(Date(t))
fun fmtTime(t: Long): String = timeFmt.format(Date(t))
fun fmtFull(t: Long): String = fullFmt.format(Date(t))
fun fmtMonth(t: Long): String = monthFmt.format(Date(t))

/** 年×100+月（例：202611）。月ごとのまとめに使う */
fun monthKey(t: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1
}

fun yearOf(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR)

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
