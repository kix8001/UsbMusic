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
import kotlin.math.sin
import kotlin.random.Random

enum class VisStyle(val id: String, val label: String, val desc: String) {
    OFF("off", "オフ", "表示しません"),
    BLOCKS("blocks", "カラーブロック", "虹色のブロックが跳ね、床に映り込みます"),
    RETRO("retro", "レトロLED", "昔のLEDグラフィックイコライザー風"),
    RETRO_LR("retro_lr", "レトロLR", "左右チャンネルのLEDメーターが横に振れます"),
    WAVE("wave", "レインボーウェーブ", "音の波が奥から手前へ流れる立体スペクトラム"),
    CIRCLE("circle", "サークル", "円形に広がるスペクトラムと波形"),
    RIBBON("ribbon", "ライトリボン", "光の帯がうねり、粒子がきらめきます"),
    WARP("warp", "ワープ", "光の筋が中心から飛び出す、ワープ空間"),
    LIGHTS("lights", "イルミネーション", "色が流れる電球が音に合わせて点灯します"),
    AMBIENT("ambient", "間接照明", "落ち着いた光がゆっくり呼吸するように変化します"),
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
    class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var r: Float, var phase: Float)
    val particles = ArrayList<Particle>()

    class Streak(var angle: Float, var z: Float, var len: Float, var hue: Float)
    val streaks = ArrayList<Streak>()

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
                VisStyle.RETRO_LR -> drawRetroLR(meter)
                VisStyle.WAVE -> drawWave(meter)
                VisStyle.CIRCLE -> drawCircleVis(meter, t)
                VisStyle.RIBBON -> drawRibbon(meter, t)
                VisStyle.WARP -> drawWarp(meter, t)
                VisStyle.LIGHTS -> drawLights(meter, t)
                VisStyle.AMBIENT -> drawAmbient(meter, t)
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

// ---------------- レトロLR（左右チャンネルの横型LEDメーター） ----------------

private fun DrawScope.drawRetroLR(m: Meter) {
    drawRect(Color(0xFF0D0F0D))
    val segs = 36
    val labelW = size.height * 0.28f
    val left = labelW + 6f
    val right = size.width - 12f
    val segW = (right - left) / segs
    val rowH = size.height * 0.3f
    val rows = listOf(size.height * 0.08f, size.height * 0.62f)
    val names = listOf("L", "R")
    for (c in 0..1) {
        val top = rows[c]
        label(names[c], labelW / 2 + 4f, top + rowH * 0.78f, LedGreen.copy(alpha = 0.9f), rowH * 0.9f)
        val lit = (m.lr[c] * segs).toInt()
        val pk = (m.lrPeak[c] * segs).toInt().coerceAtMost(segs - 1)
        for (s in 0 until segs) {
            val color = when {
                s >= segs - 5 -> LedRed
                s >= segs - 11 -> LedYellow
                else -> LedGreen
            }
            val x = left + segW * s + segW * 0.12f
            val w = segW * 0.76f
            val on = s < lit || (s == pk && m.lrPeak[c] > 0.03f)
            if (on) {
                drawRect(color.copy(alpha = 0.25f), Offset(x - 2f, top - 2f), Size(w + 4f, rowH + 4f))
                drawRect(color, Offset(x, top), Size(w, rowH))
            } else {
                drawRect(color.copy(alpha = 0.08f), Offset(x, top), Size(w, rowH))
            }
        }
    }
    // 目盛り（dB）
    val marks = listOf(-40 to 0f, -20 to 0.5238f, -10 to 0.7619f, -6 to 0.857f, -3 to 0.9286f, 0 to 1f)
    val midY = size.height * 0.5f + size.height * 0.05f
    for ((db, f) in marks) {
        label(if (db == 0) "0" else db.toString(), left + (right - left) * f, midY, Color(0xFF7A8A7A), size.height * 0.11f, bold = false)
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

// ---------------- ライトリボン（光の帯と粒子） ----------------

private fun DrawScope.drawRibbon(m: Meter, t: Float) {
    drawRect(Brush.radialGradient(listOf(Color(0xFF1A1030), Color.Black), center = center, radius = size.width * 0.7f))
    val fx = size.width * (0.5f + 0.18f * sin(t * 0.13f))
    val fy = size.height * (0.5f + 0.15f * sin(t * 0.21f))
    val colors = listOf(Color(0xFFBFF6FF), Color(0xFFE3C8FF), Color(0xFF8FD8FF), Color.White, Color(0xFFD9A8FF), Color(0xFF9FE8FF), Color(0xFFF0E0FF))
    val bands = floatArrayOf(m.bass, m.energy, m.treble * 1.6f)
    for (s in colors.indices) {
        val amp = size.height * (0.25f + 0.9f * bands[s % 3]).coerceAtMost(1.3f)
        val freq = 1.4f + s * 0.37f
        val speed = 0.5f + s * 0.17f
        val phase = s * 1.3f
        val path = Path()
        val steps = 70
        for (i in 0..steps) {
            val u = i / steps.toFloat()
            val x = size.width * u
            // 焦点に近いほど帯が集まる
            val d = (abs(x - fx) / size.width).coerceIn(0f, 1f)
            val env = 0.08f + d * 1.4f
            val y = fy + amp * env * sin(u * freq * 2 * PI.toFloat() + t * speed + phase) * 0.5f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colors[s], alpha = 0.12f, style = Stroke(width = 10f, cap = StrokeCap.Round))
        drawPath(path, colors[s], alpha = 0.8f, style = Stroke(width = 1.6f, cap = StrokeCap.Round))
    }
    // 焦点の光
    val glow = size.height * (0.35f + 0.5f * m.bass)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), Color(0x66C8A0FF), Color.Transparent), center = Offset(fx, fy), radius = glow), glow, Offset(fx, fy))
    // 粒子（ぼけた光）
    if (m.particles.isEmpty()) repeat(36) {
        m.particles += Meter.Particle(
            m.rnd.nextFloat(), m.rnd.nextFloat(),
            (m.rnd.nextFloat() - 0.5f) * 0.03f, (m.rnd.nextFloat() - 0.5f) * 0.03f,
            2f + m.rnd.nextFloat() * 7f, m.rnd.nextFloat() * 6.28f,
        )
    }
    for (p in m.particles) {
        p.x = (p.x + p.vx * m.dt * (1f + m.energy * 4f) + 1f) % 1f
        p.y = (p.y + p.vy * m.dt * (1f + m.energy * 4f) + 1f) % 1f
        val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 2.5f + p.phase)) * (0.5f + m.treble * 1.5f).coerceAtMost(1f)
        val c = Offset(p.x * size.width, p.y * size.height)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFE6D6FF).copy(alpha = 0.7f * tw), Color.Transparent), center = c, radius = p.r * 2.5f), p.r * 2.5f, c)
    }
}

