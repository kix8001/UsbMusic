package app.usbmusic.player.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.usbmusic.player.playback.Spectrum
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class VisStyle(val id: String, val label: String, val desc: String) {
    OFF("off", "オフ", "表示しません"),
    BLOCKS("blocks", "カラーブロック", "虹色のブロックが跳ね、床に映り込みます"),
    RETRO("retro", "レトロLED", "昔のLEDグラフィックイコライザー風"),
    VU("vu", "アナログVU", "琥珀色に光るアナログ針メーター（L・R）"),
    VFD("vfd", "VFDスペクトラム", "昔のカーコンポの蛍光表示管風、奥行きのある立体イコライザー"),
    VFD3D("vfd3d", "VFDスペクトラム 3D", "台形の傾斜をきつくして、奥へ進んでいくようなVFD"),
    RETRO_LR_CENTER("retro_lr_center", "レトロLR センター", "中央から左（L）と右（R）へLEDが伸びます"),
    WAVE("wave", "レインボーウェーブ", "音の波が奥から手前へ流れる立体スペクトラム"),
    CIRCLE("circle", "サークル", "円形に広がるスペクトラムと波形"),
    WARP("warp", "ワープ", "激しくうねり回転しながら突き進む、光のトンネル"),
    LIGHTS("lights", "イルミネーション", "色が流れる電球が音に合わせて点灯します"),
    RIPPLE("ripple", "波紋", "しずくが落ちて波紋が広がり、重なって消えていきます"),
    RIPPLE_TOP("ripple_top", "カラー波紋", "真上から見た水面に、色とりどりの波紋が広がります"),
    FLICKER("flicker", "ゆらぐ電灯", "古い電球がチラついたり、消えかけたりします"),
    ;

    companion object {
        fun of(id: String?): VisStyle = entries.firstOrNull { it.id == id } ?: BLOCKS
        val cycle = entries.filter { it != OFF }
    }
}

/** 音の強さを、なめらかに上下する表示用の値に変える。各スタイルの動きの状態もここに持つ */
private class Meter {
    val level = FloatArray(Spectrum.BANDS)
    val peak = FloatArray(Spectrum.BANDS)
    private val peakHold = FloatArray(Spectrum.BANDS)
    val lr = FloatArray(2)
    val lrPeak = FloatArray(2)
    private val lrHold = FloatArray(2)
    var wave = FloatArray(Spectrum.WAVE)
    var energy = 0f      // 全体の音量（すばやく反応）
    var slowEnergy = 0f  // 全体の音量（ゆっくり反応）
    var bass = 0f
    var treble = 0f
    var dt = 0.016f
    private var lastNs = 0L

    // レインボーウェーブ用の履歴
    val history = ArrayDeque<FloatArray>()
    private var historyTimer = 0f

    val rnd = Random(7)

    fun update(now: Long, active: Boolean) {
        dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        val f = if (active) Spectrum.current() else null
        for (i in level.indices) {
            val target = f?.bands?.get(i) ?: 0f
            level[i] = if (target > level[i]) level[i] + (target - level[i]) * min(1f, dt * 30f)
            else max(target, level[i] - dt * 1.6f)
            if (level[i] >= peak[i]) {
                peak[i] = level[i]; peakHold[i] = 0.5f
            } else if (peakHold[i] > 0f) peakHold[i] -= dt
            else peak[i] = max(level[i], peak[i] - dt * 0.7f)
        }
        val targets = floatArrayOf(f?.left ?: 0f, f?.right ?: 0f)
        for (c in 0..1) {
            lr[c] = if (targets[c] > lr[c]) lr[c] + (targets[c] - lr[c]) * min(1f, dt * 25f)
            else max(targets[c], lr[c] - dt * 1.2f)
            if (lr[c] >= lrPeak[c]) {
                lrPeak[c] = lr[c]; lrHold[c] = 0.8f
            } else if (lrHold[c] > 0f) lrHold[c] -= dt
            else lrPeak[c] = max(lr[c], lrPeak[c] - dt * 0.5f)
        }
        if (f != null) wave = f.wave else for (i in wave.indices) wave[i] *= 0.85f

        val avg = level.average().toFloat()
        energy += (avg - energy) * min(1f, dt * 12f)
        slowEnergy += (avg - slowEnergy) * min(1f, dt * 0.8f)
        var b = 0f
        for (i in 0 until 8) b = max(b, level[i])
        bass += (b - bass) * min(1f, dt * 15f)
        var tr = 0f
        for (i in 30 until level.size) tr += level[i]
        treble += (tr / (level.size - 30) - treble) * min(1f, dt * 10f)

        historyTimer += dt
        if (historyTimer >= 0.05f) {
            historyTimer = 0f
            history.addLast(level.copyOf())
            while (history.size > 26) history.removeFirst()
        }
    }

    /** 帯域数を減らす */
    fun bands(src: FloatArray, n: Int): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) {
            val a = i * src.size / n
            val b = max(a + 1, (i + 1) * src.size / n)
            var m = 0f
            for (k in a until b) m = max(m, src[k])
            out[i] = m
        }
        return out
    }

    // ライトリボン・ワープ・電灯で使う動きの状態

    class Streak(var angle: Float, var z: Float, var len: Float, var hue: Float)
    val streaks = ArrayList<Streak>()

    class Ripple(val x: Float, val y: Float, val maxR: Float, val life: Float, val hue: Float, var age: Float = 0f)
    val ripples = ArrayList<Ripple>()

    class Drop(val x: Float, val y: Float, val size: Float, val hue: Float, var t: Float = 0f)
    val drops = ArrayList<Drop>()
    val prevGroup = FloatArray(8)
    val cool = FloatArray(8)
    var idle = 0f

    // ワープ
    var warpSpeed = 1f
    var warpSpin = 0f
    val rings = ArrayList<Float>()

    // VFDスペクトラムの残像
    val vfdHistory = ArrayDeque<FloatArray>()
    var vfdTimer = 0f
    val vfd3dHistory = ArrayDeque<FloatArray>()
    var vfd3dTimer = 0f
    var tunnelZ = 0f

    // アナログVUの針（ばねのように動く）
    val needle = FloatArray(2)
    val needleVel = FloatArray(2)

    class Lamp(var bright: Float = 1f, var target: Float = 1f, var state: Int = 0, var timer: Float = 0f, var chatter: Float = 0f)
    val lamps = ArrayList<Lamp>()
}

