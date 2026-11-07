package app.usbmusic.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * カセットデッキ（80年代の銀色の据え置きデッキを、グラデーション・光沢・影で描く）
 *
 * 1000×400 の座標で描き、表示欄に合わせて拡大縮小する。
 * リールの巻き具合は曲の再生位置で決まり（始めは左が太く、終わりは右が太い）、
 * テープを一定の速さで送るので、細い側のリールほど速く回る。
 */

private const val LW = 1000f
private const val LH = 400f
private const val PI_F = PI.toFloat()

/** カセットのラベル用の手書き風フォント（Yomogi / SIL Open Font License） */
internal object HandFont {
    @Volatile var typeface: Typeface? = null
    fun init(context: android.content.Context) {
        if (typeface == null) {
            typeface = runCatching { Typeface.createFromAsset(context.assets, "fonts/Yomogi-Regular.ttf") }.getOrNull()
        }
    }
}

private fun c(hex: Long): Int = hex.toInt()

/** 金属のヘアライン（細い横すじ）の模様。一度だけ作る */
private val hairBitmap: Bitmap by lazy {
    val bmp = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.6f }
    var s = 7L
    fun rnd(): Float { s = (s * 16807) % 2147483647; return s / 2147483647f }
    repeat(2600) {
        val y = rnd() * 256; val x = rnd() * 512; val l = 40 + rnd() * 260
        val a = ((0.04f + rnd() * 0.08f) * 255).toInt()
        p.color = if (rnd() > 0.5f) (a shl 24) or 0xFFFFFF else (a shl 24)
        cv.drawLine(x, y, x + l, y, p)
        if (x + l > 512) cv.drawLine(x - 512, y, x + l - 512, y, p)
    }
    bmp
}