// ---------------- ワープ（中心から飛び出す光の筋） ----------------

private fun DrawScope.drawWarp(m: Meter, t: Float) {
    drawRect(Color.Black)
    val cx = size.width / 2
    val cy = size.height / 2
    val sx = size.width / size.height
    if (m.streaks.isEmpty()) repeat(110) {
        m.streaks += Meter.Streak(m.rnd.nextFloat() * 6.283f, 0.1f + m.rnd.nextFloat() * 2.9f, 0.1f + m.rnd.nextFloat() * 0.5f, m.rnd.nextFloat())
    }
    val speed = 0.4f + m.energy * 5f + m.bass * 3f
    for (s in m.streaks) {
        s.z -= m.dt * speed
        if (s.z <= 0.08f) {
            s.z = 3f
            s.angle = m.rnd.nextFloat() * 6.283f
            s.len = 0.1f + m.rnd.nextFloat() * 0.5f
        }
        val k = size.height * 0.12f
        val r1 = k / s.z
        val r2 = k / (s.z + s.len)
        if (r1 > size.width) continue
        val ca = cos(s.angle) * sx * 0.5f
        val sa = sin(s.angle)
        val near = (1f - s.z / 3f).coerceIn(0f, 1f)
        val color = lerp(Color(0xFF2AF5B0), Color(0xFF9CFFE8), s.hue)
        drawLine(
            color.copy(alpha = near * 0.9f),
            Offset(cx + ca * r2, cy + sa * r2),
            Offset(cx + ca * r1, cy + sa * r1),
            strokeWidth = 1f + near * 3.5f,
            cap = StrokeCap.Round,
        )
    }
    val glow = size.height * (0.25f + 0.4f * m.energy)
    drawCircle(Brush.radialGradient(listOf(Color(0x8835FFC0), Color.Transparent), center = Offset(cx, cy), radius = glow), glow, Offset(cx, cy))
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

// ---------------- 間接照明（落ち着いた光） ----------------

private fun DrawScope.drawAmbient(m: Meter, t: Float) {
    drawRect(Color(0xFF0B0806))
    val e = (m.slowEnergy * 1.6f).coerceIn(0f, 1f)
    val warmShift = 0.5f + 0.5f * sin(t * 0.03f) // 数分かけて色味がゆっくり変わる
    val warm = lerp(Color(0xFFFFB25A), Color(0xFFFF8A6A), warmShift)
    val cool = lerp(Color(0xFF7B5CFF), Color(0xFFB0507F), 1f - warmShift)
    // 上の縁から壁を照らすコーブ照明
    drawRect(
        Brush.verticalGradient(
            0f to warm.copy(alpha = 0.35f + 0.35f * e),
            0.45f to warm.copy(alpha = 0.08f + 0.1f * e),
            1f to Color.Transparent,
        )
    )
    // 柔らかい光だまり
    val spots = listOf(
        Triple(0.2f + 0.04f * sin(t * 0.07f), warm, 0.9f),
        Triple(0.55f + 0.05f * sin(t * 0.05f + 2f), cool, 0.7f),
        Triple(0.85f + 0.04f * sin(t * 0.06f + 4f), warm, 0.8f),
    )
    for ((fx, c, w) in spots) {
        val breath = 0.55f + 0.45f * sin(t * 0.4f + fx * 5f)
        val rad = size.height * (1.1f + 0.4f * e) * w
        val center = Offset(size.width * fx, size.height * 0.75f)
        drawCircle(
            Brush.radialGradient(listOf(c.copy(alpha = (0.18f + 0.35f * e) * (0.7f + 0.3f * breath)), Color.Transparent), center = center, radius = rad),
            rad,
            center,
        )
    }
    // 床面の反射ライン
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, warm.copy(alpha = 0.25f + 0.25f * e), Color.Transparent)),
        Offset(0f, size.height - 3f), Size(size.width, 3f),
    )
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
