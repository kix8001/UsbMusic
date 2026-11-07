package app.usbmusic.player.ui

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/*
 * ドット絵ビジュアライザー（昔のカーステの有機EL表示のような、粗いドットの絵が音に合わせて動く）
 *
 * 160×70 ドットの升目に色番号を置いて絵を描き、最後に丸い点として画面に並べる。
 * 色番号：0=消灯 1=暗い 2=ふつう 3=明るい 4=緑（曲名） 5=琥珀 6=赤
 *         7=ピンク 8=紫 9=黄 10=オレンジ 11=青 12=白 13=暗いピンク 14=暗い紫 15=暗い緑 16=暗い青 17=暗い黄
 * 画面の割り振り：0〜7段目＝シーン名と小さなスペクトラム、9〜55段目＝絵、56〜69段目＝曲名と再生時間
 */

internal const val DW = 160
internal const val DH = 70
private const val PI_F = PI.toFloat()
private const val TAU = PI_F * 2f

private fun r(v: Float): Int = v.roundToInt()

// ---------------- 5×7 ドットフォント（シーン名・時間用） ----------------

private val Font5x7: Map<Char, IntArray> = mapOf(
    'A' to intArrayOf(14, 17, 17, 31, 17, 17, 17), 'B' to intArrayOf(30, 17, 17, 30, 17, 17, 30),
    'C' to intArrayOf(14, 17, 16, 16, 16, 17, 14), 'D' to intArrayOf(28, 18, 17, 17, 17, 18, 28),
    'E' to intArrayOf(31, 16, 16, 30, 16, 16, 31), 'F' to intArrayOf(31, 16, 16, 30, 16, 16, 16),
    'G' to intArrayOf(14, 17, 16, 23, 17, 17, 15), 'H' to intArrayOf(17, 17, 17, 31, 17, 17, 17),
    'I' to intArrayOf(14, 4, 4, 4, 4, 4, 14), 'J' to intArrayOf(7, 2, 2, 2, 2, 18, 12),
    'K' to intArrayOf(17, 18, 20, 24, 20, 18, 17), 'L' to intArrayOf(16, 16, 16, 16, 16, 16, 31),
    'M' to intArrayOf(17, 27, 21, 21, 17, 17, 17), 'N' to intArrayOf(17, 17, 25, 21, 19, 17, 17),
    'O' to intArrayOf(14, 17, 17, 17, 17, 17, 14), 'P' to intArrayOf(30, 17, 17, 30, 16, 16, 16),
    'Q' to intArrayOf(14, 17, 17, 17, 21, 18, 13), 'R' to intArrayOf(30, 17, 17, 30, 20, 18, 17),
    'S' to intArrayOf(15, 16, 16, 14, 1, 1, 30), 'T' to intArrayOf(31, 4, 4, 4, 4, 4, 4),
    'U' to intArrayOf(17, 17, 17, 17, 17, 17, 14), 'V' to intArrayOf(17, 17, 17, 17, 17, 10, 4),
    'W' to intArrayOf(17, 17, 17, 21, 21, 21, 10), 'X' to intArrayOf(17, 17, 10, 4, 10, 17, 17),
    'Y' to intArrayOf(17, 17, 17, 10, 4, 4, 4), 'Z' to intArrayOf(31, 1, 2, 4, 8, 16, 31),
    '0' to intArrayOf(14, 17, 19, 21, 25, 17, 14), '1' to intArrayOf(4, 12, 4, 4, 4, 4, 14),
    '2' to intArrayOf(14, 17, 1, 2, 4, 8, 31), '3' to intArrayOf(31, 2, 4, 2, 1, 17, 14),
    '4' to intArrayOf(2, 6, 10, 18, 31, 2, 2), '5' to intArrayOf(31, 16, 30, 1, 1, 17, 14),
    '6' to intArrayOf(6, 8, 16, 30, 17, 17, 14), '7' to intArrayOf(31, 1, 2, 4, 8, 8, 8),
    '8' to intArrayOf(14, 17, 17, 14, 17, 17, 14), '9' to intArrayOf(14, 17, 17, 15, 1, 2, 12),
    ':' to intArrayOf(0, 12, 12, 0, 12, 12, 0), '.' to intArrayOf(0, 0, 0, 0, 0, 12, 12),
    '-' to intArrayOf(0, 0, 0, 31, 0, 0, 0), '/' to intArrayOf(0, 1, 2, 4, 8, 16, 0),
    '♪' to intArrayOf(4, 6, 5, 4, 12, 28, 24),
)

// ---------------- ドットの升目と、そこに描く道具 ----------------

internal class DotBuf {
    val b = ByteArray(DW * DH)
    private var cy0 = 0
    private var cy1 = DH - 1
    private var cx0 = 0
    private var cx1 = DW - 1
    private val xs = FloatArray(16)

    fun clear() = b.fill(0)

    fun clip(y0: Int, y1: Int, x0: Int = 0, x1: Int = DW - 1) {
        cy0 = y0; cy1 = y1; cx0 = x0; cx1 = x1
    }

    fun setI(x: Int, y: Int, c: Int) {
        if (x < cx0 || x > cx1 || y < cy0 || y > cy1) return
        b[y * DW + x] = c.toByte()
    }

    fun set(x: Float, y: Float, c: Int) = setI(r(x), r(y), c)

    /** 網点の模様。0=べた 1=市松 2=まばら 3=横じま 4=ごくまばら */
    fun pat(x: Int, y: Int, p: Int): Boolean = when (p) {
        0 -> true
        1 -> ((x + y) and 1) == 0
        2 -> (x and 1) == 0 && (y and 1) == 0
        3 -> (y and 1) == 0
        else -> x % 3 == 0 && y % 3 == 0
    }

    fun fillRect(x: Float, y: Float, w: Float, h: Float, c: Int, p: Int = 0) {
        val xi = r(x); val yi = r(y); val wi = r(w); val hi = r(h)
        for (j in yi until yi + hi) for (i in xi until xi + wi) if (pat(i, j, p)) setI(i, j, c)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, c: Int) {
        line(x, y, x + w - 1, y, c); line(x, y + h - 1, x + w - 1, y + h - 1, c)
        line(x, y, x, y + h - 1, c); line(x + w - 1, y, x + w - 1, y + h - 1, c)
    }

