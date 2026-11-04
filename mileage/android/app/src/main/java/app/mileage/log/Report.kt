package app.mileage.log

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 1か月分の集計。支給対象は通勤と業務だけ */
class MonthReport(val year: Int, val month: Int, allTrips: List<Trip>, val settings: Settings) {
    val trips: List<Trip> = allTrips.filter {
        val c = Calendar.getInstance().apply { timeInMillis = it.start }
        c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) + 1 == month
    }.sortedBy { it.start }

    /** 会社に出す走行（通勤・業務） */
    val claimTrips: List<Trip> = trips.filter { it.category == Category.COMMUTE || it.category == Category.BUSINESS }

    fun km(c: Category): Double = trips.filter { it.category == c }.sumOf { it.reportKm }.round1()
    fun count(c: Category): Int = trips.count { it.category == c }

    /** 区分ごとの支給額 = その月の合計距離 × 単価（円未満四捨五入） */
    fun amount(c: Category): Long = yen(km(c) * settings.rate(c))
    val totalAmount: Long get() = amount(Category.COMMUTE) + amount(Category.BUSINESS)

    fun tripAmount(t: Trip): Long = yen(t.reportKm * settings.rate(t.category))

    val title: String get() = "${year}年${month}月"

    /** 円未満を四捨五入（小数の誤差で 0.5 が 0.4999… になっても切り上がるよう少し足す） */
    private fun yen(v: Double): Long = Math.floor(v + 0.5 + 1e-9).toLong()

    private fun Double.round1() = Math.round(this * 10) / 10.0

    // ---------- CSV ----------

    fun csvFile(context: Context): File {
        val day = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN)
        val time = SimpleDateFormat("HH:mm", Locale.JAPAN)
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder()
        sb.append("車両使用 走行距離報告書,").append(q(title)).append('\n')
        if (settings.name.isNotBlank()) sb.append("氏名,").append(q(settings.name)).append('\n')
        sb.append('\n')
        sb.append("日付,出発,到着,区分,出発地,到着地,行き先,用件,距離(km),単価(円/km),金額(円)\n")
        for (t in claimTrips) {
            sb.append(day.format(Date(t.start))).append(',')
                .append(if (t.manual) "" else time.format(Date(t.start))).append(',')
                .append(if (t.manual) "" else time.format(Date(t.end))).append(',')
                .append(t.category.label).append(',')
                .append(q(t.startAddr)).append(',')
                .append(q(t.endAddr)).append(',')
                .append(q(t.destination)).append(',')
                .append(q(t.purpose)).append(',')
                .append(String.format(Locale.US, "%.1f", t.reportKm)).append(',')
                .append(fmtRate(settings.rate(t.category))).append(',')
                .append(tripAmount(t)).append('\n')
        }
        sb.append('\n')
        sb.append("区分,回数,距離(km),単価(円/km),支給額(円)\n")
        for (c in listOf(Category.COMMUTE, Category.BUSINESS)) {
            sb.append(c.label).append(',').append(count(c)).append(',')
                .append(String.format(Locale.US, "%.1f", km(c))).append(',')
                .append(fmtRate(settings.rate(c))).append(',')
                .append(amount(c)).append('\n')
        }
        sb.append("合計,,").append(String.format(Locale.US, "%.1f", km(Category.COMMUTE) + km(Category.BUSINESS)))
            .append(",,").append(totalAmount).append('\n')

        val f = File(dir(context), "走行距離_${year}年${month}月.csv")
        // Excel で文字化けしないよう、先頭に BOM を付ける
        f.writeText("﻿" + sb.toString())
        return f
    }

    // ---------- PDF ----------

    fun pdfFile(context: Context): File {
        val doc = PdfDocument()
        val pageW = 595; val pageH = 842 // A4（ポイント）
        val left = 36f; val right = pageW - 36f
        val text = Paint().apply { isAntiAlias = true; textSize = 8.5f; color = Color.BLACK }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val head = Paint(bold).apply { textSize = 16f }
        val line = Paint().apply { color = Color.rgb(180, 180, 180); strokeWidth = 0.5f }
        val dayFmt = SimpleDateFormat("M/d(E)", Locale.JAPAN)
        val timeFmt = SimpleDateFormat("H:mm", Locale.JAPAN)

        // 列：日付 / 時刻 / 区分 / 出発地→到着地 / 行き先・用件 / 距離 / 金額
        val xDate = left; val xTime = left + 50; val xCat = left + 105; val xRoute = left + 135
        val xDest = left + 330; val xKmR = right - 55; val xAmtR = right
        val rowH = 24f

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            val c = page!!.canvas
            y = 50f
            if (pageNo == 1) {
                c.drawText("車両使用 走行距離報告書", left, y, head)
                val t = Paint(bold).apply { textSize = 12f }
                c.drawText(title, right - t.measureText(title), y, t)
                y += 22f
                if (settings.name.isNotBlank()) {
                    c.drawText("氏名：${settings.name}", left, y, Paint(text).apply { textSize = 11f })
                    y += 18f
                }
                y += 6f
            }
            c.drawText("日付", xDate, y, bold)
            c.drawText("時刻", xTime, y, bold)
            c.drawText("区分", xCat, y, bold)
            c.drawText("出発地 → 到着地", xRoute, y, bold)
            c.drawText("行き先・用件", xDest, y, bold)
            drawRight(c, "距離(km)", xKmR, y, bold)
            drawRight(c, "金額(円)", xAmtR, y, bold)
            y += 5f
            c.drawLine(left, y, right, y, line)
            y += 12f
        }

        newPage()
        if (claimTrips.isEmpty()) {
            page!!.canvas.drawText("この月の通勤・業務の走行はありません", left, y, text)
            y += rowH
        }
        for (t in claimTrips) {
            if (y + rowH > pageH - 140) newPage()
            val c = page!!.canvas
            c.drawText(dayFmt.format(Date(t.start)), xDate, y, text)
            if (!t.manual) c.drawText("${timeFmt.format(Date(t.start))}-${timeFmt.format(Date(t.end))}", xTime, y, text)
            c.drawText(t.category.label, xCat, y, text)
            c.drawText(fit("${t.startAddr.ifBlank { "-" }} →", xDest - xRoute - 6, text), xRoute, y, text)
            c.drawText(fit(t.endAddr.ifBlank { "-" }, xDest - xRoute - 6, text), xRoute + 8, y + 10, text)
            c.drawText(fit(t.destination, xKmR - 50 - xDest, text), xDest, y, text)
            c.drawText(fit(t.purpose, xKmR - 50 - xDest, text), xDest, y + 10, text)
            drawRight(c, String.format(Locale.JAPAN, "%.1f", t.reportKm), xKmR, y, text)
            drawRight(c, String.format(Locale.JAPAN, "%,d", tripAmount(t)), xAmtR, y, text)
            y += rowH - 10
            c.drawLine(left, y, right, y, line)
            y += 10f
        }

        // 合計欄
        if (y + 120 > pageH - 30) newPage()
        val c = page!!.canvas
        y += 10f
        val sum = Paint(text).apply { textSize = 10.5f }
        val sumBold = Paint(bold).apply { textSize = 10.5f }
        val xs = floatArrayOf(left, left + 120, left + 200, left + 300)
        c.drawText("区分", xs[0], y, sumBold)
        drawRight(c, "回数", xs[1] + 30, y, sumBold)
        drawRight(c, "距離(km)", xs[2] + 50, y, sumBold)
        drawRight(c, "単価(円/km)", xs[3] + 60, y, sumBold)
        drawRight(c, "支給額(円)", right, y, sumBold)
        y += 5f; c.drawLine(left, y, right, y, line); y += 15f
        for (cat in listOf(Category.COMMUTE, Category.BUSINESS)) {
            c.drawText(cat.label, xs[0], y, sum)
            drawRight(c, count(cat).toString(), xs[1] + 30, y, sum)
            drawRight(c, String.format(Locale.JAPAN, "%.1f", km(cat)), xs[2] + 50, y, sum)
            drawRight(c, fmtRate(settings.rate(cat)), xs[3] + 60, y, sum)
            drawRight(c, String.format(Locale.JAPAN, "%,d", amount(cat)), right, y, sum)
            y += 18f
        }
        c.drawLine(left, y - 12, right, y - 12, line)
        y += 4f
        c.drawText("合計", xs[0], y, sumBold)
        drawRight(c, String.format(Locale.JAPAN, "%.1f", km(Category.COMMUTE) + km(Category.BUSINESS)), xs[2] + 50, y, sumBold)
        drawRight(c, "¥" + String.format(Locale.JAPAN, "%,d", totalAmount), right, y, Paint(sumBold).apply { textSize = 13f })

        val made = "作成日 " + SimpleDateFormat("yyyy年M月d日", Locale.JAPAN).format(Date())
        val small = Paint(text).apply { textSize = 7.5f; color = Color.GRAY }
        page!!.canvas.drawText(made, left, pageH - 30f, small)
        doc.finishPage(page!!)

        val f = File(dir(context), "走行距離_${year}年${month}月.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun drawRight(c: android.graphics.Canvas, s: String, x: Float, y: Float, p: Paint) {
        c.drawText(s, x - p.measureText(s), y, p)
    }

    /** 幅に収まらない文字列は末尾を「…」にする */
    private fun fit(s: String, width: Float, p: Paint): String {
        if (p.measureText(s) <= width) return s
        var n = s.length
        while (n > 0 && p.measureText(s.substring(0, n) + "…") > width) n--
        return s.substring(0, n) + "…"
    }

    private fun dir(context: Context): File =
        File(context.cacheDir, "reports").apply { mkdirs() }

    companion object {
        fun fmtRate(r: Double): String =
            if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.US, "%.1f", r)
    }
}