internal class DeckState {
    private var aL = 0f
    private var aR = 0f
    private var count = 0f
    private var lastTitle: String? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val path2 = Path()
    private val hairShader by lazy { BitmapShader(hairBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val condensedReg = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    fun render(
        scope: DrawScope, dt: Float, playing: Boolean, progress: Float,
        levelL: Float, levelR: Float, peakL: Float, peakR: Float, title: String, artist: String,
    ) {
        if (title != lastTitle) { lastTitle = title; count = 0f }
        scope.drawIntoCanvas { cv ->
            val nc = cv.nativeCanvas
            val w = scope.size.width; val h = scope.size.height
            p.reset(); p.isAntiAlias = true
            p.color = c(0xFF0B0B0C); nc.drawRect(0f, 0f, w, h, p)
            val s = min(w / LW, h / LH)
            nc.save()
            nc.translate((w - LW * s) / 2f, (h - LH * s) / 2f)
            nc.scale(s, s)
            deck(nc, dt, playing, progress.coerceIn(0f, 1f), levelL, levelR, peakL, peakR, title, artist)
            nc.restore()
        }
    }

    // ---------------- 道具 ----------------

    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float): Path {
        path.reset(); path.addRoundRect(x, y, x + w, y + h, r, r, Path.Direction.CW); return path
    }

    private fun fillPaint(color: Int): Paint { p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = color; return p }
    private fun strokePaint(color: Int, width: Float): Paint { p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = width; return p }

    private fun vGrad(y0: Float, y1: Float, vararg colors: Int): Paint {
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(0f, y0, 0f, y1, colors, null, Shader.TileMode.CLAMP)
        return p
    }

    private fun brushed(cv: Canvas, x: Float, y: Float, w: Float, h: Float, colors: IntArray, r: Float, alpha: Float) {
        cv.save(); cv.clipPath(rr(x, y, w, h, r))
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(0f, y, 0f, y + h, colors, null, Shader.TileMode.CLAMP)
        cv.drawRect(x, y, x + w, y + h, p)
        p.reset(); p.shader = hairShader; p.alpha = (alpha * 255).toInt()
        cv.drawRect(x, y, x + w, y + h, p)
        cv.restore()
    }

    private fun bevel(cv: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, light: Int, dark: Int, lw: Float) {
        cv.save(); cv.clipPath(rr(x, y, w, h, r))
        cv.drawPath(rr(x + lw, y + lw, w, h, r), strokePaint(dark, lw * 2))
        cv.drawPath(rr(x - lw, y - lw, w, h, r), strokePaint(light, lw * 2))
        cv.restore()
    }

    private fun screw(cv: Canvas, x: Float, y: Float, r: Float, ang: Float = 0.5f) {
        p.reset(); p.isAntiAlias = true
        p.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.2f, intArrayOf(c(0xFFF4F4F4), c(0xFFA9ADB2), c(0xFF5D6166)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, r, p)
        cv.drawCircle(x, y, r, strokePaint(c(0x80000000), 1f))
        val sp = strokePaint(c(0xCC282828), r * 0.28f)
        for (a in floatArrayOf(ang, ang + PI_F / 2)) {
            cv.drawLine(x + cos(a) * r * 0.65f, y + sin(a) * r * 0.65f, x - cos(a) * r * 0.65f, y - sin(a) * r * 0.65f, sp)
        }
    }

    private fun knob(cv: Canvas, x: Float, y: Float, r: Float, ang: Float) {
        val sh = fillPaint(c(0xFF2A2C2F))
        sh.setShadowLayer(r * 0.35f, 0f, r * 0.12f, c(0x8C000000))
        cv.drawCircle(x, y, r, sh)
        p.clearShadowLayer()
        val lp = strokePaint(0, 1.2f)
        for (k in 0 until 90) {
            val a = k / 90f * 2 * PI_F
            lp.color = if (k % 2 == 1) c(0x59FFFFFF) else c(0x73000000)
            cv.drawLine(x + cos(a) * r * 0.86f, y + sin(a) * r * 0.86f, x + cos(a) * r, y + sin(a) * r, lp)
        }
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(x - r, y - r, x + r, y + r, intArrayOf(c(0xFFF2F3F4), c(0xFF9DA2A8), c(0xFF5F646A)), null, Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, r * 0.82f, p)
        val rp = strokePaint(c(0x1FFFFFFF), 0.6f)
        for (k in 2 until 10) cv.drawCircle(x, y, r * 0.08f * k, rp)
        val ip = strokePaint(c(0xFFE9A24A), maxOf(2f, r * 0.08f)); ip.strokeCap = Paint.Cap.ROUND
        cv.drawLine(x + cos(ang) * r * 0.35f, y + sin(ang) * r * 0.35f, x + cos(ang) * r * 0.72f, y + sin(ang) * r * 0.72f, ip)
    }

    private fun text(cv: Canvas, t: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, face: Typeface? = condensed, spacing: Float = 0.12f) {
        p.reset(); p.isAntiAlias = true
        p.typeface = face; p.textSize = size; p.color = color; p.textAlign = align; p.letterSpacing = spacing
        val fm = p.fontMetrics
        cv.drawText(t, x, y - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun button(cv: Canvas, x: Float, y: Float, w: Float, h: Float, sym: Int, pressed: Boolean) {
        val r = 4f; val dy = if (pressed) 2f else 0f
        cv.drawPath(rr(x - 1, y - 1, w + 2, h + 4, r + 1), fillPaint(c(0x8C000000)))
        val top = if (pressed) c(0xFFB9BCC0) else c(0xFFF1F2F3)
        val bot = if (pressed) c(0xFF8D9196) else c(0xFFB8BBBF)
        cv.drawPath(rr(x, y + dy, w, h, r), vGrad(y + dy, y + dy + h, top, bot))
        bevel(cv, x, y + dy, w, h, r, if (pressed) c(0x33FFFFFF) else c(0xCCFFFFFF), c(0x59000000), 1f)
        sym(cv, sym, x + w / 2, y + h / 2 + dy, h * 0.32f)
    }

    private fun sym(cv: Canvas, sym: Int, cx: Float, cy: Float, s: Float) {
        path2.reset()
        fun tri(x: Float, dir: Float) {
            path2.moveTo(x - s * 0.5f * dir, cy - s * 0.6f); path2.lineTo(x + s * 0.5f * dir, cy); path2.lineTo(x - s * 0.5f * dir, cy + s * 0.6f); path2.close()
        }
        when (sym) {
            SYM_PLAY -> tri(cx, 1f)
            SYM_FF -> { tri(cx - s * 0.45f, 1f); tri(cx + s * 0.45f, 1f) }
            SYM_REW -> { tri(cx - s * 0.45f, -1f); tri(cx + s * 0.45f, -1f) }
            SYM_STOP -> path2.addRect(cx - s * 0.55f, cy - s * 0.55f, cx + s * 0.55f, cy + s * 0.55f, Path.Direction.CW)
            SYM_PAUSE -> {
                path2.addRect(cx - s * 0.55f, cy - s * 0.6f, cx - s * 0.17f, cy + s * 0.6f, Path.Direction.CW)
                path2.addRect(cx + s * 0.17f, cy - s * 0.6f, cx + s * 0.55f, cy + s * 0.6f, Path.Direction.CW)
            }
            SYM_REC -> { cv.drawCircle(cx, cy, s * 0.55f, fillPaint(c(0xFFC8281E))); return }
            SYM_EJECT -> {
                path2.moveTo(cx - s * 0.6f, cy + s * 0.05f); path2.lineTo(cx, cy - s * 0.6f); path2.lineTo(cx + s * 0.6f, cy + s * 0.05f); path2.close()
                path2.addRect(cx - s * 0.6f, cy + s * 0.25f, cx + s * 0.6f, cy + s * 0.55f, Path.Direction.CW)
            }
            else -> return
        }
        cv.drawPath(path2, fillPaint(c(0xFF2A2C2F)))
    }

    private fun led(cv: Canvas, x: Float, y: Float, r: Float, on: Boolean, color: Int) {
        p.reset(); p.isAntiAlias = true
        p.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.3f,
            intArrayOf(if (on) c(0xFFFFFFFF) else c(0xFF555555), if (on) color else c(0xFF2A2A2A), if (on) color else c(0xFF151515)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        if (on) p.setShadowLayer(r * 3f, 0f, 0f, color)
        cv.drawCircle(x, y, r, p)
        cv.drawCircle(x, y, r, strokePaint(c(0x99000000), 1f))
    }

    // ---------------- カセットテープ ----------------

    /** 戻り値は左右の巻いたテープの半径（カセットの幅を1とした値） */
    private fun cassette(cv: Canvas, x: Float, y: Float, w: Float, prog: Float, title: String, artist: String): Pair<Float, Float> {
        val h = w * 0.636f
        cv.save(); cv.translate(x, y)
        val body = vGrad(0f, h, c(0xFF3A3C42), c(0xFF1E1F23))
        body.setShadowLayer(w * 0.03f, 0f, w * 0.012f, c(0x99000000))
        cv.drawPath(rr(0f, 0f, w, h, w * 0.025f), body)
        p.clearShadowLayer()
        cv.drawPath(rr(w * 0.012f, w * 0.012f, w - w * 0.024f, h - w * 0.024f, w * 0.02f), strokePaint(c(0x1FFFFFFF), 1f))

        val hy = h * 0.47f; val hx1 = w * 0.295f; val hx2 = w * 0.705f; val rh = w * 0.05f; val rmax = w * 0.185f
        val rL = sqrt(rh * rh + (1 - prog) * (rmax * rmax - rh * rh))
        val rR = sqrt(rh * rh + prog * (rmax * rmax - rh * rh))
        val wx = w * 0.23f; val wy = h * 0.335f; val ww = w * 0.54f; val wh = h * 0.27f

        // 窓の中：巻かれたテープ
        cv.save(); cv.clipPath(rr(wx, wy, ww, wh, h * 0.035f))
        cv.drawRect(wx, wy, wx + ww, wy + wh, fillPaint(c(0xFF0B0B0D)))
        for ((px, r) in arrayOf(hx1 to rL, hx2 to rR)) {
            p.reset(); p.isAntiAlias = true
            p.shader = RadialGradient(px, hy, r, intArrayOf(c(0xFF5A3A26), c(0xFF3E2719), c(0xFF2A1A10)), floatArrayOf(min(rh / r, 0.8f), 0.85f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(px, hy, r, p)
            val ring = strokePaint(c(0x0FFFDCB4), 0.7f)
            var k = rh + 2f
            while (k < r) { cv.drawCircle(px, hy, k, ring); k += 2.4f }
            cv.drawArc(px - r, hy - r, px + r, hy + r, 198f, 90f, false, strokePaint(c(0x40FFD2AA), 1f))
        }
        val tape = strokePaint(c(0xFF3A2618), 1.6f)
        cv.drawLine(hx1 - rL * 0.2f, hy + rL, w * 0.2f, h, tape)
        cv.drawLine(hx2 + rR * 0.2f, hy + rR, w * 0.8f, h, tape)
        cv.restore()

        // ラベル（窓の部分をくり抜く）
        val lx = w * 0.045f; val ly = h * 0.055f; val lw = w * 0.91f; val lh = h * 0.63f
        path2.reset()
        path2.fillType = Path.FillType.EVEN_ODD
        path2.addRoundRect(lx, ly, lx + lw, ly + lh, w * 0.015f, w * 0.015f, Path.Direction.CW)
        path2.addRoundRect(wx - 1, wy - 1, wx + ww + 1, wy + wh + 1, h * 0.04f, h * 0.04f, Path.Direction.CW)
        cv.save(); cv.clipPath(path2)
        cv.drawRect(lx, ly, lx + lw, ly + lh, vGrad(ly, ly + lh, c(0xFFF4F0E6), c(0xFFE2DCCD)))
        val stripes = intArrayOf(c(0xFFE85A2A), c(0xFFF2A33A), c(0xFFF7D548))
        val sy0 = h * 0.29f; val sy1 = h * 0.655f; val sh = (sy1 - sy0) / stripes.size
        for (i in stripes.indices) cv.drawRect(lx, sy0 + i * sh, lx + lw, sy0 + (i + 1) * sh + 0.5f, fillPaint(stripes[i]))
        val rule = strokePaint(c(0x403C3228), 0.8f)
        for (yy in floatArrayOf(h * 0.155f, h * 0.235f)) cv.drawLine(lx + w * 0.1f, yy, lx + lw - w * 0.04f, yy, rule)
        p.reset(); p.isAntiAlias = true; p.typeface = condensed; p.textSize = h * 0.15f; p.color = c(0xFF2B2622)
        cv.drawText("A", lx + w * 0.025f, h * 0.235f, p)
        val hand = HandFont.typeface ?: Typeface.DEFAULT
        val maxW = lw - w * 0.15f
        fitText(cv, title.ifBlank { "　" }, lx + w * 0.11f, h * 0.145f, h * 0.095f, maxW, hand, c(0xFF1D3F8A))
        fitText(cv, artist, lx + w * 0.11f, h * 0.225f, h * 0.07f, maxW, hand, c(0xFF1D3F8A))
        p.reset(); p.isAntiAlias = true; p.typeface = condensed; p.textSize = h * 0.05f; p.color = c(0xD9FFFFFF); p.textAlign = Paint.Align.RIGHT
        cv.drawText("mTunes  C-60", lx + lw - w * 0.03f, sy1 - h * 0.025f, p)
        cv.restore()

        // 窓のふちと目盛り
        cv.drawPath(rr(wx, wy, ww, wh, h * 0.035f), strokePaint(c(0x99000000), 2f))
        cv.drawPath(rr(wx + 1.5f, wy + 1.5f, ww - 3, wh - 3, h * 0.03f), strokePaint(c(0x26FFFFFF), 1f))
        val tick = strokePaint(c(0x59FFFFFF), 0.8f)
        for (k in 0..6) { val xx = w * 0.42f + k * w * 0.027f; cv.drawLine(xx, wy + wh - 4, xx, wy + wh - (if (k % 3 != 0) 7 else 10), tick) }

        // ハブ（白い歯車）
        for ((px, ang) in arrayOf(hx1 to aL, hx2 to aR)) {
            cv.save(); cv.translate(px, hy); cv.rotate(ang * 180f / PI_F)
            p.reset(); p.isAntiAlias = true
            p.shader = RadialGradient(-rh * 0.3f, -rh * 0.3f, rh * 1.3f, c(0xFFFFFFFF), c(0xFFCFCFCF), Shader.TileMode.CLAMP)
            cv.drawCircle(0f, 0f, rh, p)
            cv.drawCircle(0f, 0f, rh * 0.62f, fillPaint(c(0xFF151515)))
            val tooth = fillPaint(c(0xFFE6E6E6))
            for (k in 0 until 6) { cv.save(); cv.rotate(k * 60f); cv.drawRect(-rh * 0.1f, -rh * 0.62f, rh * 0.1f, -rh * 0.32f, tooth); cv.restore() }
            cv.drawCircle(rh * 0.82f, 0f, rh * 0.08f, fillPaint(c(0xFF9A9A9A)))
            cv.restore()
        }
        cv.drawPath(rr(wx, wy, ww, wh, h * 0.035f), fillPaint(c(0x0DFFFFFF)))

        // 下の台形部分
        path2.reset(); path2.fillType = Path.FillType.WINDING
        path2.moveTo(w * 0.17f, h); path2.lineTo(w * 0.22f, h * 0.76f); path2.lineTo(w * 0.78f, h * 0.76f); path2.lineTo(w * 0.83f, h); path2.close()
        cv.drawPath(path2, fillPaint(c(0x40000000)))
        cv.drawPath(path2, strokePaint(c(0x24FFFFFF), 1f))
        val hole = fillPaint(c(0xFF050505))
        for (xx in floatArrayOf(0.3f, 0.7f)) cv.drawCircle(w * xx, h * 0.89f, w * 0.016f, hole)
        for (xx in floatArrayOf(0.25f, 0.73f)) cv.drawRect(w * xx, h * 0.84f, w * xx + w * 0.022f, h * 0.84f + w * 0.022f, hole)
        cv.drawRect(w * 0.45f, h * 0.86f, w * 0.55f, h * 0.95f, hole)
        for ((xx, yy) in arrayOf(0.05f to 0.08f, 0.95f to 0.08f, 0.05f to 0.92f, 0.95f to 0.92f, 0.5f to 0.79f)) screw(cv, w * xx, h * yy, w * 0.012f, 0.6f)
        // 映り込み
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(c(0x1AFFFFFF), c(0x05FFFFFF), 0), floatArrayOf(0f, 0.35f, 0.5f), Shader.TileMode.CLAMP)
        cv.drawPath(rr(0f, 0f, w, h, w * 0.025f), p)
        cv.restore()
        return (rL / w) to (rR / w)
    }

    /** 入りきらない文字は小さくし、それでも長ければ末尾を「…」にする */
    private fun fitText(cv: Canvas, t: String, x: Float, y: Float, size: Float, maxW: Float, face: Typeface, color: Int) {
        if (t.isEmpty()) return
        p.reset(); p.isAntiAlias = true; p.typeface = face; p.textSize = size; p.color = color
        var w = p.measureText(t)
        if (w > maxW) { p.textSize = size * maxOf(0.72f, maxW / w); w = p.measureText(t) }
        var s = t
        if (w > maxW) {
            while (s.length > 1 && p.measureText("$s…") > maxW) s = s.dropLast(1)
            s = "$s…"
        }
        cv.drawText(s, x, y, p)
    }

    // ---------------- テープカウンター（数字が回る） ----------------

    private fun counter(cv: Canvas, x: Float, y: Float, w: Float, h: Float, value: Float) {
        cv.drawPath(rr(x, y, w, h, 5f), fillPaint(c(0xFF101112)))
        bevel(cv, x, y, w, h, 5f, c(0x99000000), c(0xB3FFFFFF), 1.5f)
        val n = 4; val cw = (w - 16) / n
        for (i in 0 until n) {
            var place = 1f; repeat(n - 1 - i) { place *= 10f }
            val v = value / place
            val d = floor(v).toInt() % 10
            var frac = 0f
            if (i == n - 1) frac = v - floor(v)
            else { val lower = (value % place) / place; if (lower > 0.9f) frac = (lower - 0.9f) * 10f }
            val cx = x + 8 + i * cw
            cv.save(); cv.clipPath(rr(cx + 1, y + 5, cw - 2, h - 10, 2f))
            cv.drawRect(cx, y, cx + cw, y + h, fillPaint(c(0xFFF2F0EA)))
            val off = frac * (h - 10)
            text(cv, d.toString(), cx + cw / 2, y + h / 2 - off, h * 0.62f, c(0xFF151515), Paint.Align.CENTER, condensed, 0f)
            text(cv, ((d + 1) % 10).toString(), cx + cw / 2, y + h / 2 - off + (h - 10), h * 0.62f, c(0xFF151515), Paint.Align.CENTER, condensed, 0f)
            p.reset(); p.isAntiAlias = true
            p.shader = LinearGradient(0f, y + 5, 0f, y + h - 5, intArrayOf(c(0x8C000000), 0, 0, c(0x8C000000)), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(cx, y, cx + cw, y + h, p)
            cv.restore()
        }
    }

    // ---------------- デッキ全体 ----------------

    private fun deck(cv: Canvas, dt: Float, playing: Boolean, prog: Float, lvL: Float, lvR: Float, pkL: Float, pkR: Float, title: String, artist: String) {
        val ink = c(0xFF3B3E42)
        brushed(cv, 0f, 0f, LW, LH, intArrayOf(c(0xFFE1E3E5), c(0xFFC6C9CD), c(0xFFA9ADB2)), 0f, 0.9f)
        bevel(cv, 0f, 0f, LW, LH, 0f, c(0xE6FFFFFF), c(0x80000000), 2f)
        // 銘板
        text(cv, "mTunes", 40f, 38f, 30f, c(0xFF2B2D30), spacing = 0.02f)
        text(cv, "STEREO CASSETTE DECK", 150f, 40f, 16f, c(0xFF4B4E52), spacing = 0.2f)
        text(cv, "MT-808", 960f, 40f, 18f, c(0xFF4B4E52), Paint.Align.RIGHT, spacing = 0.15f)
        cv.drawRect(40f, 58f, 960f, 59f, fillPaint(c(0x40000000)))
        cv.drawRect(40f, 59f, 960f, 60f, fillPaint(c(0x99FFFFFF)))
        // 電源とイジェクト
        button(cv, 40f, 300f, 54f, 30f, SYM_NONE, true)
        text(cv, "POWER", 67f, 348f, 13f, ink, Paint.Align.CENTER)
        led(cv, 67f, 284f, 4f, true, c(0xFFFF4A3A))
        button(cv, 40f, 90f, 54f, 30f, SYM_EJECT, false)
        text(cv, "EJECT", 67f, 136f, 13f, ink, Paint.Align.CENTER)
        // カセットのふた
        val dx = 120f; val dy = 76f; val dw = 400f; val dh = 220f
        cv.drawPath(rr(dx, dy, dw, dh, 10f), fillPaint(c(0xFF16171A)))
        bevel(cv, dx, dy, dw, dh, 10f, c(0xB3000000), c(0x99FFFFFF), 2f)
        cv.save(); cv.clipPath(rr(dx + 12, dy + 12, dw - 24, dh - 24, 6f))
        cv.drawRect(dx, dy, dx + dw, dy + dh, fillPaint(c(0xFF0C0C0E)))
        val (rL, rR) = cassette(cv, dx + 40, dy + 18, 320f, prog, title, artist)
        cv.drawRect(dx, dy, dx + dw, dy + dh, vGrad(dy, dy + dh, c(0x59140E0A), c(0x8C0A0604)))
        path2.reset(); path2.fillType = Path.FillType.WINDING
        path2.moveTo(dx, dy); path2.lineTo(dx + 170, dy); path2.lineTo(dx + 60, dy + dh); path2.lineTo(dx, dy + dh); path2.close()
        cv.drawPath(path2, fillPaint(c(0x12FFFFFF)))
        cv.restore()
        // リールを回す（テープ速度は幅を1として毎秒0.476）
        if (playing) {
            aL = (aL + 0.476f / rL * dt) % (2 * PI_F)
            aR = (aR + 0.476f / rR * dt) % (2 * PI_F)
            count += 0.476f / rR * dt / (2 * PI_F) * 0.5f
        }
        // 操作ボタン
        val syms = intArrayOf(SYM_REW, SYM_PLAY, SYM_FF, SYM_STOP, SYM_PAUSE, SYM_REC)
        for (i in syms.indices) {
            val s = syms[i]; val bx = dx + i * 68
            val pressed = (s == SYM_PLAY && playing) || (s == SYM_PAUSE && !playing)
            button(cv, bx, 318f, 60f, 34f, s, pressed)
            if (s == SYM_PLAY) led(cv, bx + 30, 368f, 3.5f, playing, c(0xFF4CFF6A))
            if (s == SYM_PAUSE) led(cv, bx + 30, 368f, 3.5f, !playing, c(0xFFFFB030))
        }
        // 表示部（蛍光表示のピークメーター）
        val fx = 560f; val fy = 76f; val fw = 400f; val fh = 150f
        cv.drawPath(rr(fx, fy, fw, fh, 8f), fillPaint(c(0xFF050607)))
        bevel(cv, fx, fy, fw, fh, 8f, c(0x99000000), c(0xB3FFFFFF), 2f)
        val fl = c(0xFF7CF4FF); val flDim = c(0xFF0F2C31); val hot = c(0xFFFF6A50); val hotDim = c(0xFF3A1410)
        val dot = DotFont.typeface ?: Typeface.MONOSPACE
        val segs = 30; val sx0 = fx + 46; val sw = 10.6f
        val labels = arrayOf("-∞", "40", "30", "20", "10", "6", "3", "0", "+3", "+6")
        for (i in labels.indices) text(cv, labels[i], sx0 + (i / 9f) * (segs - 1) * sw + 4, fy + 22, 11f, if (i >= 8) c(0xFFFF9A7A) else fl, Paint.Align.CENTER, dot, 0f)
        for (row in 0..1) {
            val lv = if (row == 0) lvL else lvR; val pk = if (row == 0) pkL else pkR
            val yy = fy + 38 + row * 30
            text(cv, if (row == 0) "L" else "R", fx + 24, yy + 8, 14f, fl, Paint.Align.CENTER, dot, 0f)
            val n = (lv * segs).roundToInt(); val pkn = min(segs - 1, (pk * segs).roundToInt())
            for (k in 0 until segs) {
                val isHot = k >= 24; val on = k < n || (k == pkn && pkn > 0)
                val col = if (on) (if (isHot) hot else fl) else (if (isHot) hotDim else flDim)
                val sp = fillPaint(col)
                if (on) sp.setShadowLayer(8f, 0f, 0f, col)
                cv.drawRect(sx0 + k * sw, yy, sx0 + k * sw + sw - 3, yy + 16, sp)
            }
        }
        p.clearShadowLayer()
        var ix = fx + 20
        for ((t, on) in arrayOf("DOLBY B NR" to true, "NORMAL" to false, "CrO2" to true, "METAL" to false, "PLAY" to playing, "REC" to false)) {
            p.reset(); p.isAntiAlias = true; p.typeface = dot; p.textSize = 12f; p.color = if (on) fl else flDim
            if (on) p.setShadowLayer(6f, 0f, 0f, fl)
            val fm = p.fontMetrics
            cv.drawText(t, ix, fy + 126 - (fm.ascent + fm.descent) / 2f, p)
            ix += p.measureText(t) + 18
        }
        path2.reset(); path2.moveTo(fx, fy); path2.lineTo(fx + 160, fy); path2.lineTo(fx + 90, fy + fh); path2.lineTo(fx, fy + fh); path2.close()
        cv.drawPath(path2, fillPaint(c(0x0DFFFFFF)))
        // カウンター
        counter(cv, 560f, 252f, 132f, 46f, count)
        text(cv, "TAPE COUNTER", 626f, 314f, 12f, ink, Paint.Align.CENTER)
        button(cv, 702f, 262f, 42f, 26f, SYM_NONE, false)
        text(cv, "RESET", 723f, 300f, 11f, ink, Paint.Align.CENTER)
        // つまみ
        knob(cv, 830f, 300f, 38f, -0.6f + lvL * 0.4f)
        text(cv, "REC LEVEL", 830f, 356f, 12f, ink, Paint.Align.CENTER)
        knob(cv, 924f, 300f, 28f, -PI_F / 2)
        text(cv, "BALANCE", 924f, 356f, 12f, ink, Paint.Align.CENTER)
        // ヘッドホン端子
        cv.drawCircle(764f, 340f, 12f, fillPaint(c(0xFF1A1B1D)))
        cv.drawCircle(764f, 340f, 12f, strokePaint(c(0xFFD8DADD), 3f))
        cv.drawCircle(764f, 340f, 5f, fillPaint(c(0xFF000000)))
        text(cv, "PHONES", 764f, 366f, 11f, ink, Paint.Align.CENTER)
        for ((sx, sy) in arrayOf(14f to 14f, 986f to 14f, 14f to 386f, 986f to 386f)) screw(cv, sx, sy, 5f)
    }

    private companion object {
        const val SYM_NONE = 0
        const val SYM_PLAY = 1
        const val SYM_FF = 2
        const val SYM_REW = 3
        const val SYM_STOP = 4
        const val SYM_PAUSE = 5
        const val SYM_REC = 6
        const val SYM_EJECT = 7
    }
}