    fun line(fx0: Float, fy0: Float, fx1: Float, fy1: Float, c: Int, dash: Int = 0) {
        var x0 = r(fx0); var y0 = r(fy0); val x1 = r(fx1); val y1 = r(fy1)
        val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy; var n = 0
        while (true) {
            if (dash == 0 || n % dash < dash - 1) setI(x0, y0, c)
            n++
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }

    /** pts は x, y の順に並べた頂点 */
    fun poly(pts: FloatArray, c: Int) {
        val n = pts.size / 2
        for (i in 0 until n) { val k = (i + 1) % n; line(pts[i * 2], pts[i * 2 + 1], pts[k * 2], pts[k * 2 + 1], c) }
    }

    fun fillPoly(pts: FloatArray, c: Int, p: Int = 0) {
        val n = pts.size / 2
        var top = Float.MAX_VALUE; var bot = -Float.MAX_VALUE
        for (i in 0 until n) { top = min(top, pts[i * 2 + 1]); bot = max(bot, pts[i * 2 + 1]) }
        for (y in kotlin.math.ceil(top).toInt()..floor(bot).toInt()) {
            var cnt = 0
            val fy = y.toFloat()
            for (i in 0 until n) {
                val k = (i + 1) % n
                val ax = pts[i * 2]; val ay = pts[i * 2 + 1]; val bx = pts[k * 2]; val by = pts[k * 2 + 1]
                if ((ay <= fy && by > fy) || (by <= fy && ay > fy)) {
                    if (cnt < xs.size) xs[cnt++] = ax + (fy - ay) / (by - ay) * (bx - ax)
                }
            }
            xs.sort(0, cnt)
            var k = 0
            while (k + 1 < cnt) {
                for (x in kotlin.math.ceil(xs[k]).toInt()..floor(xs[k + 1]).toInt()) if (pat(x, y, p)) setI(x, y, c)
                k += 2
            }
        }
    }

    fun fillEllipse(cx: Float, cy: Float, rx: Float, ry: Float, c: Int, p: Int = 0) {
        for (y in floor(cy - ry).toInt()..kotlin.math.ceil(cy + ry).toInt()) {
            for (x in floor(cx - rx).toInt()..kotlin.math.ceil(cx + rx).toInt()) {
                val dx = (x - cx) / rx; val dy = (y - cy) / ry
                if (dx * dx + dy * dy <= 1f && pat(x, y, p)) setI(x, y, c)
            }
        }
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, c: Int, from: Float = 0f, to: Float = TAU, dash: Int = 0) {
        val steps = max(12, r((rx + ry) * 4f))
        for (i in 0..steps) {
            if (dash != 0 && i % dash == dash - 1) continue
            val a = from + (to - from) * i / steps
            set(cx + cos(a) * rx, cy + sin(a) * ry, c)
        }
    }

    fun circle(cx: Float, cy: Float, rad: Float, c: Int, dash: Int = 0) = ellipse(cx, cy, rad, rad, c, 0f, TAU, dash)

    fun fillCircle(cx: Float, cy: Float, rad: Float, c: Int, p: Int = 0) = fillEllipse(cx, cy, rad + 0.3f, rad + 0.3f, c, p)

    fun text5(s: String, x: Float, y: Float, c: Int) {
        var xi = r(x); val yi = r(y)
        for (ch in s.uppercase()) {
            val g = Font5x7[ch]
            if (g != null) for (row in 0 until 7) for (k in 0 until 5) if (g[row] and (16 shr k) != 0) setI(xi + k, yi + row, c)
            xi += 6
        }
    }

    /** 文字で描いた小さな絵。map が -1 を返す文字は描かない */
    fun sprite(rows: Array<String>, x: Float, y: Float, flip: Boolean, map: (Char) -> Int) {
        val xi = r(x); val yi = r(y)
        for (j in rows.indices) {
            val row = rows[j]
            for (i in row.indices) {
                val c = map(row[if (flip) row.length - 1 - i else i])
                if (c >= 0) setI(xi + i, yi + j, c)
            }
        }
    }
}

// ---------------- 音の取り出し（拍を見つける） ----------------

internal class DotAudio {
    val bands = FloatArray(16)
    var bass = 0f; var mid = 0f; var treble = 0f; var energy = 0f
    var kick = 0f; var hat = 0f
    var beat = 0
    var newBeat = false
    private var sinceBeat = 10f
    private var bassAvg = 0f
    private var prevBass = 0f
    private var cool = 0f
    private var trebleAvg = 0f
    private var prevTreble = 0f

    /** 拍の位置（整数部が拍の数、小数部が拍の中の進み） */
    val phase: Float get() = beat + min(1f, sinceBeat / 0.5f)

    fun update(src: FloatArray, dt: Float) {
        for (i in 0 until 16) bands[i] = src[i]
        fun avg(a: Int, b: Int): Float { var s = 0f; for (i in a until b) s += bands[i]; return s / (b - a) }
        bass = avg(0, 4); mid = avg(4, 10); treble = avg(10, 16); energy = avg(0, 16)
        newBeat = false
        cool -= dt
        sinceBeat += dt
        // 低音が急に強くなったところを拍とみなす
        if (bass - prevBass > 0.05f && bass > bassAvg * 1.08f + 0.04f && cool <= 0f) {
            kick = 1f; newBeat = true; beat++; cool = 0.22f; sinceBeat = 0f
        } else kick *= exp(-dt * 5f)
        bassAvg += (bass - bassAvg) * min(1f, dt * 1.5f)
        prevBass = bass
        if (treble - prevTreble > 0.04f && treble > trebleAvg + 0.03f) hat = 1f else hat *= exp(-dt * 9f)
        trebleAvg += (treble - trebleAvg) * min(1f, dt * 2f)
        prevTreble = treble
    }
}

// ---------------- シーン ----------------

internal abstract class DotScene(val label: String) {
    abstract fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float)
}

/** クラブ：2台のターンテーブルとミキサー */
private class ClubScene : DotScene("CLUB") {
    private class Note(var x: Float, var y: Float, var life: Float)
    private val notes = ArrayList<Note>()
    private val rnd = Random(1)

    private fun deck(g: DotBuf, a: DotAudio, t: Float, cx: Float) {
        val top = floatArrayOf(cx - 30f, 52f, cx + 24f, 52f, cx + 30f, 20f, cx - 24f, 20f)
        g.fillPoly(top, 1, 2)
        g.poly(top, 2)
        g.line(cx - 30f, 54f, cx + 24f, 54f, 1)
        val px = cx + 1f; val py = 36f
        g.fillEllipse(px, py, 19f, 12f, 0)
        g.ellipse(px, py, 19f, 12f, 3)
        g.ellipse(px, py, 15f, 9.2f, 1, dash = 3)
        g.ellipse(px, py, 11f, 6.8f, 1, dash = 3)
        g.fillEllipse(px, py, 5f, 3.2f, 2)
        val th = t * 3.4f
        for (k in 0 until 7) {
            val an = th + k * 0.07f
            g.set(px + cos(an) * 13f, py + sin(an) * 8f, 3)
            g.set(px + cos(an + PI_F) * 17f, py + sin(an + PI_F) * 10.5f, 2)
        }
        g.set(px + cos(th) * 3.5f, py + sin(th) * 2.2f, 0)
        g.set(px, py, 3)
        // トーンアーム
        g.fillCircle(cx + 23f, 24f, 2f, 3)
        g.line(cx + 23f, 24f, cx + 22f, 33f, 3)
        g.line(cx + 22f, 33f, cx + 15f, 31f, 3)
        g.fillRect(cx + 12f, 30f, 3f, 2f, 3)
        g.fillRect(cx - 26f, 47f, 4f, 2f, if (a.energy > 0.02f) 3 else 1)
    }