@Composable
fun Visualizer(
    style: VisStyle,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    if (style == VisStyle.OFF) return
    val meter = remember { Meter() }
    var frame by remember { mutableLongStateOf(0L) }
    val isPlaying by rememberUpdatedState(playing)

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { t ->
                meter.update(t, isPlaying)
                frame = t
            }
        }
    }

    var showLabel by remember { mutableStateOf(false) }
    var firstStyle by remember { mutableStateOf(true) }
    LaunchedEffect(style) {
        if (firstStyle) firstStyle = false else {
            showLabel = true
            delay(1400)
            showLabel = false
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onTap != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                ) else Modifier
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = frame / 1e9f
            when (style) {
                VisStyle.BLOCKS -> drawBlocks(meter)
                VisStyle.RETRO -> drawRetro(meter)
                VisStyle.RETRO_LR_CENTER -> drawRetroLRCenter(meter)
                VisStyle.VU -> drawVu(meter)
                VisStyle.VFD -> drawVfd(meter)
                VisStyle.VFD3D -> drawVfd3d(meter)
                VisStyle.RIPPLE -> drawRipple(meter, t)
                VisStyle.RIPPLE_TOP -> drawRippleTop(meter, t)
                VisStyle.WAVE -> drawWave(meter)
                VisStyle.CIRCLE -> drawCircleVis(meter, t)
                VisStyle.WARP -> drawWarp(meter, t)
                VisStyle.LIGHTS -> drawLights(meter, t)
                VisStyle.FLICKER -> drawFlicker(meter, t)
                VisStyle.OFF -> Unit
            }
        }
        AnimatedVisibility(
            visible = showLabel,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                style.label,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

private val LedGreen = Color(0xFF33FF66)
private val LedYellow = Color(0xFFFFD233)
private val LedRed = Color(0xFFFF3B30)

private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

private fun measure(text: String, sizePx: Float): Float {
    measurePaint.textSize = sizePx
    return measurePaint.measureText(text)
}

private fun DrawScope.label(text: String, x: Float, y: Float, color: Color, sizePx: Float, bold: Boolean = true) {
    drawIntoCanvas {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = sizePx
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        it.nativeCanvas.drawText(text, x, y, p)
    }
}

// ---------------- カラーブロック（虹色ブロック＋映り込み） ----------------

private fun DrawScope.drawBlocks(m: Meter) {
    val n = 20
    val segs = 10
    val lv = m.bands(m.level, n)
    val floor = size.height * 0.66f
    val colW = size.width / n
    val segH = floor / segs
    val bw = colW * 0.84f
    val bh = segH * 0.8f
    drawRect(
        Brush.verticalGradient(0f to Color.Black, 0.66f to Color.Black, 0.67f to Color(0xFF2A2A2A), 1f to Color(0xFF0A0A0A))
    )
    for (i in 0 until n) {
        val hue = (280f + i / (n - 1f) * 320f) % 360f
        val c = Color.hsv(hue, 0.85f, 1f)
        val x = colW * i + (colW - bw) / 2
        val lit = (lv[i] * segs + 0.6f).toInt().coerceIn(1, segs)
        for (s in 0 until lit) {
            val y = floor - segH * (s + 1) + (segH - bh) / 2
            drawRect(c, Offset(x, y), Size(bw, bh))
            // 映り込み
            val ry = floor + segH * s + (segH - bh) / 2
            if (ry < size.height) {
                val fade = (1f - (ry - floor) / (size.height - floor)).coerceIn(0f, 1f)
                drawRect(c.copy(alpha = 0.35f * fade), Offset(x, ry), Size(bw, min(bh, size.height - ry)))
            }
        }
    }
}

// ---------------- レトロLED（縦） ----------------

private fun DrawScope.drawRetro(m: Meter) {
    drawRect(Color(0xFF0D0F0D))
    val n = 20
    val segs = 12
    val lv = m.bands(m.level, n)
    val pk = m.bands(m.peak, n)
    val padX = 10f
    val padY = 8f
    val w = size.width - padX * 2
    val h = size.height - padY * 2
    val colW = w / n
    val segH = h / segs
    val bw = colW * 0.78f
    val bh = segH * 0.68f
    for (i in 0 until n) {
        val x = padX + colW * i + (colW - bw) / 2
        val litSegs = (lv[i] * segs).toInt()
        val peakSeg = (pk[i] * segs).toInt().coerceAtMost(segs - 1)
        for (s in 0 until segs) {
            val color = when {
                s >= segs - 2 -> LedRed
                s >= segs - 5 -> LedYellow
                else -> LedGreen
            }
            val y = padY + h - segH * (s + 1) + (segH - bh) / 2
            val on = s < litSegs || (s == peakSeg && pk[i] > 0.05f)
            if (on) {
                drawRect(color.copy(alpha = 0.25f), Offset(x - 2f, y - 2f), Size(bw + 4f, bh + 4f))
                drawRect(color, Offset(x, y), Size(bw, bh))
            } else {
                drawRect(color.copy(alpha = 0.08f), Offset(x, y), Size(bw, bh))
            }
        }
    }
}

// ---------------- レインボーウェーブ（立体スペクトラム） ----------------

private val WaveColors = listOf(
    Color(0xFFFF8A00), Color(0xFFFF3D7F), Color(0xFFD13DFF), Color(0xFF7A5CFF),
    Color(0xFF2BD9FF), Color(0xFF3DFFB0), Color(0xFFFFE53D),
)

private fun DrawScope.drawWave(m: Meter) {
    val hist = m.history.toList()
    if (hist.isEmpty()) return
    val n = 40
    val count = hist.size
    val brush = Brush.horizontalGradient(WaveColors)
    for ((k, levels) in hist.withIndex()) {
        val d = if (count <= 1) 1f else k / (count - 1f) // 0=奥, 1=手前
        val lv = m.bands(levels, n)
        val scale = 0.55f + 0.45f * d
        val width = size.width * scale
        val x0 = (size.width - width) / 2 + (1 - d) * size.width * 0.12f
        val baseY = size.height * (0.28f + 0.68f * d)
        val amp = size.height * (0.22f + 0.38f * d)
        val path = Path()
        for (i in 0 until n) {
            val x = x0 + width * i / (n - 1)
            val y = baseY - lv[i] * amp
            if (i == 0) path.moveTo(x, y) else {
                val px = x0 + width * (i - 1) / (n - 1)
                val py = baseY - lv[i - 1] * amp
                path.quadraticBezierTo(px, py, (px + x) / 2, (py + y) / 2)
            }
        }
        val alpha = 0.12f + 0.88f * d * d
        if (k == count - 1) drawPath(path, brush, alpha = 0.25f, style = Stroke(width = 8f, cap = StrokeCap.Round))
        drawPath(path, brush, alpha = alpha, style = Stroke(width = 1.2f + 1.8f * d, cap = StrokeCap.Round))
    }
}

// ---------------- サークル（円形スペクトラム＋波形） ----------------

private fun spectrumColor(v: Float): Color = when {
    v < 0.35f -> lerp(Color(0xFF3050FF), Color(0xFF2EE6E6), v / 0.35f)
    v < 0.6f -> lerp(Color(0xFF2EE6E6), Color(0xFF7CFF3A), (v - 0.35f) / 0.25f)
    v < 0.8f -> lerp(Color(0xFF7CFF3A), Color(0xFFFFD233), (v - 0.6f) / 0.2f)
    else -> lerp(Color(0xFFFFD233), Color(0xFFFF4030), ((v - 0.8f) / 0.2f).coerceAtMost(1f))
}

private fun DrawScope.drawCircleVis(m: Meter, t: Float) {
    drawRect(Color(0xFF151A2E))
    val cx = size.width / 2
    val cy = size.height / 2
    val sx = (size.width / size.height * 0.55f).coerceIn(1f, 2.4f) // 横長に広げる
    val r0 = size.height * (0.2f + 0.03f * m.bass)
    val maxLen = size.height * 0.3f
    val lines = 120
    val half = lines / 2
    val rot = t * 0.1f
    for (i in 0 until lines) {
        val a = (i.toFloat() / lines) * 2 * PI.toFloat() + rot
        // 左右対称に帯域を割り当てる
        val idx = if (i < half) i else lines - 1 - i
        val band = (idx * (Spectrum.BANDS - 1)) / (half - 1)
        val v = m.level[band.coerceIn(0, Spectrum.BANDS - 1)]
        val len = maxLen * (0.06f + v)
        val ca = cos(a)
        val sa = sin(a)
        val start = Offset(cx + ca * r0 * sx, cy + sa * r0)
        val end = Offset(cx + ca * (r0 + len) * sx, cy + sa * (r0 + len))
        drawLine(spectrumColor(v), start, end, strokeWidth = 2.5f, cap = StrokeCap.Round)
    }
    drawOval(Color(0xFF151A2E), Offset(cx - r0 * sx * 0.92f, cy - r0 * 0.92f), Size(r0 * sx * 1.84f, r0 * 1.84f))
    // 横に走る白い波形
    val wave = m.wave
    val path = Path()
    for (i in wave.indices) {
        val x = size.width * i / (wave.size - 1)
        val y = cy + wave[i] * size.height * 0.6f
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(width = 2f))
}

// ---------------- ワープ（うねりながら突き進む光のトンネル） ----------------

private fun DrawScope.drawWarp(m: Meter, t: Float) {
    drawRect(Color.Black)
    // 速度：音が大きいほど・低音が鳴るほど加速
    // 速く・激しく：ビートで急加速し、トンネル全体が大きく回転・蛇行する
    val target = 2.5f + m.energy * 14f + m.bass * 12f
    m.warpSpeed += (target - m.warpSpeed) * min(1f, m.dt * (if (target > m.warpSpeed) 10f else 1.5f))
    m.tunnelZ += m.dt * m.warpSpeed * 0.9f
    m.warpSpin += m.dt * (0.8f + m.energy * 3f)
    val travel = m.tunnelZ
    val sx = (size.width / size.height * 0.65f).coerceAtLeast(1f)
    // トンネルの中心線がうねる（奥ほど大きくずれる）
    fun bend(z: Float): Offset {
        val w = travel + z
        return Offset(size.width * 0.5f * sin(w * 1.3f + t * 0.9f), size.height * 0.55f * cos(w * 1.1f + t * 0.7f))
    }
    val near0 = bend(0.3f)
    // 画面全体のロール（左右に大きく傾く）と、低音での揺れ
    val roll = sin(t * 0.8f) * 0.9f + m.warpSpin * 0.25f
    val cr = cos(roll)
    val srl = sin(roll)
    val shakeX = (m.rnd.nextFloat() - 0.5f) * size.height * 0.08f * m.bass
    val shakeY = (m.rnd.nextFloat() - 0.5f) * size.height * 0.08f * m.bass
    fun proj(z: Float, ox: Float, oy: Float): Offset {
        val b = bend(z)
        val x = ((b.x - near0.x) + ox * sx) / z * 0.9f
        val y = ((b.y - near0.y) + oy) / z * 0.9f
        return Offset(size.width / 2 + x * cr - y * srl + shakeX, size.height / 2 + x * srl + y * cr + shakeY)
    }
    val spacing = 0.18f
    val count = 24
    val phase = (travel / spacing) % 1f
    // 奥から手前へ輪を描く
    for (k in count - 1 downTo 0) {
        val z = 0.32f + (k + 1 - phase) * spacing
        val near = (1f - z / 4.3f).coerceIn(0f, 1f)
        val hue = ((t * 90f + (travel + z) * 120f) % 360f + 360f) % 360f
        val c = Color.hsv(hue, 0.75f, 1f)
        val r = size.height * 0.42f
        val center = proj(z, 0f, 0f)
        val rx = r * sx / z * 0.9f
        val ry = r / z * 0.9f
        if (rx > size.width * 2.5f) continue
        // 輪を区切って、回転するパネルのように見せる
        val segs = 16
        for (i in 0 until segs) {
            if ((i + (m.warpSpin * 2).toInt()) % 4 == 3) continue
            val a1 = (i.toFloat() / segs) * 2 * PI.toFloat() + m.warpSpin
            val a2 = a1 + 2 * PI.toFloat() / segs * 0.8f
            drawLine(
                c.copy(alpha = near * (0.5f + 0.5f * m.energy.coerceAtMost(1f))),
                Offset(center.x + cos(a1) * rx, center.y + sin(a1) * ry),
                Offset(center.x + cos(a2) * rx, center.y + sin(a2) * ry),
                strokeWidth = 1f + near * 5f,
                cap = StrokeCap.Round,
            )
        }
    }
    // 壁に沿って流れる光の筋
    if (m.streaks.isEmpty()) repeat(120) {
        m.streaks += Meter.Streak(m.rnd.nextFloat() * 6.283f, 0.3f + m.rnd.nextFloat() * 4f, 0.1f + m.rnd.nextFloat() * 0.3f, m.rnd.nextFloat())
    }
    for (s in m.streaks) {
        s.z -= m.dt * m.warpSpeed * 0.9f
        if (s.z <= 0.32f) {
            s.z = 4.3f
            s.angle = m.rnd.nextFloat() * 6.283f
            s.hue = m.rnd.nextFloat()
        }
        val rr = size.height * 0.4f
        val a = s.angle + m.warpSpin * 0.3f
        val p1 = proj(s.z, cos(a) * rr, sin(a) * rr)
        val p2 = proj(s.z + s.len * (0.5f + m.warpSpeed * 0.3f), cos(a) * rr, sin(a) * rr)
        val near = (1f - s.z / 4.3f).coerceIn(0f, 1f)
        drawLine(Color.White.copy(alpha = near * 0.8f), p2, p1, strokeWidth = 1f + near * 2.5f, cap = StrokeCap.Round)
    }
    // 出口の光
    val far = proj(0.32f + count * spacing, 0f, 0f)
    val glow = size.height * (0.15f + 0.35f * m.energy)
    drawCircle(Brush.radialGradient(listOf(Color(0xCCFFFFFF), Color.Transparent), center = far, radius = glow), glow, far)
    if (m.bass > 0.5f) drawRect(Color.White.copy(alpha = ((m.bass - 0.5f) * 0.5f).coerceAtMost(0.2f)))
}

// ---------------- イルミネーション ----------------

private fun DrawScope.drawLights(m: Meter, t: Float) {
    val rows = 6
    val cell = size.height / rows
    val cols = max(8, (size.width / cell).toInt())
    val lv = m.bands(m.level, cols)
    val gapX = size.width / cols
    val r = min(cell, gapX) * 0.26f
    for (row in 0 until rows) {
        val cy = size.height - cell * row - cell / 2
        drawLine(Color(0x14FFFFFF), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5f)
    }
    for (i in 0 until cols) {
        val lit = lv[i] * rows + 0.15f
        val hue = ((i.toFloat() / cols) * 300f + t * 40f) % 360f
        val base = Color.hsv(hue, 0.75f, 1f)
        val cx = gapX * i + gapX / 2
        for (row in 0 until rows) {
            val cy = size.height - cell * row - cell / 2
            val on = (lit - row).coerceIn(0f, 1f)
            val twinkle = 0.75f + 0.25f * sin(t * 6f + i * 1.7f + row * 2.3f)
            if (on > 0.01f) {
                val a = on * twinkle
                drawCircle(
                    Brush.radialGradient(listOf(base.copy(alpha = 0.55f * a), Color.Transparent), center = Offset(cx, cy), radius = r * 3f),
                    radius = r * 3f,
                    center = Offset(cx, cy),
                )
                drawCircle(lerp(base, Color.White, 0.45f).copy(alpha = a), radius = r, center = Offset(cx, cy))
            } else {
                drawCircle(Color(0x1FFFFFFF), radius = r * 0.7f, center = Offset(cx, cy))
            }
        }
    }
}

// ---------------- ゆらぐ電灯（チラつき・消えかけ） ----------------

private fun DrawScope.drawFlicker(m: Meter, t: Float) {
    drawRect(Color(0xFF080605))
    val count = 4
    if (m.lamps.size != count) {
        m.lamps.clear()
        repeat(count) { m.lamps += Meter.Lamp() }
    }
    val dt = m.dt
    val music = 0.7f + 0.3f * (m.energy * 1.8f).coerceAtMost(1f)
    for ((i, lamp) in m.lamps.withIndex()) {
        // 状態：0=安定 1=消えかけてゆっくり暗くなる 2=チラチラ 3=復帰
        lamp.timer -= dt
        when (lamp.state) {
            0 -> {
                lamp.target = 1f
                if (lamp.timer <= 0f) {
                    lamp.timer = 1f
                    val r = m.rnd.nextFloat()
                    if (r < 0.06f) { lamp.state = 1; lamp.timer = 1.5f + m.rnd.nextFloat() * 2.5f }
                    else if (r < 0.16f) { lamp.state = 2; lamp.timer = 0.3f + m.rnd.nextFloat() * 0.9f }
                }
            }
            1 -> {
                lamp.target = 0.12f
                if (lamp.timer <= 0f) { lamp.state = 3; lamp.timer = 1.2f + m.rnd.nextFloat() }
            }
            2 -> {
                lamp.chatter -= dt
                if (lamp.chatter <= 0f) {
                    lamp.chatter = 0.04f + m.rnd.nextFloat() * 0.12f
                    lamp.target = if (m.rnd.nextBoolean()) 0.15f + m.rnd.nextFloat() * 0.3f else 1f
                }
                if (lamp.timer <= 0f) { lamp.state = 0; lamp.timer = 1f }
            }
            3 -> {
                lamp.target = 1f
                if (lamp.timer <= 0f) { lamp.state = 0; lamp.timer = 2f }
            }
        }
        val rate = when (lamp.state) { 1 -> 0.9f; 2 -> 25f; else -> 2.5f }
        lamp.bright += (lamp.target - lamp.bright) * min(1f, dt * rate)
        // 消えかけの間は細かくジジッと揺れる
        val buzz = if (lamp.state == 1) 0.85f + 0.15f * sin(t * 47f + i) * sin(t * 13f) else 0.97f + 0.03f * sin(t * 9f + i * 2f)
        val b = (lamp.bright * buzz * music).coerceIn(0f, 1f)

        val cx = size.width * (i + 0.5f) / count
        val bulbY = size.height * 0.42f
        val r = size.height * 0.11f
        // コード
        drawLine(Color(0xFF2A2420), Offset(cx, 0f), Offset(cx, bulbY - r * 1.1f), strokeWidth = 2f)
        drawRect(Color(0xFF3A3028), Offset(cx - r * 0.45f, bulbY - r * 1.3f), Size(r * 0.9f, r * 0.5f))
        // 光の広がり
        val halo = size.height * (0.55f + 0.35f * b)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFFB04A).copy(alpha = 0.55f * b), Color(0x33FF8A2A).copy(alpha = 0.2f * b), Color.Transparent), center = Offset(cx, bulbY), radius = halo),
            halo, Offset(cx, bulbY),
        )
        // 電球とフィラメント
        drawCircle(Color(0xFF2B2018).copy(alpha = 1f - b * 0.6f), r, Offset(cx, bulbY))
        drawCircle(lerp(Color(0xFF5A3A1A), Color(0xFFFFE2A8), b).copy(alpha = 0.35f + 0.65f * b), r * 0.92f, Offset(cx, bulbY))
        drawLine(lerp(Color(0xFF6A3A10), Color.White, b), Offset(cx - r * 0.35f, bulbY), Offset(cx + r * 0.35f, bulbY), strokeWidth = 2.5f, cap = StrokeCap.Round)
        // 床の照り返し
        drawOval(
            Brush.radialGradient(listOf(Color(0xFFFFB04A).copy(alpha = 0.35f * b), Color.Transparent), center = Offset(cx, size.height * 0.95f), radius = size.width / count * 0.5f),
            Offset(cx - size.width / count * 0.5f, size.height * 0.85f),
            Size(size.width / count, size.height * 0.2f),
        )
    }
}

