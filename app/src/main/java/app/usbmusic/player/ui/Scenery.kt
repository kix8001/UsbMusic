package app.usbmusic.player.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * 景色のビジュアライザー（なめらかなグラデーションで描く）
 *  - トロピカル・サーフ：南の海で波に乗るサーファー
 *  - コースト・ハイウェイ／ダートトラック：車の後ろから見る道を、グレーのジムニーが走る
 * どれも曲の進みで空が変わる（曲の始め＝昼、半ばすぎ＝夕焼け、終わり＝夜）。
 * 1000×400 の座標で描き、表示欄いっぱいに広げる（はみ出す分は切る）。
 */

internal const val SCENE_SURF = 0
internal const val SCENE_COAST = 1
internal const val SCENE_DIRT = 2

private const val VW = 1000f
private const val VH = 400f
private const val PI_F = PI.toFloat()
private const val TAU = PI_F * 2f

// ---------------- 色 ----------------

private fun cc(hex: Long): Int = hex.toInt()
private fun ch(c: Int, s: Int) = (c shr s) and 0xFF

/** 2色を f の割合で混ぜる（f=0 で a、1 で b） */
private fun mixC(a: Int, b: Int, f: Float): Int {
    val g = f.coerceIn(0f, 1f)
    fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * g).toInt()
    return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
}