    private fun meter(g: DotBuf, x: Float, lv: Float) {
        val n = 9
        val lit = r(lv * n)
        for (k in 0 until n) {
            val on = k < lit
            g.fillRect(x, 45f - k * 3, 3f, 2f, if (on) (if (k >= 7) 5 else if (k >= 5) 3 else 2) else 1, if (on) 0 else 2)
        }
    }

    private fun knob(g: DotBuf, x: Float, y: Float, v: Float) {
        g.fillCircle(x, y, 2f, 1)
        g.circle(x, y, 2.4f, 2)
        val an = -2.4f + v * 4.8f
        g.set(x + sin(an) * 2f, y - cos(an) * 2f, 3)
    }

    override fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float) {
        deck(g, a, t, 34f)
        deck(g, a, t, 126f)
        // ミキサー
        g.fillRect(65f, 15f, 31f, 39f, 0)
        g.rect(65f, 15f, 31f, 39f, 2)
        g.fillRect(66f, 16f, 29f, 2f, 1, 1)
        meter(g, 69f, min(1f, a.bass * 1.15f))
        meter(g, 89f, min(1f, a.mid * 1.3f))
        knob(g, 80f, 21f, a.bass); knob(g, 80f, 29f, a.mid); knob(g, 80f, 37f, a.treble)
        g.line(71f, 50f, 89f, 50f, 1)
        val fx = 80f + r(sin(a.phase * PI_F / 2f) * 7f)
        g.fillRect(fx - 1f, 48f, 3f, 4f, 3)
        // 拍で音符が浮かぶ
        if (a.newBeat && a.beat % 2 == 0) notes += Note(8f + rnd.nextFloat() * 140f, 19f, 0f)
        val it = notes.iterator()
        while (it.hasNext()) {
            val n = it.next()
            n.life += dt; n.y -= dt * 10f
            if (n.life > 1.1f) { it.remove(); continue }
            g.text5("♪", n.x + sin(n.life * 6f) * 1.5f, n.y, if (n.life < 0.7f) 3 else 1)
        }
    }
}

/** ナイトドライブ：夜の高速道路を走る車の後ろ姿 */
private class DriveScene : DotScene("DRIVE") {
    private val rnd = Random(2)
    private var travel = 0f
    private class Star(val x: Float, val y: Float, val p: Float, val b: Boolean)
    private class Bld(val x: Int, val w: Int, val h: Int, val s: Float)
    private val stars = List(34) { Star(rnd.nextFloat() * 160f, 9f + rnd.nextFloat() * 15f, rnd.nextFloat(), rnd.nextFloat() > 0.7f) }
    private val blds = ArrayList<Bld>().apply {
        var x = -2
        while (x < 162) {
            val w = 5 + (rnd.nextFloat() * 9).toInt()
            val h = 3 + (rnd.nextFloat() * (if (abs(x - 80) < 30) 6 else 11)).toInt()
            add(Bld(x, w, h, rnd.nextFloat() * 100f)); x += w
        }
    }

    override fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float) {
        val hz = 29f
        travel += dt * (1.6f + a.energy * 4.5f)
        for (s in stars) if ((s.p + t * 0.4f) % 1f < 0.85f) g.set(s.x, s.y, if (s.b && a.hat > 0.5f) 3 else 1)
        g.fillCircle(136f, 16f, 6f, 2); g.fillCircle(132f, 16f, 6f, 3, 2); g.fillCircle(139f, 14f, 6f, 0)
        for (b in blds) {
            g.fillRect(b.x.toFloat(), hz - b.h, (b.w - 1).toFloat(), b.h.toFloat(), 0)
            g.line(b.x.toFloat(), hz - b.h, (b.x + b.w - 2).toFloat(), hz - b.h, 1)
            var wy = hz.toInt() - b.h + 2
            while (wy < hz.toInt()) {
                var wx = b.x + 1
                while (wx < b.x + b.w - 2) {
                    val h = sin(wx * 12.9898f + wy * 78.233f + floor(t * 0.5f + b.s) * 3.1f) * 437.585f
                    val v = h - floor(h)
                    if (v > 0.72f) g.setI(wx, wy, if (v > 0.93f) 5 else 1)
                    wx += 2
                }
                wy += 2
            }
        }
        g.line(0f, hz, 159f, hz, 2)
        for (y in hz.toInt() + 1..55) {
            val half = 2f + (y - hz) / 27f * 108f
            for (x in 0 until DW) if (abs(x - 80) > half && (x + y * 2) % 5 == 0) g.setI(x, y, 1)
        }
        g.line(78f, hz + 1f, -30f, 56f, 2); g.line(82f, hz + 1f, 190f, 56f, 2)
        // センターライン
        for (k in 0 until 10) {
            val z = 10f - ((k + travel) % 10f)
            if (z < 0.6f) continue
            val ya = hz + 27f / z; val yb = hz + 27f / (z + 0.5f)
            val wd = if (z < 2f) 1f else 0f
            g.fillRect(80f - wd, yb, 1f + wd, max(1f, ya - yb), if (z < 3f) 3 else 2)
        }
        // 街灯
        for (k in 0 until 5) {
            val z = 5f - ((k + travel * 0.5f) % 5f)
            if (z < 0.55f) continue
            val y = hz + 27f / z; val d = y - hz; val top = y - d * 1.1f
            for (sd in intArrayOf(-1, 1)) {
                val lx = 80f + sd * d * 4.6f
                g.line(lx, y, lx, top, 1)
                g.line(lx, top, lx - sd * d * 0.6f, top, 1)
                val big = d > 12f
                g.fillRect(lx - sd * d * 0.6f - (if (big) 1f else 0f), top + 1f, if (big) 2f else 1f, 1f, 5)
            }
        }
        // 車（後ろ姿）
        val cy = 43f - (if (a.kick > 0.7f) 1f else 0f); val x = 80f
        g.fillRect(x - 13f, cy + 3f, 27f, 7f, 0)
        g.line(x - 8f, cy, x + 8f, cy, 2); g.line(x - 8f, cy, x - 11f, cy + 3f, 2); g.line(x + 8f, cy, x + 11f, cy + 3f, 2)
        g.fillRect(x - 8f, cy + 1f, 17f, 2f, 1, 1)
        g.rect(x - 13f, cy + 3f, 27f, 7f, 2)
        val brake = a.kick > 0.6f
        g.fillRect(x - 12f, cy + 5f, 4f, 2f, if (brake) 6 else 5); g.fillRect(x + 9f, cy + 5f, 4f, 2f, if (brake) 6 else 5)
        g.fillRect(x - 3f, cy + 6f, 7f, 2f, 3)
        g.fillRect(x - 12f, cy + 10f, 4f, 2f, 1); g.fillRect(x + 9f, cy + 10f, 4f, 2f, 1)
    }
}