// ---------------- 波紋（しずくが落ちて広がる） ----------------

private fun DrawScope.drawRipple(m: Meter, t: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF02060C), Color(0xFF06182A), Color(0xFF0A2238))))
    val dt = m.dt
    val surfaceTop = size.height * 0.22f

    // 音の立ち上がり（アタック）でしずくを落とす。低音は左、高音は右
    val groups = m.bands(m.level, 8)
    for (g in 0 until 8) {
        m.cool[g] -= dt
        val rise = groups[g] - m.prevGroup[g]
        if (rise > 0.07f && groups[g] > 0.25f && m.cool[g] <= 0f && m.drops.size + m.ripples.size < 60) {
            m.cool[g] = 0.22f + m.rnd.nextFloat() * 0.2f
            val x = size.width * ((g + m.rnd.nextFloat()) / 8f)
            val y = surfaceTop + (size.height - surfaceTop) * (0.15f + m.rnd.nextFloat() * 0.8f)
            m.drops += Meter.Drop(x, y, 0.5f + groups[g], 190f + g * 8f)
        }
        m.prevGroup[g] += (groups[g] - m.prevGroup[g]) * min(1f, dt * 8f)
    }
    // 静かな時もときどき雫が落ちる
    m.idle -= dt
    if (m.idle <= 0f) {
        m.idle = 1.2f + m.rnd.nextFloat() * 2.5f
        val y = surfaceTop + (size.height - surfaceTop) * (0.15f + m.rnd.nextFloat() * 0.8f)
        m.drops += Meter.Drop(size.width * m.rnd.nextFloat(), y, 0.45f, 200f)
    }

    // 落ちていくしずく
    val fall = 0.28f
    val itD = m.drops.iterator()
    while (itD.hasNext()) {
        val d = itD.next()
        d.t += dt
        val p = (d.t / fall).coerceAtMost(1f)
        val y = d.y - (1f - p) * (d.y + 10f) * 0.6f
        drawLine(
            Color.White.copy(alpha = 0.15f + 0.5f * p),
            Offset(d.x, y - 14f * d.size), Offset(d.x, y),
            strokeWidth = 2f, cap = StrokeCap.Round,
        )
        if (p >= 1f) {
            val depth = (d.y - surfaceTop) / (size.height - surfaceTop) // 手前ほど大きく
            m.ripples += Meter.Ripple(d.x, d.y, size.height * (0.35f + 0.7f * depth) * d.size, 2.2f + d.size, d.hue)
            itD.remove()
        }
    }

    // 広がる波紋（手前ほど大きい楕円にして水面らしく）
    val itR = m.ripples.iterator()
    while (itR.hasNext()) {
        val r = itR.next()
        r.age += dt
        val life = r.age / r.life
        if (life >= 1f) {
            itR.remove()
            continue
        }
        val fade = (1f - life) * (1f - life)
        for (k in 0 until 3) {
            val lag = life - k * 0.12f
            if (lag <= 0f) continue
            val rx = r.maxR * (1f - (1f - lag) * (1f - lag))
            val ry = rx * 0.32f
            val c = Color.hsv(r.hue, 0.45f, 1f)
            drawOval(
                c.copy(alpha = fade * (0.75f - k * 0.2f)),
                Offset(r.x - rx, r.y - ry),
                Size(rx * 2, ry * 2),
                style = Stroke(width = (2.4f - k * 0.6f) * (1f - lag * 0.5f)),
            )
        }
        // 落ちた瞬間のきらめき
        if (r.age < 0.25f) {
            val a = 1f - r.age / 0.25f
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.6f * a), Color.Transparent), center = Offset(r.x, r.y), radius = 22f),
                22f, Offset(r.x, r.y),
            )
        }
    }
    // 水面の反射のゆらぎ
    for (i in 0 until 6) {
        val y = surfaceTop + (size.height - surfaceTop) * (i + 0.5f) / 6f + 3f * sin(t * 0.8f + i)
        drawLine(Color(0x0FFFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}


// ---------------- レトロLR センター（中央から左右へ、横幅いっぱい） ----------------

/** 左右の音量を -30dB〜0dB の範囲に広げて、針の振れ（バーの動き）を大きくする */
private fun wideLevel(v: Float): Float {
    if (v <= 0.001f) return 0f
    val db = v * 42f - 42f
    return ((db + 30f) / 30f).coerceIn(0f, 1f)
}

private fun DrawScope.drawRetroLRCenter(m: Meter) {
    drawRect(Color(0xFF0D0F0D))
    val segs = 26
    val cx = size.width / 2
    val gap = 5f
    val margin = 6f
    val half = cx - gap - margin
    val segW = half / segs
    val rowH = size.height * 0.56f
    val top = size.height * 0.08f
    for (c in 0..1) {
        val lit = (wideLevel(m.lr[c]) * segs).toInt()
        val pk = (wideLevel(m.lrPeak[c]) * segs).toInt().coerceAtMost(segs - 1)
        for (s in 0 until segs) {
            // 約4割から黄色、最後の4つが赤
            val color = when {
                s >= segs - 4 -> LedRed
                s >= segs * 4 / 10 -> LedYellow
                else -> LedGreen
            }
            val w = segW * 0.74f
            val x = if (c == 0) cx - gap - segW * (s + 1) + segW * 0.13f else cx + gap + segW * s + segW * 0.13f
            val on = s < lit || (s == pk && m.lrPeak[c] > 0.03f)
            if (on) {
                drawRect(color.copy(alpha = 0.25f), Offset(x - 2f, top - 2f), Size(w + 4f, rowH + 4f))
                drawRect(color, Offset(x, top), Size(w, rowH))
            } else {
                drawRect(color.copy(alpha = 0.08f), Offset(x, top), Size(w, rowH))
            }
        }
    }
    drawLine(Color(0xFF2E3A2E), Offset(cx, top - 4f), Offset(cx, top + rowH + 4f), strokeWidth = 2f)
    // 目盛り：重ならない分だけ表示（狭い画面では自動で間引く）
    val textSize = min(size.height * 0.13f, half * 0.12f)
    val y = top + rowH + (size.height - top - rowH) * 0.5f + textSize * 0.35f
    val placed = ArrayList<Pair<Float, Float>>() // 右半分での (左端, 右端)
    for (db in listOf(0, -10, -20, -3, -6)) {
        val text = db.toString()
        val tw = measure(text, textSize) + textSize * 0.5f
        val dx = (half * (db + 30f) / 30f - segW * 0.5f).coerceAtMost(half - tw / 2)
        val l = dx - tw / 2
        val r = dx + tw / 2
        if (l < textSize || placed.any { r > it.first && l < it.second }) continue
        placed += l to r
        label(text, cx + gap + dx, y, Color(0xFF7A8A7A), textSize, bold = false)
        label(text, cx - gap - dx, y, Color(0xFF7A8A7A), textSize, bold = false)
    }
}

// ---------------- 音の立ち上がりで波紋を作る（共通） ----------------

private fun spawnOnsets(
    m: Meter,
    maxCount: Int = 70,
    minRise: Float = 0.07f,
    cooldown: Float = 0.2f,
    onHit: (group: Int, strength: Float) -> Unit,
) {
    val groups = m.bands(m.level, 8)
    for (g in 0 until 8) {
        m.cool[g] -= m.dt
        val rise = groups[g] - m.prevGroup[g]
        if (rise > minRise && groups[g] > 0.3f && m.cool[g] <= 0f && m.drops.size + m.ripples.size < maxCount) {
            m.cool[g] = cooldown + m.rnd.nextFloat() * cooldown
            onHit(g, groups[g])
        }
        m.prevGroup[g] += (groups[g] - m.prevGroup[g]) * min(1f, m.dt * 8f)
    }
}

// ---------------- カラー波紋（真上から見た水面） ----------------

private fun DrawScope.drawRippleTop(m: Meter, t: Float) {
    drawRect(Brush.radialGradient(listOf(Color(0xFF0A1420), Color(0xFF03060A)), center = center, radius = size.width * 0.7f))
    val baseHue = (t * 12f) % 360f // 全体の色が時間とともに移り変わる
    // 波紋は少なめに：同時に最大10個、音域ごとに約1秒あける
    spawnOnsets(m, maxCount = 10, minRise = 0.12f, cooldown = 0.9f) { g, strength ->
        val x = size.width * ((g + m.rnd.nextFloat()) / 8f)
        val y = size.height * (0.1f + m.rnd.nextFloat() * 0.8f)
        val hue = (baseHue + g * 40f + m.rnd.nextFloat() * 30f) % 360f
        m.ripples += Meter.Ripple(x, y, size.height * (0.45f + 0.9f * strength), 2.0f + strength * 1.5f, hue)
    }
    m.idle -= m.dt
    if (m.idle <= 0f) {
        m.idle = 2.5f + m.rnd.nextFloat() * 3f
        if (m.ripples.size < 4) m.ripples += Meter.Ripple(
            size.width * m.rnd.nextFloat(), size.height * m.rnd.nextFloat(),
            size.height * 0.5f, 2.2f, (baseHue + m.rnd.nextFloat() * 360f) % 360f,
        )
    }
    val it = m.ripples.iterator()
    while (it.hasNext()) {
        val r = it.next()
        r.age += m.dt
        val life = r.age / r.life
        if (life >= 1f) {
            it.remove()
            continue
        }
        val fade = (1f - life) * (1f - life)
        // 輪が広がるにつれて色相も少しずつずれる
        for (k in 0 until 4) {
            val lag = life - k * 0.09f
            if (lag <= 0f) continue
            val rad = r.maxR * (1f - (1f - lag) * (1f - lag))
            val c = Color.hsv((r.hue + k * 18f + lag * 60f) % 360f, 0.7f, 1f)
            drawCircle(c.copy(alpha = fade * 0.18f), rad, Offset(r.x, r.y), style = Stroke(width = 9f))
            drawCircle(c.copy(alpha = fade * (0.85f - k * 0.18f)), rad, Offset(r.x, r.y), style = Stroke(width = 2.2f - k * 0.35f))
        }
        if (r.age < 0.3f) {
            val a = 1f - r.age / 0.3f
            val c = Color.hsv(r.hue, 0.4f, 1f)
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = 0.8f * a), Color.Transparent), center = Offset(r.x, r.y), radius = 30f),
                30f, Offset(r.x, r.y),
            )
        }
    }
}