private fun alpha(c: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (c and 0xFFFFFF)

private class Key(
    val t: Float, val skyT: Int, val skyM: Int, val skyB: Int, val sea: Int, val seaD: Int,
    val sun: Int, val haze: Int, val dark: Int, val light: Float,
)

/** 時間帯ごとの色（曲の位置 0〜1） */
private val Keys = arrayOf(
    Key(0.00f, cc(0xFF2F7FD8), cc(0xFF62AEF0), cc(0xFFBFE8FF), cc(0xFF1AA3C8), cc(0xFF0B5E8C), cc(0xFFFFF8D0), cc(0xFFFFFFFF), cc(0xFF0F2F38), 1f),
    Key(0.40f, cc(0xFF3D84D6), cc(0xFF79B8EC), cc(0xFFD8F0FF), cc(0xFF1D9CC0), cc(0xFF0C5A86), cc(0xFFFFF4C0), cc(0xFFFFFFFF), cc(0xFF123038), 1f),
    Key(0.55f, cc(0xFF4A4F9C), cc(0xFFE7786A), cc(0xFFFFC46E), cc(0xFFC36A5E), cc(0xFF3C3A72), cc(0xFFFFD27A), cc(0xFFFFB070), cc(0xFF2A1830), 0.75f),
    Key(0.66f, cc(0xFF2B2D6E), cc(0xFFA14E7C), cc(0xFFF08A5C), cc(0xFF8A4A6A), cc(0xFF25265A), cc(0xFFFF9A5A), cc(0xFFFF8A6A), cc(0xFF1D1228), 0.5f),
    Key(0.78f, cc(0xFF0E1238), cc(0xFF2B2A62), cc(0xFF5A3E74), cc(0xFF262A5C), cc(0xFF0D1236), cc(0xFFFF8A5A), cc(0xFF8A6AA0), cc(0xFF0B0A1A), 0.25f),
    Key(1.00f, cc(0xFF040714), cc(0xFF0A1430), cc(0xFF16264A), cc(0xFF0C1A38), cc(0xFF040A1C), cc(0xFFFFFFFF), cc(0xFF5A7AB0), cc(0xFF03050C), 0.12f),
)

private class ScenePal {
    var skyT = 0; var skyM = 0; var skyB = 0; var sea = 0; var seaD = 0; var sun = 0; var haze = 0; var dark = 0
    var light = 1f
    var p = 0f

    fun set(prog: Float) {
        p = prog
        var i = 0
        while (i < Keys.size - 2 && prog > Keys[i + 1].t) i++
        val a = Keys[i]; val b = Keys[i + 1]
        val f = ((prog - a.t) / (b.t - a.t)).coerceIn(0f, 1f)
        skyT = mixC(a.skyT, b.skyT, f); skyM = mixC(a.skyM, b.skyM, f); skyB = mixC(a.skyB, b.skyB, f)
        sea = mixC(a.sea, b.sea, f); seaD = mixC(a.seaD, b.seaD, f); sun = mixC(a.sun, b.sun, f)
        haze = mixC(a.haze, b.haze, f); dark = mixC(a.dark, b.dark, f)
        light = a.light + (b.light - a.light) * f
    }

    /** 昼の色を、夕焼け色・夜の色へ寄せる */
    fun tint(col: Int): Int {
        val dusk = max(0f, 1f - abs(p - 0.6f) / 0.15f)
        return mixC(mixC(col, haze, 0.22f * dusk), dark, (1f - light) * 0.85f)
    }
}

// ---------------- 道のコース ----------------

private class Seg(val curve: Float, val y: Float, val i: Int)

private class Course(
    seed: Long, curveMax: Float, hillMax: Float,
    val grassA: Int, val grassB: Int, val sideA: Int, val sideB: Int,
    val roadA: Int, val roadB: Int, val rumbleA: Int, val rumbleB: Int, val lane: Int?, val sea: Boolean,
    val mtnFar: Int, val mtnNear: Int, val speed: Float,
) {
    val track: List<Seg>

    init {
        val list = ArrayList<Seg>()
        var y = 0f
        fun add(n: Int, curve: Float, hill: Float) {
            for (i in 0 until n) {
                val f = i.toFloat() / n
                val ease = if (f < 0.25f) f / 0.25f else if (f > 0.75f) (1 - f) / 0.25f else 1f
                y += hill / n
                list += Seg(curve * ease, y, list.size)
            }
        }
        var s = seed
        fun r(): Float { s = (s * 16807) % 2147483647; return s / 2147483647f }
        add(60, 0f, 0f)
        repeat(14) {
            val dir = if (r() > 0.5f) 1f else -1f
            add(50 + (r() * 80).toInt(), dir * (1.5f + r() * curveMax), (r() - 0.5f) * hillMax)
            add(25 + (r() * 40).toInt(), 0f, (r() - 0.5f) * hillMax * 0.6f)
        }
        add(80, 0f, -y)
        track = list
    }
}

private val Coast = Course(3, 3.5f, 2400f, cc(0xFF7DC25A), cc(0xFF6DB34C), cc(0xFFF1DFB0), cc(0xFFE6D29C),
    cc(0xFF6A6E74), cc(0xFF63676D), cc(0xFFD8323A), cc(0xFFF4F4F4), cc(0xFFF6F6F6), true, cc(0xFF8FB2CF), cc(0xFF5F8A7A), 1f)
private val Dirt = Course(17, 5.5f, 4200f, cc(0xFF5E8F3C), cc(0xFF527F33), cc(0xFF5E8F3C), cc(0xFF527F33),
    cc(0xFF9A7048), cc(0xFF8D6640), cc(0xFF6F4E2E), cc(0xFF7C5A36), null, false, cc(0xFF7F9FB6), cc(0xFF3F6A45), 0.75f)

private const val SEGL = 200f
private const val ROADW = 1000f
private const val CAMH = 380f
private const val DRAW = 160
private const val DEPTH = 170f
private const val HOR = 172f

// ---------------- まとめ役 ----------------

internal class SceneryState {
    private val audio = DotAudio()
    private val pal = ScenePal()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rnd = kotlin.random.Random(9)
    private var t = 0f
    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val condensedItalic = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)

    fun render(scope: DrawScope, kind: Int, dt: Float, playing: Boolean, progress: Float, bands16: FloatArray) {
        t += dt
        audio.update(bands16, dt)
        pal.set(progress.coerceIn(0f, 1f))
        scope.drawIntoCanvas { cv ->
            val nc = cv.nativeCanvas
            val w = scope.size.width; val h = scope.size.height
            val s = max(w / VW, h / VH)
            nc.save()
            nc.clipRect(0f, 0f, w, h)
            nc.translate((w - VW * s) / 2f, (h - VH * s) / 2f)
            nc.scale(s, s)
            when (kind) {
                SCENE_SURF -> surf(nc, dt)
                SCENE_COAST -> drive(nc, dt, playing, Coast, coastSt)
                else -> drive(nc, dt, playing, Dirt, dirtSt)
            }
            nc.restore()
        }
    }

    // ---------------- 道具 ----------------

    private fun reset(): Paint {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.shader = null; p.clearShadowLayer()
        p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0f; p.xfermode = null
        return p
    }
    private fun fill(c: Int): Paint { reset(); p.color = c; return p }
    private fun stroke(c: Int, w: Float, round: Boolean = false): Paint {
        reset(); p.style = Paint.Style.STROKE; p.color = c; p.strokeWidth = w
        if (round) p.strokeCap = Paint.Cap.ROUND
        return p
    }
    private fun vGrad(y0: Float, y1: Float, colors: IntArray, pos: FloatArray? = null): Paint {
        reset(); p.shader = LinearGradient(0f, y0, 0f, y1, colors, pos, Shader.TileMode.CLAMP); return p
    }
    private fun radial(x: Float, y: Float, r: Float, inner: Int, outer: Int): Paint {
        reset(); p.shader = RadialGradient(x, y, max(1f, r), inner, outer, Shader.TileMode.CLAMP); return p
    }
    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float): Path {
        path.reset(); path.addRoundRect(x, y, x + w, y + h, r, r, Path.Direction.CW); return path
    }
    private val oval = RectF()
    private fun ellipse(cv: Canvas, x: Float, y: Float, rx: Float, ry: Float, paint: Paint) {
        oval.set(x - rx, y - ry, x + rx, y + ry); cv.drawOval(oval, paint)
    }

    /** ヤシの木。x, y は根元 */
    private fun palm(cv: Canvas, x: Float, y: Float, h: Float, lean: Float, sway: Float, col: Int, fronds: Int = 8, lights: Float = 0f) {
        if (h < 2f) return
        val tx = x + lean * h + sway * h * 0.04f; val ty = y - h
        val sp = stroke(col, 1f, true)
        for (k in 0 until 14) {
            val s0 = k / 14f; val s1 = (k + 1) / 14f
            sp.strokeWidth = h * (0.05f - 0.025f * s0)
            cv.drawLine(x + lean * h * s0 * s0 + sway * h * 0.04f * s0 * s0, y - h * s0,
                x + lean * h * s1 * s1 + sway * h * 0.04f * s1 * s1, y - h * s1, sp)
        }
        val detail = h > 50f
        for (k in 0 until fronds) {
            val a = -PI_F / 2 + (k.toFloat() / (fronds - 1) - 0.5f) * 3.6f + sway * 0.05f * sin(k.toFloat())
            val len = h * (0.45f + 0.1f * sin(k * 2.1f))
            val ex = tx + cos(a) * len; val ey = ty + sin(a) * len * 0.5f + len * 0.35f
            val cx = tx + cos(a) * len * 0.6f; val cy = ty + sin(a) * len * 0.6f - len * 0.12f
            path.reset(); path.moveTo(tx, ty)
            path.quadTo(cx, cy - len * 0.08f, ex, ey)
            path.quadTo(cx, cy + len * 0.06f, tx, ty + 2f)
            cv.drawPath(path, fill(col))
            if (detail) {
                val lp = stroke(col, 1.2f)
                for (j in 1 until 9) {
                    val u = j / 9f
                    val bx = (1 - u) * (1 - u) * tx + 2 * (1 - u) * u * cx + u * u * ex
                    val by = (1 - u) * (1 - u) * ty + 2 * (1 - u) * u * (cy - len * 0.04f) + u * u * ey
                    val ln = len * 0.16f * (1 - u * 0.6f)
                    cv.drawLine(bx, by, bx + cos(a + 1.2f) * ln * 0.4f, by + ln, lp)
                    cv.drawLine(bx, by, bx + cos(a - 1.2f) * ln * 0.4f, by + ln * 0.8f, lp)
                }
            }
        }
        if (lights > 0f) {
            val hues = intArrayOf(cc(0xFFFFD36A), cc(0xFFFF7AA8), cc(0xFF7AE0FF), cc(0xFF9AFF8A))
            for (k in 0 until 7) {
                val s = 0.25f + k * 0.1f
                val lx = x + lean * h * s * s + sway * h * 0.04f * s * s + sin(k * 2f) * h * 0.03f; val ly = y - h * s
                val hc = hues[k % 4]
                cv.drawCircle(lx, ly, 12f, radial(lx, ly, 12f, alpha(hc, 0.6f * lights), alpha(hc, 0f)))
                cv.drawCircle(lx, ly, 3.2f, fill(alpha(hc, lights)))
            }
        }
    }

    // ---------------- トロピカル・サーフ ----------------

    private class Star(val x: Float, val y: Float, val r: Float, val p: Float)
    private class Cloud(var x: Float, val y: Float, val s: Float, val v: Float)
    private class Drop(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float = 0f)
    private class Bird(var x: Float, val y: Float, val v: Float, val p: Float)
    private val stars = List(140) { Star(rnd.nextFloat() * VW, rnd.nextFloat() * 190f, 0.6f + rnd.nextFloat() * 1.4f, rnd.nextFloat() * 6f) }
    private val clouds = List(6) { Cloud(rnd.nextFloat() * VW, 40f + rnd.nextFloat() * 90f, 0.6f + rnd.nextFloat() * 0.8f, 4f + rnd.nextFloat() * 6f) }
    private val spray = ArrayList<Drop>()
    private val birds = List(4) { Bird(rnd.nextFloat() * VW, 60f + rnd.nextFloat() * 60f, 20f + rnd.nextFloat() * 15f, rnd.nextFloat() * 6f) }
    private val cloudPuffs = floatArrayOf(0f, 0f, 22f, 24f, -8f, 28f, 52f, 0f, 22f, 26f, 8f, 20f, -20f, 6f, 16f, 74f, 6f, 16f)

    private fun cloud(cv: Canvas, x: Float, y: Float, s: Float, paint: Paint) {
        var k = 0
        while (k < cloudPuffs.size) { ellipse(cv, x + cloudPuffs[k] * s, y + cloudPuffs[k + 1] * s, cloudPuffs[k + 2] * s * 1.4f, cloudPuffs[k + 2] * s, paint); k += 3 }
    }

    private fun surf(cv: Canvas, dt: Float) {
        val P = pal; val prog = P.p; val hz = 205f
        // 空
        cv.drawRect(0f, 0f, VW, hz, vGrad(0f, hz, intArrayOf(P.skyT, P.skyM, P.skyB), floatArrayOf(0f, 0.6f, 1f)))
        val starA = max(0f, (prog - 0.68f) / 0.15f)
        if (starA > 0f) for (s in stars) {
            val tw = 0.55f + 0.45f * sin(t * 2f + s.p) + (if (audio.hat > 0.7f && s.p > 4f) 0.5f else 0f)
            cv.drawCircle(s.x, s.y, s.r, fill(alpha(0xFFFFFF, min(1f, starA) * min(1f, tw))))
        }
        // 太陽は昼は高く、夕方に水平線へ沈む
        val sunP = min(1f, prog / 0.7f)
        val sunX = 660f + sunP * 60f; val sunY = 50f + sunP * sunP * (hz - 30f); val sunR = 26f + sunP * 18f
        if (prog < 0.76f) {
            cv.drawRect(0f, 0f, VW, hz, radial(sunX, sunY, sunR * 5f, alpha(P.sun, 0.55f), alpha(P.sun, 0f)))
            cv.save(); cv.clipRect(0f, 0f, VW, hz); cv.drawCircle(sunX, sunY, sunR, fill(P.sun)); cv.restore()
        }
        // 月は夜に左から昇る
        val moonP = max(0f, (prog - 0.72f) / 0.28f)
        var moonX = 0f
        if (moonP > 0f) {
            moonX = 230f + moonP * 60f; val moonY = hz - 10f - moonP * 140f
            cv.drawRect(0f, 0f, VW, hz, radial(moonX, moonY, 90f, alpha(cc(0xFFDCE6FF), 0.35f * moonP), alpha(cc(0xFFDCE6FF), 0f)))
            val ma = min(1f, moonP * 3f)
            cv.drawCircle(moonX, moonY, 20f, fill(alpha(cc(0xFFF5F0DC), ma)))
            val crater = fill(alpha(cc(0xFFC8C3B4), ma * 0.5f))
            cv.drawCircle(moonX - 6f, moonY - 4f, 4f, crater); cv.drawCircle(moonX + 5f, moonY + 5f, 3f, crater); cv.drawCircle(moonX + 3f, moonY - 8f, 2f, crater)
        }
        // 雲
        val cloudCol = mixC(mixC(cc(0xFFFFFFFF), P.haze, 0.6f), P.skyT, 1f - P.light)
        val cp = fill(alpha(cloudCol, 0.8f * (0.4f + 0.6f * P.light)))
        for (cl in clouds) { cl.x += cl.v * dt; if (cl.x > VW + 150f) cl.x = -150f; cloud(cv, cl.x, cl.y, cl.s, cp) }
        // 遠くの島
        val isle = mixC(P.dark, P.skyB, 0.45f)
        path.reset(); path.moveTo(560f, hz); path.quadTo(600f, hz - 34f, 650f, hz - 30f); path.quadTo(700f, hz - 52f, 760f, hz - 22f); path.quadTo(800f, hz - 12f, 830f, hz); path.close()
        cv.drawPath(path, fill(isle))
        palm(cv, 640f, hz - 26f, 40f, 0.15f, sin(t), isle, 6)
        palm(cv, 700f, hz - 44f, 48f, -0.1f, sin(t + 1f), isle, 6)
        // 海
        cv.drawRect(0f, hz, VW, VH, vGrad(hz, VH, intArrayOf(mixC(P.sea, P.skyB, 0.35f), P.sea, P.seaD), floatArrayOf(0f, 0.35f, 1f)))
        val rippleCol = mixC(P.skyB, cc(0xFFFFFFFF), 0.4f)
        for (k in 0 until 40) {
            val y = hz + 3f + k * k * 0.12f
            val sp = stroke(alpha(rippleCol, 0.08f + k * 0.004f), 1f + k * 0.03f)
            path.reset()
            var x = -20f
            while (x <= VW + 20f) {
                val yy = y + sin(x * 0.02f + t * (1f + k * 0.05f) + k) * (0.6f + k * 0.08f)
                if (x == -20f) path.moveTo(x, yy) else path.lineTo(x, yy)
                x += 40f
            }
            cv.drawPath(path, sp)
        }
        // 太陽／月の照り返し
        val refX = if (prog < 0.76f) sunX else moonX
        val refOn = if (prog < 0.76f) 1f else moonP
        if (refOn > 0f) {
            val rc = alpha(if (prog < 0.76f) P.sun else cc(0xFFEBF0FF), 0.75f * refOn)
            val fp = fill(rc)
            val step = floor(t * 6f)
            for (k in 0 until 70) {
                val y = hz + 2f + (k / 70f).pow(1.6f) * 150f; val w = 8f + (y - hz) * 0.5f
                val x = refX + sin(k * 12.9f + step) * w
                if (sin(k * 7.3f + t * 9f) <= 0.2f - audio.treble * 0.5f) continue
                cv.drawRect(x - (3f + k * 0.2f), y, x + 3f + k * 0.2f, y + 1.4f + k * 0.02f, fp)
            }
        }
        // 崩れる波
        val amp = 30f + audio.bass * 26f
        val baseY = 330f; val peak = 260f
        fun waveY(x: Float): Float {
            val d = (x - peak) / 260f
            return baseY - amp * exp(-d * d * 1.4f) * (if (x < peak) 1f else 0.85f) - 8f * sin(x * 0.012f + t * 1.2f)
        }
        path.reset(); path.moveTo(-10f, VH)
        var wx = -10f
        while (wx <= VW + 10f) { path.lineTo(wx, waveY(wx)); wx += 10f }
        path.lineTo(VW + 10f, VH); path.close()
        cv.drawPath(path, vGrad(baseY - amp - 20f, VH, intArrayOf(mixC(P.sea, cc(0xFF78F0E6), 0.55f * P.light + 0.1f), P.sea, P.seaD), floatArrayOf(0f, 0.4f, 1f)))
        val streak = stroke(alpha(0xFFFFFF, 0.12f + 0.1f * P.light), 1.2f)
        for (k in 1 until 6) {
            path.reset()
            var x = 0f
            while (x <= VW) { val y = waveY(x) + k * 9f + sin(x * 0.03f - t * 2f + k) * 2f; if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y); x += 12f }
            cv.drawPath(path, streak)
        }
        // 白い波頭
        var fx = 0f
        while (fx <= VW) {
            val y = waveY(fx); val foam = max(0f, 1f - abs(fx - peak + 60f) / 220f)
            val n = (1 + foam * 5).toInt()
            val fpaint = fill(alpha(0xFFFFFF, (0.5f + foam * 0.5f) * (0.5f + 0.5f * P.light)))
            for (j in 0 until n) {
                cv.drawCircle(fx + sin(fx * 3.1f + j * 7f + t * 4f) * 3f, y - 1f + sin(fx * 1.7f + j + t * 5f) * 2f - j * 1.2f, 1.4f + foam * 2.2f, fpaint)
            }
            fx += 5f
        }
        // サーファー
        val surfX = peak + 140f + sin(t * 0.45f) * 160f
        val sy = waveY(surfX) + 18f - (if (audio.kick > 0.7f) 6f else 0f)
        val slope = (waveY(surfX + 5f) - waveY(surfX - 5f)) / 10f
        val ang = atan(slope) * 0.8f + sin(t * 0.45f) * 0.08f
        if (audio.newBeat) repeat(14) { spray += Drop(surfX - 30f, sy, -40f - rnd.nextFloat() * 80f, -40f - rnd.nextFloat() * 70f) }
        val it = spray.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.life += dt; d.x += d.vx * dt; d.y += d.vy * dt; d.vy += 160f * dt
            if (d.life > 0.8f) { it.remove(); continue }
            cv.drawCircle(d.x, d.y, 1.8f, fill(alpha(0xFFFFFF, (1f - d.life / 0.8f) * (0.5f + 0.5f * P.light))))
        }
        cv.save(); cv.translate(surfX, sy); cv.rotate(ang * 180f / PI_F)
        path.reset(); path.moveTo(-34f, 4f); path.quadTo(-80f, 10f, -130f, 2f)
        cv.drawPath(path, stroke(alpha(0xFFFFFF, 0.5f * (0.5f + 0.5f * P.light)), 3f))
        reset(); p.shader = LinearGradient(-36f, 0f, 36f, 0f, intArrayOf(cc(0xFFFF7A3A), cc(0xFFFFF2D0), cc(0xFFFF7A3A)), null, Shader.TileMode.CLAMP)
        p.alpha = ((0.6f + 0.4f * P.light) * 255).toInt()
        ellipse(cv, 0f, 2f, 38f, 5f, p)
        val body = mixC(cc(0xFF281E1C), P.dark, 1f - P.light)
        val crouch = if (audio.kick > 0.7f) 0f else 4f
        val lp = stroke(body, 5f, true)
        path.reset(); path.moveTo(-12f, 0f); path.lineTo(-6f, -14f + crouch); path.lineTo(0f, -16f + crouch); cv.drawPath(path, lp)
        path.reset(); path.moveTo(12f, 0f); path.lineTo(6f, -14f + crouch); path.lineTo(0f, -16f + crouch); cv.drawPath(path, lp)
        lp.strokeWidth = 6f; cv.drawLine(0f, -16f + crouch, 3f, -34f + crouch, lp)
        lp.strokeWidth = 3.5f
        cv.drawLine(2f, -30f + crouch, -18f, -26f + crouch + sin(t * 2f) * 3f, lp)
        cv.drawLine(3f, -30f + crouch, 22f, -34f + crouch - sin(t * 2f) * 3f, lp)
        cv.drawCircle(4f, -40f + crouch, 5f, fill(body))
        cv.restore()
        // 鳥
        if (prog < 0.75f) {
            val bp = stroke(alpha(mixC(cc(0xFF28323C), P.dark, 0.5f), 0.8f), 1.6f)
            for (b in birds) {
                b.x += b.v * dt; if (b.x > VW + 20f) b.x = -20f
                val f = sin(t * 6f + b.p) * 4f
                path.reset(); path.moveTo(b.x - 8f, b.y - f); path.quadTo(b.x - 3f, b.y - 3f, b.x, b.y); path.quadTo(b.x + 3f, b.y - 3f, b.x + 8f, b.y - f)
                cv.drawPath(path, bp)
            }
        }
        // 手前の砂浜とヤシ
        path.reset(); path.moveTo(0f, 352f); path.quadTo(120f, 340f, 230f, 372f); path.quadTo(260f, 390f, 280f, VH); path.lineTo(0f, VH); path.close()
        cv.drawPath(path, vGrad(340f, VH, intArrayOf(mixC(cc(0xFFF0D6A0), P.dark, 1f - P.light * 0.9f), mixC(cc(0xFFC8AA78), P.dark, 1f - P.light * 0.8f))))
        path.reset(); path.moveTo(0f, 352f + sin(t) * 2f); path.quadTo(120f, 340f + sin(t) * 3f, 230f, 372f)
        cv.drawPath(path, stroke(alpha(0xFFFFFF, 0.6f * (0.4f + 0.6f * P.light)), 2f))
        val pcol = mixC(cc(0xFF2E4A28), P.dark, 1f - P.light * 0.9f)
        val lights = min(1f, max(0f, (prog - 0.7f) / 0.1f))
        palm(cv, 70f, 395f, 230f, 0.28f, sin(t * 0.9f) * 3f, pcol, 9, lights * (0.75f + 0.25f * sin(t * 3f)))
        palm(cv, 170f, 400f, 170f, 0.12f, sin(t * 0.9f + 1f) * 3f, pcol, 8)
        palm(cv, 1010f, 405f, 260f, -0.25f, sin(t * 0.8f + 2f) * 3f, pcol, 9, lights * (0.75f + 0.25f * sin(t * 3f + 1f)))
    }

    // ---------------- ハイウェイ／ダートトラック ----------------

    private class Dust(var x: Float, var y: Float, val vx: Float, val vy: Float, var r: Float, var life: Float = 0f)
    private class RoadSt { var pos = 0f; var bg = 0f; var bounce = 0f; val dust = ArrayList<Dust>() }
    private val coastSt = RoadSt()
    private val dirtSt = RoadSt()
    private val rstars = List(90) { Star(rnd.nextFloat() * VW, rnd.nextFloat() * 150f, 0.6f + rnd.nextFloat() * 1.2f, rnd.nextFloat() * 6f) }
    private val pX = FloatArray(DRAW + 1)
    private val pY = FloatArray(DRAW + 1)
    private val pW = FloatArray(DRAW + 1)
    private val pS = FloatArray(DRAW + 1)
    private val pZ = FloatArray(DRAW + 1)
    private val pI = IntArray(DRAW + 1)
    private val visible = IntArray(DRAW)

    private fun quad(cv: Canvas, col: Int, x1: Float, y1: Float, w1: Float, x2: Float, y2: Float, w2: Float) {
        path.reset(); path.moveTo(x1 - w1, y1); path.lineTo(x1 + w1, y1); path.lineTo(x2 + w2, y2); path.lineTo(x2 - w2, y2); path.close()
        cv.drawPath(path, fill(col))
    }

    private fun pine(cv: Canvas, x: Float, y: Float, h: Float, col: Int) {
        val fp = fill(col)
        cv.drawRect(x - h * 0.03f, y - h * 0.2f, x + h * 0.03f, y, fp)
        for (k in 0 until 4) {
            val yy = y - h * 0.15f - k * h * 0.2f; val w = h * (0.3f - k * 0.06f)
            path.reset(); path.moveTo(x - w, yy); path.lineTo(x + w, yy); path.lineTo(x, yy - h * 0.32f); path.close()
            cv.drawPath(path, fp)
        }
    }

    private fun mountains(cv: Canvas, col: Int, amp: Float, freq: Float, shift: Float, base: Float) {
        path.reset(); path.moveTo(0f, HOR + 30f)
        var x = 0f
        while (x <= VW) { val u = x + shift; path.lineTo(x, base - amp * (0.6f + 0.4f * sin(u * freq) * sin(u * freq * 0.37f + 1f))); x += 8f }
        path.lineTo(VW, HOR + 30f); path.close()
        cv.drawPath(path, fill(col))
    }

    private fun drive(cv: Canvas, dt: Float, playing: Boolean, C: Course, st: RoadSt) {
        val P = pal; val prog = P.p
        val track = C.track; val n0 = track.size; val tl = n0 * SEGL
        val speed = if (playing) (2600f + audio.energy * 4200f) * C.speed else 0f
        st.pos = (st.pos + speed * dt) % tl
        val base = (st.pos / SEGL).toInt(); val pct = (st.pos % SEGL) / SEGL
        val bseg = track[base % n0]; val nseg = track[(base + 1) % n0]
        val camY = CAMH + bseg.y + (nseg.y - bseg.y) * pct
        st.bg += bseg.curve * speed * dt * 0.00009f
        val night = ((prog - 0.7f) / 0.12f).coerceIn(0f, 1f)
        // 空
        cv.drawRect(0f, 0f, VW, VH, vGrad(0f, HOR + 40f, intArrayOf(P.skyT, P.skyM, P.skyB), floatArrayOf(0f, 0.65f, 1f)))
        if (night > 0f) for (s in rstars) cv.drawCircle(s.x, s.y, s.r, fill(alpha(0xFFFFFF, night * (0.5f + 0.5f * sin(t * 2f + s.p)))))
        val sunP = min(1f, prog / 0.7f)
        val shift = ((st.bg * 60f) % 2000f + 2000f) % 2000f
        val sx = 760f - shift + (if (shift > 1200f) 2000f else 0f); val sy = 50f + sunP * sunP * (HOR - 30f)
        if (prog < 0.76f) {
            cv.drawRect(0f, 0f, VW, HOR + 20f, radial(sx, sy, 170f, alpha(P.sun, 0.8f), alpha(P.sun, 0f)))
            cv.drawCircle(sx, sy, 22f + sunP * 14f, fill(P.sun))
        }
        if (night > 0f) {
            val mx = ((240f - shift * 0.3f) % 1000f + 1000f) % 1000f
            cv.drawCircle(mx, 70f, 16f, fill(alpha(cc(0xFFF5F0DC), night)))
        }
        val cloudCol = mixC(mixC(cc(0xFFFFFFFF), P.haze, 0.5f), P.skyT, 1f - P.light)
        val cp = fill(alpha(cloudCol, 0.85f * (0.35f + 0.65f * P.light)))
        for (k in 0 until 7) {
            val cx = (((k * 230f - st.bg * 40f - t * 6f) % 1400f) + 1400f) % 1400f - 200f; val cy = 40f + (k * 37) % 70
            cloud(cv, cx, cy, 0.8f, cp)
        }
        mountains(cv, P.tint(mixC(C.mtnFar, P.skyB, 0.3f)), if (C.sea) 60f else 90f, 0.006f, -st.bg * 80f, HOR + 4f)
        mountains(cv, P.tint(C.mtnNear), if (C.sea) 34f else 55f, 0.011f, -st.bg * 160f, HOR + 10f)
        if (C.sea) cv.drawRect(0f, HOR, VW, VH, vGrad(HOR, VH, intArrayOf(P.tint(cc(0xFF5FC0E0)), P.tint(cc(0xFF1A7FB0)))))
        else cv.drawRect(0f, HOR, VW, VH, fill(P.tint(C.grassB)))
        // 道を手前から奥へ投影
        var x = 0f; var dx = -bseg.curve * pct
        for (n in 0..DRAW) {
            val sg = track[(base + n) % n0]
            val z = (n - pct) * SEGL + 60f; val s = DEPTH / z
            pX[n] = VW / 2f + x * s; pY[n] = HOR + (camY - sg.y) * s; pW[n] = ROADW * s; pS[n] = s; pZ[n] = z; pI[n] = sg.i
            x += dx; dx += sg.curve
        }
        fun fog(col: Int, z: Float): Int {
            val f = night * ((z - 1500f) / 9000f).coerceIn(0f, 1f)
            return mixC(P.tint(col), P.dark, f * 0.7f)
        }
        var maxY = VH; var nv = 0
        for (n in 0 until DRAW) {
            val by = pY[n + 1]
            if (by >= maxY || by >= pY[n]) continue
            val ax = pX[n]; val aw = pW[n]; val bx = pX[n + 1]; val bw = pW[n + 1]
            val alt = (pI[n] / 3) % 2 == 1
            val y1 = min(pY[n], maxY); val z = pZ[n]
            val grass = fog(if (alt) C.grassA else C.grassB, z)
            cv.drawRect(0f, by, max(0f, bx - bw * 1.5f), y1 + 1f, fill(grass))
            if (!C.sea) cv.drawRect(bx + bw * 1.5f, by, VW, y1 + 1f, fill(grass))
            quad(cv, fog(if (alt) C.sideA else C.sideB, z), ax + aw * 1.6f, y1, aw * 0.35f, bx + bw * 1.6f, by, bw * 0.35f)
            quad(cv, grass, ax - aw * 1.35f, y1, aw * 0.25f, bx - bw * 1.35f, by, bw * 0.25f)
            quad(cv, fog(if (alt) C.rumbleA else C.rumbleB, z), ax, y1, aw * 1.12f, bx, by, bw * 1.12f)
            quad(cv, fog(if (alt) C.roadA else C.roadB, z), ax, y1, aw, bx, by, bw)
            val lane = C.lane
            if (lane != null && alt) {
                val lc = fog(lane, z)
                quad(cv, lc, ax - aw * 0.33f, y1, aw * 0.025f, bx - bw * 0.33f, by, bw * 0.025f)
                quad(cv, lc, ax + aw * 0.33f, y1, aw * 0.025f, bx + bw * 0.33f, by, bw * 0.025f)
            }
            if (lane == null) {
                val rc = fog(cc(0xFF6E4B2B), z)
                quad(cv, rc, ax - aw * 0.42f, y1, aw * 0.08f, bx - bw * 0.42f, by, bw * 0.08f)
                quad(cv, rc, ax + aw * 0.42f, y1, aw * 0.08f, bx + bw * 0.42f, by, bw * 0.08f)
            }
            visible[nv++] = n
            maxY = by
        }
        // 夜はヘッドライトが前の路面を照らす
        if (night > 0f) {
            path.reset(); path.moveTo(VW / 2f - 140f, VH); path.lineTo(VW / 2f - 40f, HOR + 70f); path.lineTo(VW / 2f + 40f, HOR + 70f); path.lineTo(VW / 2f + 140f, VH); path.close()
            val hp = radial(VW / 2f, VH - 60f, 300f, alpha(cc(0xFFFFF0BE), 0.32f * night), alpha(cc(0xFFFFF0BE), 0f))
            hp.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            cv.drawPath(path, hp)
        }
        // 道ばたのもの（奥から手前へ）
        val treeCol = P.tint(cc(0xFF285A32))
        for (k in nv - 1 downTo 0) {
            val n = visible[k]; if (n < 1) continue
            val i = pI[n]; val ax = pX[n]; val ay = pY[n]; val aw = pW[n]; val s = pS[n]
            if (C.sea) {
                if (i % 6 == 0) palm(cv, ax - aw * 1.9f, ay, 1700f * s, 0.15f, sin(t + i), treeCol, 8)
                if (i % 6 == 3) palm(cv, ax + aw * 2.1f, ay, 1500f * s, -0.15f, sin(t + i), treeCol, 8)
                if (i % 12 == 9) {
                    val lx = ax - aw * 1.3f; val top = ay - 1500f * s; val hx = lx + 260f * s
                    val sp = stroke(P.tint(cc(0xFF5A5E64)), max(1f, 40f * s))
                    path.reset(); path.moveTo(lx, ay); path.lineTo(lx, top); path.lineTo(hx, top); cv.drawPath(path, sp)
                    if (night > 0f) cv.drawCircle(hx, top, max(1f, 260f * s), radial(hx, top, 260f * s, alpha(cc(0xFFFFDC96), night), alpha(cc(0xFFFFDC96), 0f)))
                }
                if (i % 50 == 25) {
                    val bw = 900f * s; val bh = 380f * s; val bx = ax - aw * 1.6f - bw; val byy = ay - 700f * s
                    val pole = fill(P.tint(cc(0xFF555555)))
                    cv.drawRect(bx + bw * 0.2f, byy + bh, bx + bw * 0.26f, ay, pole)
                    cv.drawRect(bx + bw * 0.74f, byy + bh, bx + bw * 0.8f, ay, pole)
                    reset(); p.shader = LinearGradient(bx, byy, bx + bw, byy + bh,
                        mixC(P.tint(cc(0xFFFF7A3A)), cc(0xFFFF7A3A), night * 0.8f), mixC(P.tint(cc(0xFFFFD25A)), cc(0xFFFFD25A), night * 0.8f), Shader.TileMode.CLAMP)
                    cv.drawRect(bx, byy, bx + bw, byy + bh, p)
                    reset(); p.color = cc(0xFFFFFFFF); p.typeface = condensedBold; p.textSize = bh * 0.55f; p.textAlign = Paint.Align.CENTER
                    val fm = p.fontMetrics
                    cv.drawText("mTunes FM", bx + bw / 2f, byy + bh / 2f - (fm.ascent + fm.descent) / 2f, p)
                }
            } else {
                if (i % 4 == 0) pine(cv, ax - aw * (1.8f + (i % 3) * 0.4f), ay, 1900f * s, P.tint(cc(0xFF1E4628)))
                if (i % 4 == 2) pine(cv, ax + aw * (1.8f + (i % 5) * 0.3f), ay, 2100f * s, P.tint(cc(0xFF224E2C)))
                if (i % 9 == 4) {
                    oval.set(ax + aw * 1.35f - 180f * s, ay - 60f * s - 110f * s, ax + aw * 1.35f + 180f * s, ay - 60f * s + 110f * s)
                    cv.drawArc(oval, 180f, 180f, true, fill(P.tint(cc(0xFF8A8A86))))
                }
                if (i % 20 == 10) {
                    val fp = fill(P.tint(cc(0xFF7A5434)))
                    for (sd in intArrayOf(-1, 1)) { val fx = ax + sd * aw * 1.22f; cv.drawRect(fx - 20f * s, ay - 380f * s, fx + 20f * s, ay, fp) }
                }
            }
        }
        // 土ぼこり（ダート）
        val curveNow = bseg.curve
        val carX = VW / 2f - curveNow * 6f
        if (!C.sea && playing) for (sd in intArrayOf(-1, 1)) {
            if (rnd.nextFloat() < 0.6f + audio.energy) st.dust += Dust(carX + sd * 88f, VH - 14f, (rnd.nextFloat() - 0.5f) * 40f + sd * 20f, -20f - rnd.nextFloat() * 30f, 6f + rnd.nextFloat() * 6f)
        }
        val dustCol = P.tint(cc(0xFFC4A076))
        val dit = st.dust.iterator()
        while (dit.hasNext()) {
            val d = dit.next()
            d.life += dt; d.x += d.vx * dt; d.y += d.vy * dt; d.r += dt * 40f
            if (d.life > 1.1f) { dit.remove(); continue }
            cv.drawCircle(d.x, d.y, d.r, fill(alpha(dustCol, 0.35f * (1f - d.life / 1.1f))))
        }
        // ジムニー
        val bump = if (C.sea || !playing) 0f else sin(t * 23f) * 1.8f + sin(t * 7.3f) * 1.5f
        st.bounce += ((if (audio.bass > 0.75f || audio.kick > 0.8f) -5f else 0f) - st.bounce) * min(1f, dt * 12f)
        val shake = if (playing) sin(t * 18f) * 0.8f else 0f
        jimny(cv, carX, 392f + st.bounce + bump + shake, -curveNow * 0.025f + bump * 0.003f, audio.kick > 0.6f, night, dt, playing)
    }

    // ---------------- グレーのジムニー（JB64）の後ろ姿 ----------------

    private val layerRect = RectF()
    private val jimnyFace = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private class Puff(var x: Float, var y: Float, var vx: Float, val vy: Float, var r: Float, var life: Float)
    private val puffs = ArrayList<Puff>()
    private var puffTimer = 2f

    /** テールランプの台形（外側の上端 topOuter、内側の上端 topInner、下端 bottom） */
    private fun lampPath(outer: Float, inner: Float, topOuter: Float, topInner: Float, bottom: Float) {
        path.reset(); path.moveTo(outer, topOuter); path.lineTo(inner, topInner); path.lineTo(inner, bottom); path.lineTo(outer, bottom); path.close()
    }

    private fun jimny(cv: Canvas, cx: Float, by: Float, lean: Float, brake: Boolean, night: Float, dt: Float, playing: Boolean) {
        val P = pal
        cv.save(); cv.translate(cx, by); cv.rotate(lean * 180f / PI_F)
        // 影
        ellipse(cv, 0f, -2f, 150f, 16f, radial(0f, -2f, 150f, alpha(0, 0.45f), alpha(0, 0f)))
        // 車体は別の層に描き、夕方・夜は車の形だけを暗くする
        layerRect.set(-130f, -220f, 130f, 4f)
        val layer = cv.saveLayer(layerRect, null)
        for (s in intArrayOf(-1, 1)) {
            val tx = s * 88f
            cv.drawPath(rr(tx - 13f, -62f, 26f, 62f, 6f), fill(cc(0xFF151617)))
            val tp = fill(cc(0xFF262729))
            for (k in 0 until 6) cv.drawRect(tx - 10f, -58f + k * 10, tx + 10f, -54f + k * 10, tp)
        }
        cv.drawRect(-70f, -48f, 70f, -34f, fill(cc(0xFF1C1D1F)))
        // マフラー（右下）
        cv.drawRect(64f, -42f, 78f, -36f, fill(cc(0xFF2A2B2D)))
        ellipse(cv, 78f, -39f, 4f, 4f, fill(cc(0xFF4A4B4D)))
        ellipse(cv, 78f, -39f, 2.4f, 2.4f, fill(cc(0xFF0A0A0A)))
        // ボディ（箱型、少し上すぼまり）
        path.reset(); path.moveTo(-108f, -70f); path.lineTo(-104f, -196f); path.quadTo(-102f, -206f, -92f, -207f)
        path.lineTo(92f, -207f); path.quadTo(102f, -206f, 104f, -196f); path.lineTo(108f, -70f); path.close()
        reset(); p.shader = LinearGradient(-110f, 0f, 110f, 0f,
            intArrayOf(cc(0xFF6C7074), cc(0xFF9A9EA2), cc(0xFFA7ABAF), cc(0xFF94989C), cc(0xFF63676B)), floatArrayOf(0f, 0.15f, 0.5f, 0.85f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p)
        cv.drawRect(-96f, -210f, 96f, -205f, fill(cc(0xFF7C8084)))
        cv.drawLine(-94f, -204f, 94f, -204f, stroke(alpha(0xFFFFFF, 0.35f), 1.2f))
        // リアガラス
        cv.drawPath(rr(-84f, -196f, 168f, 56f, 6f), vGrad(-196f, -140f, intArrayOf(cc(0xFF2A3138), cc(0xFF11161B))))
        path.reset(); path.moveTo(-84f, -196f); path.lineTo(-20f, -196f); path.lineTo(-60f, -140f); path.lineTo(-84f, -140f); path.close()
        cv.drawPath(path, fill(alpha(cc(0xFFA0C8E6), 0.18f)))
        cv.drawLine(0f, -142f, -50f, -150f, stroke(cc(0xFF0B0C0D), 3f))
        cv.drawRect(-18f, -203f, 18f, -199f, fill(cc(0xFF7A1A14)))
        // バックドアの合わせ目・右側のヒンジ・取っ手
        cv.drawPath(rr(-98f, -200f, 196f, 128f, 6f), stroke(alpha(0, 0.35f), 1.5f))
        val dark = fill(cc(0xFF2B2D30))
        cv.drawRect(94f, -186f, 102f, -170f, dark); cv.drawRect(94f, -104f, 102f, -88f, dark)
        cv.drawPath(rr(-94f, -124f, 26f, 8f, 3f), fill(cc(0xFF2B2D30)))
        // スペアタイヤ（黒いソフトカバー。写真のとおり大きく中央に）
        val stx = 0f; val sty = -114f; val sr = 60f
        reset(); p.shader = RadialGradient(stx - 18f, sty - 22f, sr * 1.25f, cc(0xFF2E2E30), cc(0xFF0C0C0D), Shader.TileMode.CLAMP)
        p.setShadowLayer(6f, 0f, 3f, alpha(0, 0.5f))
        cv.drawCircle(stx, sty, sr, p)
        cv.drawCircle(stx, sty, sr - 3f, stroke(cc(0xFF050505), 1.2f))
        oval.set(stx - sr + 6f, sty - sr + 6f, stx + sr - 6f, sty + sr - 6f)
        cv.drawArc(oval, 200f, 70f, false, stroke(alpha(0xFFFFFF, 0.10f), 2f, true))
        reset(); p.color = cc(0xFFF2F2F2); p.typeface = condensedBold; p.textSize = 8.5f; p.textAlign = Paint.Align.CENTER; p.letterSpacing = 0.06f
        cv.drawText("TOUGH & NEAT", stx + 16f, sty - 13f, p)
        reset(); p.color = cc(0xFFF7F7F7); p.typeface = jimnyFace; p.textSize = 27f; p.textAlign = Paint.Align.CENTER; p.letterSpacing = 0.02f
        var fm = p.fontMetrics
        cv.drawText("Jimny", stx + 1f, sty + 2f - (fm.ascent + fm.descent) / 2f, p)
        // リアバンパー（ボディと同じ幅）とテールランプ（外側が赤、内側が白いバックランプ）
        cv.drawPath(rr(-108f, -76f, 216f, 32f, 4f), vGrad(-76f, -44f, intArrayOf(cc(0xFF2C2D30), cc(0xFF141517))))
        cv.drawLine(-106f, -75f, 106f, -75f, stroke(alpha(0xFFFFFF, 0.12f), 1f))
        for (sd in intArrayOf(-1, 1)) {
            val outer = sd * 104f; val inner = sd * 50f
            val l = min(outer, inner); val r = max(outer, inner)
            // 台形：外側の辺が高く、内側へ向かって上の辺が下がる
            lampPath(outer + sd * 1.5f, inner - sd * 1.5f, -75f, -70f, -55f)
            cv.drawPath(path, fill(cc(0xFF0E0E10)))
            lampPath(outer, inner, -73.5f, -68.5f, -56f)
            cv.save(); cv.clipPath(path)
            val redOuterEnd = outer - sd * 20f
            val redInnerStart = inner + sd * 5f
            cv.drawRect(min(outer, redOuterEnd), -72f, max(outer, redOuterEnd), -56f, vGrad(-72f, -56f, intArrayOf(cc(0xFFD8322A), cc(0xFF8E1A14))))
            cv.drawRect(min(inner, redInnerStart), -72f, max(inner, redInnerStart), -56f, vGrad(-72f, -56f, intArrayOf(cc(0xFFD8322A), cc(0xFF8E1A14))))
            cv.drawRect(min(redOuterEnd, redInnerStart), -72f, max(redOuterEnd, redInnerStart), -56f, vGrad(-72f, -56f, intArrayOf(cc(0xFFF4F6F8), cc(0xFFB9BEC4))))
            val ln = stroke(alpha(0, 0.18f), 0.8f)
            for (k in 1 until 4) cv.drawLine(min(redOuterEnd, redInnerStart), -72f + k * 4f, max(redOuterEnd, redInnerStart), -72f + k * 4f, ln)
            cv.restore()
        }
        // ナンバープレート
        cv.drawPath(rr(-30f, -74f, 60f, 27f, 3f), fill(cc(0xFFF4F4EE)))
        cv.drawPath(rr(-30f, -74f, 60f, 27f, 3f), stroke(cc(0xFF3A5A3A), 1.2f))
        reset(); p.color = cc(0xFF2B4A2B); p.typeface = condensedBold; p.textSize = 20f; p.textAlign = Paint.Align.CENTER; p.letterSpacing = 0.1f
        fm = p.fontMetrics
        cv.drawText("902", 0f, -59f - (fm.ascent + fm.descent) / 2f, p)
        // 映り込み
        cv.drawRect(-108f, -210f, 108f, -70f, vGrad(-210f, -60f, intArrayOf(alpha(0xFFFFFF, 0.12f), alpha(0xFFFFFF, 0f))))
        // 夕方・夜は車の形だけを暗くする
        val dk = (1f - P.light) * 0.75f
        if (dk > 0f) {
            val dp = fill(alpha(P.dark, dk)); dp.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            cv.drawRect(layerRect, dp)
        }
        cv.restoreToCount(layer)
        // テールランプ（夜は点灯、拍でブレーキ）
        if (night > 0f || brake) {
            val on = if (brake) 1f else 0.6f * night
            for (sd in intArrayOf(-1, 1)) {
                val outer = sd * 104f; val inner = sd * 50f
                for ((a0, a1) in arrayOf(outer to outer - sd * 20f, inner to inner + sd * 5f)) {
                    val cxl = (a0 + a1) / 2f
                    cv.drawCircle(cxl, -64f, 30f, radial(cxl, -64f, 30f, alpha(cc(0xFFFF2A1A), 0.5f * on), alpha(cc(0xFFFF2A1A), 0f)))
                }
                lampPath(outer, inner, -73.5f, -68.5f, -56f)
                cv.save(); cv.clipPath(path)
                for ((a0, a1) in arrayOf(outer to outer - sd * 20f, inner to inner + sd * 5f)) cv.drawRect(min(a0, a1) - 2f, -76f, max(a0, a1) + 2f, -54f, fill(alpha(cc(0xFFFF3A2A), on)))
                cv.restore()
            }
            if (brake) {
                cv.drawCircle(0f, -201f, 26f, radial(0f, -201f, 26f, alpha(cc(0xFFFF3A2A), 0.5f), alpha(cc(0xFFFF3A2A), 0f)))
                cv.drawRect(-18f, -203f, 18f, -199f, fill(cc(0xFFFF3A2A)))
            }
        }
        // 排気ガス（ときどき、マフラーからふわっと出る）
        if (playing) {
            puffTimer -= dt
            val burst = puffTimer <= 0f || (audio.newBeat && audio.beat % 16 == 0)
            if (burst) {
                puffTimer = 3f + rnd.nextFloat() * 5f
                repeat(7 + rnd.nextInt(5)) { k ->
                    puffs += Puff(80f, -38f, 18f + rnd.nextFloat() * 30f, 4f + rnd.nextFloat() * 14f, 3f + rnd.nextFloat() * 3f, -k * 0.06f)
                }
            } else if (rnd.nextFloat() < dt * 2f) puffs += Puff(80f, -38f, 14f + rnd.nextFloat() * 10f, 4f + rnd.nextFloat() * 6f, 2f, 0f)
        }
        val smoke = mixC(cc(0xFFD8DADC), P.dark, (1f - P.light) * 0.6f)
        val pit = puffs.iterator()
        while (pit.hasNext()) {
            val q = pit.next()
            q.life += dt
            if (q.life < 0f) continue
            q.x += q.vx * dt; q.y += q.vy * dt; q.r += dt * 22f; q.vx *= 0.97f
            if (q.life > 1.6f) { pit.remove(); continue }
            val a = 0.32f * (1f - q.life / 1.6f) * min(1f, q.life * 8f)
            cv.drawCircle(q.x, q.y, q.r, radial(q.x, q.y, q.r, alpha(smoke, a), alpha(smoke, 0f)))
        }
        if (night > 0f) cv.drawPath(rr(-30f, -74f, 60f, 27f, 3f), fill(alpha(cc(0xFFF4F4EE), 0.25f * night)))
        cv.restore()
    }
}