/** スピーカー：ウーファーが低音で動き、拍で音の輪が広がる */
private class SpeakerScene : DotScene("SPEAKER") {
    private class Wave(var r: Float)
    private val waves = ArrayList<Wave>()

    override fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float) {
        if (a.newBeat) waves += Wave(21f)
        val it = waves.iterator()
        while (it.hasNext()) {
            val w = it.next()
            w.r += dt * 34f
            if (w.r > 62f) { it.remove(); continue }
            for (cx in floatArrayOf(42f, 118f)) g.circle(cx, 33f, w.r, if (w.r < 38f) 2 else 1, 3)
        }
        for (cx in floatArrayOf(42f, 118f)) {
            val cy = 33f; val p = r(a.bass * 2.6f).toFloat()
            g.fillCircle(cx, cy, 21f, 0)
            g.circle(cx, cy, 21f, 2); g.fillCircle(cx, cy, 20f, 1, 4)
            for (k in 0 until 4) { val an = PI_F / 4 + k * PI_F / 2; g.set(cx + cos(an) * 19f, cy + sin(an) * 19f, 3) }
            g.fillCircle(cx, cy, 16f, 0)
            g.circle(cx, cy, 15f + p * 0.4f, 3)
            g.circle(cx, cy, 13f + p * 0.3f, 1)
            g.fillCircle(cx, cy, 11f, 1, 1)
            g.circle(cx, cy, 8f + p * 0.5f, 2, 2)
            g.fillCircle(cx, cy, 4f + p, 2)
            g.set(cx - 1f - p * 0.5f, cy - 2f - p * 0.5f, 3); g.set(cx - 2f - p * 0.5f, cy - 1f - p * 0.5f, 3)
        }
        g.fillRect(71f, 12f, 19f, 43f, 0)
        g.rect(71f, 12f, 19f, 43f, 1)
        g.fillCircle(80f, 20f, 5f, 1, 1); g.circle(80f, 20f, 5f, 2); g.fillCircle(80f, 20f, 2f, if (a.treble > 0.45f || a.hat > 0.6f) 3 else 2)
        val n = 8
        val lit = r(min(1f, a.energy * 1.4f) * n)
        for (k in 0 until n) {
            val on = k < lit
            g.fillRect(76f, 50f - k * 3, 9f, 2f, if (on) (if (k >= 6) 5 else 2) else 1, if (on) 0 else 2)
        }
    }
}

/** ライブ：スポットライトのステージと、跳ねる観客 */
private class LiveScene : DotScene("LIVE") {
    private val down = arrayOf(
        "...###...", "..#####..", "...###...", "....#....", "..#####..", ".#.###.#.", ".#.###.#.",
        "#..###..#", "...###...", "...#.#...", "...#.#...", "..#...#..", "..#...#..", ".##...##.",
    )
    private val up = arrayOf(
        "#.......#", ".#.###.#.", "..#####..", "...###...", "....#....", "..#####..", "...###...",
        "...###...", "...###...", "...#.#...", "..#...#..", "..#...#..", ".#.....#.", "##.....##",
    )
    private val lights = floatArrayOf(24f, 56f, 104f, 136f)

    override fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float) {
        for (x in 6 until 154) {
            g.setI(x, 10, 2); g.setI(x, 13, 2)
            if (x % 4 == 0) { g.setI(x, 11, 1); g.setI(x + 1, 12, 1) }
        }
        val strong = a.kick > 0.55f
        for (i in lights.indices) {
            val lx = lights[i]
            val ang = sin(t * (0.7f + a.energy * 0.6f) + i * 1.7f) * 0.55f
            val fx = lx + tan(ang) * 31f; val half = 9f + a.kick * 4f
            g.fillPoly(floatArrayOf(lx - 1f, 15f, lx + 1f, 15f, fx + half, 44f, fx - half, 44f), if (strong) 2 else 1, if (strong) 4 else 2)
            g.line(lx, 15f, fx - half, 44f, 1, 2); g.line(lx, 15f, fx + half, 44f, 1, 2)
            g.fillRect(lx - 1f, 14f, 3f, 2f, 3)
            g.fillEllipse(fx, 45f, half, 1.4f, 2, 1)
        }
        for (sx in floatArrayOf(3f, 143f)) {
            g.fillRect(sx, 25f, 14f, 19f, 0); g.rect(sx, 25f, 14f, 19f, 2)
            val p = if (a.bass > 0.6f) 1f else 0f
            g.circle(sx + 7f, 30f, 3f + p, 1); g.circle(sx + 7f, 38f, 4f + p, 2); g.set(sx + 7f, 38f, 3)
        }
        g.line(0f, 45f, 159f, 45f, 2); g.fillRect(0f, 46f, 160f, 1f, 1, 1)
        // マイクスタンドと歌い手
        val jump = a.kick > 0.6f
        g.line(70f, 44f, 70f, 31f, 1); g.line(70f, 31f, 73f, 29f, 2)
        g.sprite(if (jump) up else down, 76f, if (jump) 29f else 30f, false) { if (it == '#') 3 else -1 }
        // 観客
        for (i in 0 until 27) {
            val hx = (3 + i * 6 + (i % 2) * 2).toFloat()
            val j = r(a.kick * ((i * 7) % 3 + 1) * 1.2f)
            val hy = (50 - j + (if (i % 3 == 0) 1 else 0)).toFloat()
            g.fillRect(hx - 2f, hy + 2f, 5f, 56f - hy, 0)
            g.fillCircle(hx, hy, 2f, 2)
            g.fillRect(hx - 2f, hy + 3f, 5f, 56f - hy, 1, 1)
            if (a.kick > 0.45f && i % 2 == 0) { g.line(hx - 2f, hy + 2f, hx - 4f, hy - 3f, 2); g.line(hx + 2f, hy + 2f, hx + 4f, hy - 3f, 2) }
        }
    }
}

/** サンセット：しま模様の夕日、海、ヤシの木、鳥 */
private class SunsetScene : DotScene("SUNSET") {
    private val leaves = floatArrayOf(2.7f, 2.1f, 1.35f, 0.55f, -0.15f)