// ---------------- アナログVU（琥珀色の針メーター） ----------------

private const val VU_MIN = -20f
private const val VU_MAX = 3f

/** VU値 → 目盛り上の位置（0〜1）。実物と同じく電圧に比例した目盛り */
private fun vuPos(vu: Float): Float {
    val lo = 10f.pow(VU_MIN / 20f)
    val hi = 10f.pow(VU_MAX / 20f)
    return ((10f.pow(vu.coerceIn(VU_MIN, VU_MAX) / 20f) - lo) / (hi - lo)).coerceIn(0f, 1f)
}

private fun DrawScope.drawVu(m: Meter) {
    drawRect(Color(0xFF1A1714))
    // 針の動き：VUメーターらしく少しオーバーシュートして戻る
    for (c in 0..1) {
        val dbfs = m.lr[c] * 42f - 42f
        val target = if (m.lr[c] <= 0.001f) 0f else vuPos(dbfs + 12f) // -12dBFS ≒ 0VU
        val acc = 170f * (target - m.needle[c]) - 20f * m.needleVel[c]
        m.needleVel[c] += acc * m.dt
        m.needle[c] = (m.needle[c] + m.needleVel[c] * m.dt).coerceIn(-0.02f, 1.04f)
    }
    val gap = size.width * 0.025f
    val areaW = (size.width - gap * 3) / 2
    val areaH = size.height - 8f
    // 文字盤の縦横比を固定（横:縦 = 1.9:1）して、表示欄の形に関係なく崩れないようにする
    val faceH = min(areaH, areaW / 1.9f)
    val faceW = faceH * 1.9f
    for (c in 0..1) {
        val ax = gap + c * (areaW + gap)
        val x = ax + (areaW - faceW) / 2
        val y = (size.height - faceH) / 2
        drawVuMeter(x, y, faceW, faceH, m.needle[c], if (c == 0) "L" else "R")
    }
}