    override fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float) {
        val hz = 36; val sr = 19; val scx = 92; val scy = hz + 3
        val stripe = floor(t * 3f).toInt()
        for (y in scy - sr until hz) for (x in scx - sr..scx + sr) {
            val dx = x - scx; val dy = y - scy
            if (dx * dx + dy * dy > sr * sr) continue
            if (y > hz - 13 && (y + stripe) % 4 == 0) continue
            g.setI(x, y, if (y < hz - 10) 3 else 2)
        }
        for (k in 0 until 3) {
            val cx = ((t * 3f + k * 70f) % 220f) - 30f; val cy = 14f + k * 5
            g.line(cx, cy, cx + 18f + k * 4, cy, 1); g.line(cx + 5f, cy - 1f, cx + 13f, cy - 1f, 1)
        }
        g.line(0f, hz.toFloat(), 159f, hz.toFloat(), 2)
        val th = 1.25f - a.bass * 0.7f
        for (y in hz + 1..55) {
            val d = (y - hz) / 20f
            val rw = (1f - d) * 14f + 2f + a.energy * 4f
            for (x in 0 until DW) {
                val v = sin(x * 0.11f * (1.6f - d) + t * 1.6f + y * 1.7f) + sin(x * 0.05f - t * 0.9f + y * 0.8f)
                if (v > th) g.setI(x, y, if (d > 0.5f) 2 else 1)
                if (abs(x - scx) < rw && y % 2 == 0 && sin(x * 1.7f + t * 5f + y) > 0.2f) g.setI(x, y, 3)
            }
        }
        // ヤシの木
        val sway = sin(t * 1.3f) * 1.5f
        var tx = 0f; var ty = 0f
        var s = 0f
        while (s <= 1.0001f) {
            val x = 16f + 12f * s + sin(s * 3f) * 3f + sway * s * s; val y = 56f - 34f * s
            g.set(x - 1f, y, 0); g.set(x + 2f, y, 0)
            g.set(x, y, if (r(y) % 3 == 0) 1 else 2); g.set(x + 1f, y, 2)
            tx = x; ty = y
            s += 0.03f
        }
        for (i in leaves.indices) {
            val ang = leaves[i] + sin(t * 1.3f + i) * 0.07f
            val len = 15f + (i % 2) * 4
            var u = 0f
            var step = 0
            while (u <= 1f) {
                val lx = tx + cos(ang) * len * u; val ly = ty - sin(ang) * len * u * 0.7f + u * u * 9f
                g.set(lx, ly, 3)
                if (step % 3 == 0) { g.set(lx, ly + 1f, 2); g.set(lx, ly + 2f, 1) }
                u += 0.03f; step++
            }
        }
        // 鳥
        for (i in 0 until 3) {
            val bx = ((t * 9f + i * 60f) % 200f) - 20f; val by = 15f + i * 4 + sin(t + i) * 2f
            val up = floor(t * 4f + i).toInt() % 2 == 1
            g.set(bx - 2f, by + (if (up) -1f else 1f), 2); g.set(bx - 1f, by, 2)
            g.set(bx, by + (if (up) 1f else 0f), 2)
            g.set(bx + 1f, by, 2); g.set(bx + 2f, by + (if (up) -1f else 1f), 2)
        }
    }
}

/** シティ：16棟のビルの窓明かりがイコライザー */
private class CityScene : DotScene("CITY") {
    private val rnd = Random(6)
    private val hts = IntArray(16) { 22 + (rnd.nextFloat() * 18).toInt() }
    private val ant = BooleanArray(16) { rnd.nextFloat() > 0.6f }
    private val peak = FloatArray(16)
    private val hold = FloatArray(16)

    override fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float) {
        g.fillCircle(146f, 15f, 5f, 2); g.fillCircle(149f, 13f, 5f, 0)
        val tw = floor(t * 2f).toInt()
        for (i in 0 until 26) if ((i + tw) % 7 != 0) g.setI((i * 37) % 160, 10 + (i * 13) % 12, 1)
        for (i in 0 until 16) {
            val x = (i * 10 + 1).toFloat(); val h = hts[i]; val top = 50f - h
            g.fillRect(x, top, 9f, h.toFloat(), 0)
            g.rect(x, top, 9f, h + 1f, 1)
            val rows = (h - 3) / 3
            val lit = r(min(1f, a.bands[i] * 1.15f) * rows)
            if (lit >= peak[i]) { peak[i] = lit.toFloat(); hold[i] = 0.5f }
            else { hold[i] -= dt; if (hold[i] < 0f) peak[i] = max(lit.toFloat(), peak[i] - dt * 6f) }
            for (k in 0 until rows) {
                val wy = 48f - k * 3
                val on = k < lit
                val c = if (on) (if (k == lit - 1) 3 else 2) else 1
                g.fillRect(x + 2f, wy, 2f, 2f, c, if (on) 0 else 2)
                g.fillRect(x + 5f, wy, 2f, 2f, c, if (on) 0 else 2)
            }
            val pk = r(peak[i])
            if (pk in 1..rows) g.fillRect(x + 2f, 48f - (pk - 1) * 3 - 1f, 5f, 1f, 5)
            if (ant[i]) {
                g.line(x + 4f, top - 1f, x + 4f, top - 5f, 1)
                if (floor(t * 1.5f + i).toInt() % 2 == 1) g.set(x + 4f, top - 6f, 6)
            }
        }
        g.line(0f, 52f, 159f, 52f, 2)
        var px = 4
        while (px < 160) { g.line(px.toFloat(), 53f, px.toFloat(), 55f, 1); px += 16 }
        // 高架を走る電車
        val trx = ((t * 34f) % 420f) - 100f
        for (c in 0 until 3) {
            val cx = trx + c * 21
            g.fillRect(cx, 46f, 19f, 6f, 0); g.rect(cx, 46f, 19f, 6f, 2)
            var wx = cx + 2f
            while (wx < cx + 17f) { g.fillRect(wx, 48f, 2f, 1f, 5); wx += 3f }
        }
    }
}

/** スペース：輪のある惑星と、まわりを回る月 */
private class SpaceScene : DotScene("SPACE") {
    private val rnd = Random(7)
    private class Star(var x: Float, val y: Float, val z: Int)
    private class Shooter(var x: Float, var y: Float, var life: Float)
    private val stars = List(60) { Star(rnd.nextFloat() * 160f, 9f + rnd.nextFloat() * 47f, 1 + (rnd.nextFloat() * 3).toInt()) }
    private val shooters = ArrayList<Shooter>()
    private val cx = 80f
    private val cy = 32f
    private val tilt = -0.22f

    private fun ringX(ang: Float, rx: Float, ry: Float): Float = cx + cos(ang) * rx * cos(tilt) - sin(ang) * ry * sin(tilt)
    private fun ringY(ang: Float, rx: Float, ry: Float): Float = cy + cos(ang) * rx * sin(tilt) + sin(ang) * ry * cos(tilt)

    private fun drawRing(g: DotBuf, a: DotAudio, t: Float, front: Boolean) {
        for (i in 0 until 160) {
            val ang = i / 160f * TAU
            if ((sin(ang) >= 0f) != front) continue
            g.set(ringX(ang, 30f, 7.5f), ringY(ang, 30f, 7.5f), 2)
            if (i % 2 == 0) g.set(ringX(ang, 25f, 6.2f), ringY(ang, 25f, 6.2f), 1)
        }
        for (k in 0 until 24) {
            val ang = k / 24f * TAU + t * 0.5f
            if ((sin(ang) >= 0f) != front) continue
            g.set(ringX(ang, 27.5f, 6.9f), ringY(ang, 27.5f, 6.9f), if (a.energy > 0.35f) 3 else 2)
        }
    }

    override fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float) {
        for (s in stars) {
            s.x -= dt * s.z * 2.5f
            if (s.x < 0f) s.x += 160f
            val tw = a.hat > 0.6f && (s.x * 7).toInt() % 3 == 0
            g.set(s.x, s.y, if (tw) 3 else if (s.z == 3) 2 else 1)
        }
        if (a.newBeat && a.beat % 8 == 0) shooters += Shooter(60f + rnd.nextFloat() * 100f, 10f + rnd.nextFloat() * 8f, 0f)
        val it = shooters.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.life += dt; s.x -= dt * 70f; s.y += dt * 22f
            if (s.life > 0.9f) { it.remove(); continue }
            for (k in 0 until 8) g.set(s.x + k * 1.6f, s.y - k * 0.5f, if (k < 2) 3 else if (k < 5) 2 else 1)
        }
        val pr = 12
        val ma = t * 0.55f
        val mx = cx + cos(ma) * 50f; val my = cy + sin(ma) * 11f
        val moonBack = sin(ma) < 0f
        if (moonBack) { g.fillCircle(mx, my, 2f, 2); g.set(mx - 1f, my - 1f, 3) }
        drawRing(g, a, t, false)
        val icx = cx.toInt(); val icy = cy.toInt()
        for (y in -pr..pr) for (x in -pr..pr) {
            if (x * x + y * y > pr * pr + 2) continue
            val l = ((x + 5f) * (x + 5f) + (y + 5f) * (y + 5f)) / (pr * pr * 1.7f)
            var c = if (l < 0.3f) 3 else if (l < 0.85f) 2 else 1
            val p = if (l < 0.85f) 0 else if (l < 1.3f) 1 else 2
            if ((y + 40) % 5 == 0 && c >= 2) c = 1
            g.setI(icx + x, icy + y, if (g.pat(icx + x, icy + y, p)) c else 0)
        }
        drawRing(g, a, t, true)
        if (!moonBack) { g.fillCircle(mx, my, 2f, 2); g.set(mx - 1f, my - 1f, 3) }
    }
}

/** アクアリウム：魚、水草、宝箱と泡 */
private class AquaScene : DotScene("AQUA") {
    private val rnd = Random(8)
    private val fishA = arrayOf("....####...", "..##....#.#", ".#.@.....##", "..##....#.#", "....####...")
    private val fishB = arrayOf("....####...", "..##....##.", ".#.@.....##", "..##....##.", "....####...")
    private class Fish(var x: Float, val y: Float, val d: Int, val s: Float, val ph: Float)
    private class Bubble(val x: Float, var y: Float, val big: Boolean, val v: Float, val p: Float)
    private val fish = List(5) { i -> Fish(rnd.nextFloat() * 160f, 17f + i * 6 + rnd.nextFloat() * 3f, if (rnd.nextFloat() > 0.5f) 1 else -1, 8f + rnd.nextFloat() * 10f, rnd.nextFloat() * 6f) }
    private val bubbles = ArrayList<Bubble>()
    private val weeds = intArrayOf(8, 24, 22, 17, 58, 28, 96, 14, 146, 22)

    override fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float) {
        // 差し込む光
        val shimmer = floor(t * 4f).toInt()
        for (k in 0 until 4) {
            val x0 = 16f + k * 40 + sin(t * 0.3f + k) * 6f
            val reach = 13f + 30f * (0.6f + 0.4f * sin(t * 0.5f + k))
            for (y in 13 until 48) {
                if (y >= reach) break
                val xc = x0 + (y - 13) * 0.45f
                for (x in xc.toInt() until (xc + 5f).toInt()) if ((x + y + shimmer) % 5 == 0) g.setI(x, y, 1)
            }
        }
        val ripple = floor(t * 6f).toInt()
        for (x in 0 until DW) {
            g.setI(x, 11 + r(sin(x * 0.2f + t * 2f) * 0.8f), 2)
            if ((x + ripple) % 9 == 0) g.setI(x, 12, 1)
        }
        for (y in 51..55) for (x in 0 until DW) if (if (y == 51) (x + y) % 2 == 0 else (x * 7 + y * 3) % 5 == 0) g.setI(x, y, 1)
        // 水草
        for (i in 0 until weeds.size / 2) {
            val sx = weeds[i * 2]; val h = weeds[i * 2 + 1]
            for (j in 0 until h) {
                val y = (51 - j).toFloat(); val x = sx + sin(t * 1.6f + j * 0.25f + i) * (j.toFloat() / h) * 3f
                g.set(x, y, 2)
                if (j % 4 == 2) g.set(x + (if (j % 8 < 4) 1f else -1f), y - 1f, 1)
            }
        }
        // 宝箱
        val lift = r(a.kick * 3f).toFloat()
        g.fillRect(118f, 45f, 15f, 7f, 0); g.rect(118f, 45f, 15f, 7f, 2); g.line(125f, 45f, 125f, 51f, 1)
        if (lift > 0f) g.fillRect(120f, 46f - lift, 11f, max(1f, lift - 1f), 5, 1)
        g.fillRect(117f, 42f - lift, 17f, 3f, 0); g.rect(117f, 42f - lift, 17f, 3f, 3)
        // 泡
        if (a.newBeat) repeat(3) { bubbles += Bubble(122f + rnd.nextFloat() * 8f, 42f, rnd.nextFloat() > 0.5f, 12f + rnd.nextFloat() * 8f, rnd.nextFloat() * 6f) }
        if (a.hat > 0.9f && rnd.nextFloat() > 0.85f) bubbles += Bubble(40f + rnd.nextFloat() * 4f, 50f, false, 14f + rnd.nextFloat() * 6f, rnd.nextFloat() * 6f)
        val it = bubbles.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.y -= b.v * dt
            if (b.y < 14f) { it.remove(); continue }
            val bx = b.x + sin(t * 3f + b.p) * 1.2f
            if (!b.big) { g.set(bx, b.y - 1f, 2); g.set(bx - 1f, b.y, 2); g.set(bx + 1f, b.y, 2); g.set(bx, b.y + 1f, 2) }
            else { g.circle(bx, b.y, 2f, 2); g.set(bx - 1f, b.y - 1f, 3) }
        }
        // 魚
        val flap = 3f + a.energy * 6f
        for (f in fish) {
            f.x += f.d * f.s * (0.6f + a.energy * 1.6f) * dt
            if (f.x > 175f) f.x = -15f
            if (f.x < -15f) f.x = 175f
            val fr = if (floor(t * flap + f.ph).toInt() % 2 == 1) fishA else fishB
            g.sprite(fr, f.x, f.y + sin(t * 1.5f + f.ph), f.d > 0) { when (it) { '#' -> 2; '@' -> 3; else -> -1 } }
        }
    }
}

/** 明るい色 → 同じ系統の暗い色 */
private fun dimOf(c: Int): Int = when (c) {
    2 -> 1; 3 -> 2; 4 -> 15; 5 -> 17; 6 -> 13; 7 -> 13; 8 -> 14; 9 -> 17; 10 -> 17; 11 -> 16; 12 -> 3
    else -> c
}
private val Vivid = intArrayOf(7, 9, 4, 2, 10, 8, 6, 11, 12)