private fun DrawScope.drawVuMeter(x: Float, y: Float, w: Float, h: Float, pos: Float, ch: String) {
    drawRoundRect(Color(0xFF0A0908), Offset(x, y), Size(w, h), androidx.compose.ui.geometry.CornerRadius(h * 0.05f, h * 0.05f))
    val inset = h * 0.06f
    val fx = x + inset
    val fy = y + inset
    val fw = w - inset * 2
    val fh = h - inset * 2
    // 以下は文字盤の高さ fh を 1 とした寸法で配置する
    fun px(v: Float) = v * fh
    clipRect(fx, fy, fx + fw, fy + fh) {
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFFF4BC6E), Color(0xFFDC8E3E), Color(0xFF8E4C1C)),
                center = Offset(fx + fw / 2, fy + fh * 0.8f),
                radius = fw * 0.65f,
            ),
            Offset(fx, fy), Size(fw, fh),
        )
        val ink = Color(0xFF2A1608)
        val red = Color(0xFFB8221A)
        val cx = fx + fw / 2
        val pivotY = fy + px(1.32f)
        val radius = px(1.04f)
        val sweep = 0.8f
        fun pointAt(p: Float, r: Float): Offset {
            val a = -sweep + p * sweep * 2
            return Offset(cx + sin(a) * r, pivotY - cos(a) * r)
        }
        // 目盛りの弧（0VU以上は赤く太く）
        val zero = vuPos(0f)
        val steps = 48
        for (i in 0 until steps) {
            val p1 = i / steps.toFloat()
            val p2 = (i + 1) / steps.toFloat()
            val isRed = p1 >= zero - 0.001f
            drawLine(if (isRed) red else ink, pointAt(p1, radius), pointAt(p2, radius), strokeWidth = if (isRed) px(0.035f) else px(0.012f))
        }
        val marks = listOf(-20f, -10f, -7f, -5f, -3f, -2f, -1f, 0f, 1f, 2f, 3f)
        val labelled = setOf(-20f, -10f, -7f, -5f, -3f, 0f, 3f)
        for (v in marks) {
            val p = vuPos(v)
            val col = if (v > 0f) red else ink
            val long = v in labelled
            drawLine(col, pointAt(p, radius), pointAt(p, radius + px(if (long) 0.09f else 0.05f)), strokeWidth = px(0.012f))
            if (long) {
                val pt = pointAt(p, radius + px(0.17f))
                val text = when {
                    v > 0f -> "+${v.toInt()}"
                    v == 0f -> "0"
                    else -> "${-v.toInt()}"
                }
                label(text, pt.x, pt.y + px(0.04f), col, px(0.11f), bold = false)
            }
        }
        // − と ＋ の記号、VU、チャンネル
        val minus = pointAt(0f, radius + px(0.17f))
        label("−", minus.x - px(0.12f), minus.y + px(0.04f), ink, px(0.11f))
        label("VU", cx, fy + px(0.62f), ink, px(0.16f))
        label(ch, fx + px(0.16f), fy + px(0.9f), ink.copy(alpha = 0.85f), px(0.12f))
        // ピークランプ（右下）
        val lampOn = pos > vuPos(1f)
        val lamp = Offset(fx + fw - px(0.16f), fy + px(0.86f))
        if (lampOn) drawCircle(Brush.radialGradient(listOf(Color(0x99FF3020), Color.Transparent), center = lamp, radius = px(0.14f)), px(0.14f), lamp)
        drawCircle(if (lampOn) Color(0xFFFF3020) else Color(0xFF5A1A10), px(0.04f), lamp)
        // 針（影と本体）
        val tip = pointAt(pos.coerceIn(-0.02f, 1.04f), radius + px(0.07f))
        val baseY = fy + fh
        val k = (pivotY - baseY) / (pivotY - tip.y)
        val base = Offset(cx + (tip.x - cx) * k, baseY)
        drawLine(Color(0x55000000), base + Offset(px(0.02f), px(0.015f)), tip + Offset(px(0.02f), px(0.015f)), strokeWidth = px(0.016f), cap = StrokeCap.Round)
        drawLine(Color(0xFF140A04), base, tip, strokeWidth = px(0.013f), cap = StrokeCap.Round)
        // 下の支点カバー
        drawRect(Color(0xFF1A120C), Offset(fx, fy + px(0.93f)), Size(fw, px(0.07f)))
        // ガラスの映り込み
        drawRect(
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.12f), Color.Transparent, Color.Transparent),
                start = Offset(fx, fy), end = Offset(fx + fw, fy + fh),
            ),
            Offset(fx, fy), Size(fw, fh),
        )
    }
}