/** 花火：夜の川べりに上がる花火。火の粉は重さで落ち、川面に映る */
private class FireworksScene : DotScene("HANABI") {
    private val rnd = Random(11)
    private class Shell(val x: Float, var y: Float, var vy: Float, val c1: Int, val c2: Int, val big: Boolean)
    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val c: Int, var life: Float = 0f)
    private class Bld(val x: Int, val w: Int, val h: Int)
    private val shells = ArrayList<Shell>()
    private val sparks = ArrayList<Spark>()
    private var auto = 0f
    private val blds = ArrayList<Bld>().apply {
        var x = 0
        while (x < 160) { val w = 4 + (rnd.nextFloat() * 8).toInt(); add(Bld(x, w, 2 + (rnd.nextFloat() * 7).toInt())); x += w }
    }

    private fun launch() {
        shells += Shell(20f + rnd.nextFloat() * 120f, 46f, -(38f + rnd.nextFloat() * 14f),
            Vivid[rnd.nextInt(Vivid.size)], Vivid[rnd.nextInt(Vivid.size)], rnd.nextFloat() > 0.5f)
    }

    override fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float) {
        for (i in 0 until 30) g.setI((i * 53) % 160, 10 + (i * 17) % 20, 14)
        if (a.newBeat) launch()
        auto += dt
        if (a.energy > 0.05f && auto > 1.7f) { auto = 0f; launch() }
        val si = shells.iterator()
        while (si.hasNext()) {
            val s = si.next()
            s.y += s.vy * dt; s.vy += 40f * dt
            g.set(s.x, s.y, 9); g.set(s.x, s.y + 1f, 17); g.set(s.x, s.y + 2f, 17)
            if (s.vy > -6f) {
                val n = if (s.big) 40 else 24
                val sp = (if (s.big) 26f else 18f) * (0.8f + a.energy * 0.6f)
                for (k in 0 until n) {
                    val an = k / n.toFloat() * 2f * PI_F
                    sparks += Spark(s.x, s.y, cos(an) * sp, sin(an) * sp, if (k % 3 != 0) s.c1 else s.c2)
                }
                si.remove()
            }
        }
        val water = 47f
        val damp = exp(-dt * 0.9f)
        val it = sparks.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life += dt; p.x += p.vx * dt; p.y += p.vy * dt; p.vy += 14f * dt; p.vx *= damp; p.vy *= damp
            if (p.life > 1.6f || p.y > water) { it.remove(); continue }
            val c = when {
                p.life < 0.12f -> 12
                p.life < 0.7f -> p.c
                p.life < 1.2f -> dimOf(p.c)
                floor(p.life * 20f).toInt() % 2 == 1 -> dimOf(p.c)
                else -> 0
            }
            g.set(p.x, p.y, c)
            if (p.life < 0.9f) g.set(p.x - p.vx * 0.03f, p.y - p.vy * 0.03f, dimOf(p.c))
            val ry = 2f * water - p.y + 2f
            if (ry < 56f && ((r(p.x) + r(ry)) and 1) == 0) g.set(p.x + sin(ry + t * 4f), ry, dimOf(p.c))
        }
        for (b in blds) {
            val top = water - 1f - b.h
            g.fillRect(b.x.toFloat(), top, (b.w - 1).toFloat(), b.h.toFloat(), 0)
            g.line(b.x.toFloat(), top, (b.x + b.w - 2).toFloat(), top, 16)
            if (b.h > 4) g.set(b.x + 1f, water - b.h + 1f, 17)
        }
        g.line(0f, water, 159f, water, 16)
        var y = water.toInt() + 2
        while (y < 56) {
            var x = (y * 3) % 7
            while (x < 160) { g.set(x + sin(t * 2f + y) * 2f, y.toFloat(), 16); x += 9 }
            y += 2
        }
    }
}

/** アーケード：昔のゲームセンター風のシューティング。自機が拍で撃ち、敵がはじける */
private class ArcadeScene : DotScene("ARCADE") {
    private val rnd = Random(12)
    private val eA = arrayOf(".#####.", "##.#.##", "#######", ".#.#.#.", "#.....#")
    private val eB = arrayOf(".#####.", "##.#.##", "#######", "#.#.#.#", ".#...#.")
    private val ship = arrayOf("....#....", "...###...", ".#.###.#.", "#########", "##.#.#.##")
    private val rowColor = intArrayOf(7, 9, 4)
    private val down = FloatArray(24) // 0より大きい間は撃たれて消えている
    private class Shot(val x: Float, var y: Float)
    private class Boom(var x: Float, var y: Float, val vx: Float, val vy: Float, val c: Int, var life: Float = 0f)
    private class Star(val x: Float, var y: Float, val s: Float)
    private val shots = ArrayList<Shot>()
    private val booms = ArrayList<Boom>()
    private val stars = List(30) { Star(rnd.nextFloat() * 160f, 9f + rnd.nextFloat() * 46f, 4f + rnd.nextFloat() * 10f) }
    private var shipX = 80f
    private var target = 80f
    private var step = 0

    override fun draw(g: DotBuf, a: DotAudio, t: Float, dt: Float) {
        for (s in stars) { s.y += s.s * dt; if (s.y > 55f) s.y = 9f; g.set(s.x, s.y, if (s.s > 9f) 2 else 1) }
        if (a.newBeat) { step++; target = 14f + rnd.nextFloat() * 132f; shots += Shot(shipX, 46f) }
        val ox = 26f + r(sin(step * 0.5f) * 10f); val oy = 14f
        val frame = if (step % 2 == 1) eA else eB
        for (i in 0 until 24) {
            if (down[i] > 0f) { down[i] -= dt; continue }
            val col = rowColor[i / 8]
            g.sprite(frame, ox + (i % 8) * 14, oy + (i / 8) * 8, false) { if (it == '#') col else -1 }
        }
        shipX += (target - shipX) * min(1f, dt * 3f)
        val si = shots.iterator()
        while (si.hasNext()) {
            val s = si.next()
            s.y -= dt * 80f
            var hit = -1
            for (k in 0 until 24) {
                if (down[k] > 0f) continue
                val cx = ox + (k % 8) * 14; val cy = oy + (k / 8) * 8
                if (s.x >= cx && s.x <= cx + 6f && s.y >= cy && s.y <= cy + 4f) { hit = k; break }
            }
            if (hit >= 0) {
                down[hit] = 4f + rnd.nextFloat() * 4f
                val cx = ox + (hit % 8) * 14 + 3f; val cy = oy + (hit / 8) * 8 + 2f
                repeat(14) {
                    val an = rnd.nextFloat() * 2f * PI_F; val sp = 10f + rnd.nextFloat() * 25f
                    booms += Boom(cx, cy, cos(an) * sp, sin(an) * sp, Vivid[rnd.nextInt(6)])
                }
                si.remove(); continue
            }
            if (s.y < 9f) { si.remove(); continue }
            g.line(s.x, s.y, s.x, s.y + 2f, 9)
        }
        val bi = booms.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            b.life += dt; b.x += b.vx * dt; b.y += b.vy * dt
            if (b.life > 0.5f) { bi.remove(); continue }
            g.set(b.x, b.y, if (b.life < 0.25f) b.c else dimOf(b.c))
        }
        g.sprite(ship, shipX - 4f, 49f, false) { if (it == '#') 2 else -1 }
        g.set(shipX, 49f, 12)
        if (a.kick > 0.6f) { g.set(shipX - 1f, 54f, 10); g.set(shipX + 1f, 54f, 10); g.set(shipX, 55f, 9) }
        g.line(0f, 55f, 159f, 55f, 15)
    }
}

// ---------------- まとめ役（状態を持ち、毎フレーム描く） ----------------

internal const val DOT_SCENE_COUNT = 10

private val DotColors = intArrayOf(
    Color(0xFF0C1216).toArgb(), // 消灯
    Color(0xFF194D5C).toArgb(), // 暗い
    Color(0xFF46D6FF).toArgb(), // ふつう
    Color(0xFFD7FAFF).toArgb(), // 明るい
    Color(0xFF78FF8C).toArgb(), // 緑（曲名）
    Color(0xFFFFB03C).toArgb(), // 琥珀
    Color(0xFFFF4632).toArgb(), // 赤
    Color(0xFFFF5FB0).toArgb(), // ピンク
    Color(0xFFA86BFF).toArgb(), // 紫
    Color(0xFFFFE14A).toArgb(), // 黄
    Color(0xFFFF8A2A).toArgb(), // オレンジ
    Color(0xFF4682FF).toArgb(), // 青
    Color(0xFFFFFFFF).toArgb(), // 白
    Color(0xFF601E42).toArgb(), // 暗いピンク
    Color(0xFF3E2668).toArgb(), // 暗い紫
    Color(0xFF1E5C2C).toArgb(), // 暗い緑
    Color(0xFF182C68).toArgb(), // 暗い青
    Color(0xFF604A14).toArgb(), // 暗い黄
)

internal class DotState {
    private val buf = DotBuf()
    private val audio = DotAudio()
    private val scenes: List<DotScene> = listOf(
        ClubScene(), DriveScene(), SpeakerScene(), LiveScene(),
        SunsetScene(), CityScene(), SpaceScene(), AquaScene(),
        FireworksScene(), ArcadeScene(),
    )
    private var t = 0f
    private var capText: String? = null
    private var capW = 0
    private var capBits = BooleanArray(0)
    private var autoIndex = 0
    private var autoCaption: String? = null
    private val pts = Array(DotColors.size) { FloatArray(DW * DH * 2) }
    private val counts = IntArray(DotColors.size)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    /** index が -1 のときは「おまかせ」：曲が変わるたびに次のシーンへ */
    fun render(scope: DrawScope, index: Int, bands16: FloatArray, dt: Float, caption: String, time: String) {
        t += dt
        audio.update(bands16, dt)
        val sceneIndex = if (index >= 0) index else {
            if (autoCaption == null) autoCaption = caption
            else if (caption != autoCaption) { autoCaption = caption; autoIndex = (autoIndex + 1) % scenes.size }
            autoIndex
        }
        val scene = scenes[sceneIndex]
        buf.clear()
        buf.clip(9, 55)
        scene.draw(buf, audio, t, dt)
        chrome(scene.label, caption, time)
        scope.drawDots()
    }

    /** 共通の枠：左上にシーン名、右上に小さなスペクトラム、下に曲名（緑）と再生時間（琥珀） */
    private fun chrome(label: String, caption: String, time: String) {
        val g = buf
        g.clip(0, DH - 1)
        g.fillRect(0f, 0f, 70f, 8f, 0)
        g.text5(label, 1f, 0f, 3)
        for (i in 0 until 16) {
            val x = 126 + i * 2
            g.fillRect(x.toFloat(), 1f, 1f, 7f, 0)
            val h = r(audio.bands[i] * 7f)
            for (j in 0 until h) g.setI(x, 7 - j, if (j >= 5) 5 else 2)
        }
        g.fillRect(0f, 56f, DW.toFloat(), (DH - 56).toFloat(), 0)
        // 再生時間（5×7 の数字）
        val timeW = time.length * 6
        val timeX = DW - timeW
        g.text5(time, timeX.toFloat(), 60f, 5)
        // 曲名（PixelMplus12 のドット。日本語もそのまま出る）。入りきらなければ横に流す
        if (caption != capText) {
            capText = caption
            val (w, bits) = textToDots(caption)
            capW = w; capBits = bits
        }
        val areaR = timeX - 4
        g.clip(56, DH - 1, 0, areaR)
        val gap = 40
        val off = if (capW <= areaR) 0 else ((t * 14f) % (capW + gap)).toInt()
        drawCaption(1 - off)
        if (capW > areaR) drawCaption(1 - off + capW + gap)
        g.clip(0, DH - 1)
    }

    private fun drawCaption(x0: Int) {
        for (c in 0 until capW) {
            val x = x0 + c
            if (x < 0 || x >= DW) continue
            for (row in 0 until DOT_ROWS) if (capBits[row * capW + c]) buf.setI(x, 56 + row, 4)
        }
    }

    private fun DrawScope.drawDots() {
        drawRect(Color(0xFF020304))
        val pitch = min(size.width / DW, size.height / DH)
        val ox = (size.width - pitch * DW) / 2f
        val oy = (size.height - pitch * DH) / 2f
        counts.fill(0)
        val b = buf.b
        for (y in 0 until DH) {
            val py = oy + (y + 0.5f) * pitch
            for (x in 0 until DW) {
                val c = b[y * DW + x].toInt()
                val arr = pts[c]
                val n = counts[c]
                arr[n] = ox + (x + 0.5f) * pitch
                arr[n + 1] = py
                counts[c] = n + 2
            }
        }
        drawIntoCanvas { cv ->
            val nc = cv.nativeCanvas
            // 消えている点もうっすら見せて、表示器の升目らしくする
            paint.color = DotColors[0]
            paint.strokeWidth = pitch * 0.72f
            nc.drawPoints(pts[0], 0, counts[0], paint)
            for (c in 1 until DotColors.size) {
                if (counts[c] == 0) continue
                if (c >= 2) {
                    // 光のにじみ
                    paint.color = (DotColors[c] and 0x00FFFFFF) or (0x48 shl 24)
                    paint.strokeWidth = pitch * 1.5f
                    nc.drawPoints(pts[c], 0, counts[c], paint)
                }
                paint.color = DotColors[c]
                paint.strokeWidth = pitch * 0.8f
                nc.drawPoints(pts[c], 0, counts[c], paint)
            }
        }
    }
}