// ---------------- VFDスペクトラム（昔のカーコンポの蛍光表示管、奥行きあり） ----------------

private val VfdRows = listOf(
    Color(0xFF3B6BFF), Color(0xFF3B8BFF), Color(0xFF4FB3FF), Color(0xFF7FE0FF), Color(0xFFBDF4FF),
    Color(0xFF6CFF7A), Color(0xFFB5FF4A), Color(0xFFFFD84A), Color(0xFFFF8A3A), Color(0xFFFF3B30), Color(0xFFFF3B30),
)

private fun DrawScope.drawVfd(m: Meter) {
    drawRect(Color(0xFF050608))
    val n = 10
    val segs = VfdRows.size
    val labels = listOf("63", "125", "250", "500", "1k", "2k", "3.5k", "6k", "10k", "16k")
    // 残像（奥行き）用に少し前の値を記録
    m.vfdTimer += m.dt
    if (m.vfdTimer > 0.06f) {
        m.vfdTimer = 0f
        m.vfdHistory.addFirst(m.bands(m.level, n))
        while (m.vfdHistory.size > 4) m.vfdHistory.removeLast()
    }
    val labelH = size.height * 0.14f
    val floorY = size.height - labelH - 4f
    val topY = size.height * 0.06f
    val h = floorY - topY
    val cx = size.width / 2
    val baseW = size.width * 0.8f
    // 遠近：上にいくほど幅が狭くなる台形。奥の層は右上へずらす
    fun colX(i: Int, rowFrac: Float, depth: Int): Float {
        val shrink = 1f - 0.22f * rowFrac
        val x = cx - baseW / 2 + baseW * (i + 0.5f) / n
        return cx + (x - cx) * shrink + depth * size.width * 0.012f
    }
    val segH = h / segs
    val colW = baseW / n * 0.72f
    val layers = listOf(m.bands(m.level, n)) + m.vfdHistory.toList().take(3)
    for (depth in layers.indices.reversed()) {
        val lv = layers[depth]
        val dim = if (depth == 0) 1f else 0.28f / depth
        for (i in 0 until n) {
            val lit = (lv[i] * segs + 0.5f).toInt().coerceIn(if (depth == 0) 1 else 0, segs)
            for (s in 0 until segs) {
                val rowFrac = s / (segs - 1f)
                val shrink = 1f - 0.22f * rowFrac
                val w = colW * shrink
                val x = colX(i, rowFrac, depth) - w / 2
                val y = floorY - segH * (s + 1) + segH * 0.18f - depth * size.height * 0.02f
                val c = VfdRows[s]
                if (s < lit) {
                    if (depth == 0) drawRect(c.copy(alpha = 0.22f), Offset(x - 3f, y - 3f), Size(w + 6f, segH * 0.64f + 6f))
                    drawRect(c.copy(alpha = dim), Offset(x, y), Size(w, segH * 0.64f))
                } else if (depth == 0) {
                    drawRect(c.copy(alpha = 0.06f), Offset(x, y), Size(w, segH * 0.64f))
                }
            }
        }
    }
    // 床の線と周波数表示
    drawLine(Color(0xFF4FB3FF).copy(alpha = 0.5f), Offset(cx - baseW / 2, floorY + 2f), Offset(cx + baseW / 2, floorY + 2f), strokeWidth = 1.5f)
    val ts = min(labelH * 0.75f, baseW / n * 0.32f)
    for (i in 0 until n) {
        label(labels[i], colX(i, 0f, 0), floorY + 4f + ts, Color(0xFF7FE0FF).copy(alpha = 0.85f), ts, bold = false)
    }
}

// ---------------- VFDスペクトラム 3D（1層・台形の傾斜をきつく、奥へ進んでいく） ----------------

private fun DrawScope.drawVfd3d(m: Meter) {
    drawRect(Color(0xFF050608))
    val n = 14
    val segs = VfdRows.size
    val k = 0.22f
    val floorY = size.height * 0.98f
    val top = size.height * 0.05f
    val zt = 1f + segs * k
    val vpY = (top * zt - floorY) / (zt - 1f) // 一番上の段が top に来る消失点
    val vpX = size.width / 2
    val baseW = size.width * 0.96f
    fun p(x: Float, depth: Float): Offset {
        val z = 1f + depth * k
        return Offset(vpX + (x - vpX) / z, vpY + (floorY - vpY) / z)
    }
    // 床の線が手前へ流れてくる（音が大きいほど速く進む）
    m.tunnelZ = (m.tunnelZ + m.dt * (1.2f + m.energy * 5f)) % 1f
    val grid = Color(0xFF4FB3FF)
    for (j in 0..segs + 1) {
        val dep = j - m.tunnelZ
        if (dep < 0f || dep > segs) continue
        drawLine(
            grid.copy(alpha = 0.3f / (1f + dep * 0.3f)),
            p(vpX - baseW / 2 - size.width * 0.1f, dep), p(vpX + baseW / 2 + size.width * 0.1f, dep),
            strokeWidth = 1.2f,
        )
    }
    drawLine(grid.copy(alpha = 0.25f), p(vpX - baseW / 2, 0f), p(vpX - baseW / 2, segs.toFloat()), strokeWidth = 1.2f)
    drawLine(grid.copy(alpha = 0.25f), p(vpX + baseW / 2, 0f), p(vpX + baseW / 2, segs.toFloat()), strokeWidth = 1.2f)
    val lv = m.bands(m.level, n)
    val gap = 0.2f
    val path = Path()
    for (s in segs - 1 downTo 0) {
        val c = VfdRows[s]
        for (i in 0 until n) {
            val lit = (lv[i] * segs + 0.5f).toInt().coerceIn(1, segs)
            val x0 = vpX - baseW / 2 + baseW * i / n + baseW / n * 0.13f
            val x1 = x0 + baseW / n * 0.74f
            path.reset()
            val a = p(x0, s.toFloat())
            path.moveTo(a.x, a.y)
            p(x1, s.toFloat()).let { path.lineTo(it.x, it.y) }
            p(x1, s + 1 - gap).let { path.lineTo(it.x, it.y) }
            p(x0, s + 1 - gap).let { path.lineTo(it.x, it.y) }
            path.close()
            drawPath(path, c.copy(alpha = if (s < lit) 1f else 0.06f))
        }
    }
}
