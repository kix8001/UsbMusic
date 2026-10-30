package app.usbmusic.player.ui

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.usbmusic.player.playback.Spectrum
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class VisStyle(val id: String, val label: String) {
    OFF("off", "オフ"),
    NEON("neon", "ネオン"),
    LIGHTS("lights", "イルミネーション"),
    RETRO("retro", "レトロLED"),
    ;

    companion object {
        fun of(id: String?): VisStyle = entries.firstOrNull { it.id == id } ?: NEON
        val cycle = listOf(NEON, LIGHTS, RETRO)
    }
}

/** 音の強さを、なめらかに上下する表示用の値に変える */
private class Meter {
    val level = FloatArray(Spectrum.BANDS)
    val peak = FloatArray(Spectrum.BANDS)
    val peakHold = FloatArray(Spectrum.BANDS)
    var lastNs = 0L

    fun update(now: Long, active: Boolean) {
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        val src = if (active) Spectrum.current() else null
        for (i in level.indices) {
            val target = src?.get(i) ?: 0f
            level[i] = if (target > level[i]) {
                level[i] + (target - level[i]) * min(1f, dt * 30f)
            } else {
                max(target, level[i] - dt * 1.6f)
            }
            if (level[i] >= peak[i]) {
                peak[i] = level[i]
                peakHold[i] = 0.5f
            } else if (peakHold[i] > 0f) {
                peakHold[i] -= dt
            } else {
                peak[i] = max(level[i], peak[i] - dt * 0.7f)
            }
        }
    }

    /** 帯域数を減らす（見た目に合わせて間引く） */
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

    // スタイルを切り替えた時に名前を少し表示
    var showLabel by remember { mutableStateOf(false) }
    var firstStyle by remember { mutableStateOf(true) }
    LaunchedEffect(style) {
        if (firstStyle) {
            firstStyle = false
        } else {
            showLabel = true
            delay(1400)
            showLabel = false
        }
    }

    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .clip(shape)
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
                VisStyle.NEON -> drawNeon(meter, t)
                VisStyle.LIGHTS -> drawLights(meter, t)
                VisStyle.RETRO -> drawRetro(meter)
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

// ---------------- ネオン：中央から上下に伸びる光るバー ----------------

private val NeonColors = listOf(Color(0xFFFA2D48), Color(0xFFB43CFF), Color(0xFF3C8CFF), Color(0xFF28E6E6))

private fun neonColor(x: Float): Color {
    val p = x.coerceIn(0f, 1f) * (NeonColors.size - 1)
    val i = p.toInt().coerceAtMost(NeonColors.size - 2)
    return lerp(NeonColors[i], NeonColors[i + 1], p - i)
}

private fun DrawScope.drawNeon(m: Meter, t: Float) {
    val n = 40
    val lv = m.bands(m.level, n)
    val pk = m.bands(m.peak, n)
    val gap = size.width / n
    val barW = gap * 0.55f
    val mid = size.height * 0.62f
    val upMax = mid - 4f
    val downMax = size.height - mid - 2f
    // うっすら床の光
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.62f to Color(0x22B43CFF),
            1f to Color.Transparent,
        )
    )
    for (i in 0 until n) {
        val shimmer = 0.85f + 0.15f * sin(t * 2f + i * 0.35f)
        val c = neonColor(i / (n - 1f))
        val x = gap * i + (gap - barW) / 2
        val h = max(2f, lv[i] * upMax)
        // にじみ（グロー）
        drawRoundRect(
            color = c.copy(alpha = 0.22f * shimmer),
            topLeft = Offset(x - barW * 0.45f, mid - h - barW * 0.3f),
            size = Size(barW * 1.9f, h + barW * 0.3f),
            cornerRadius = CornerRadius(barW, barW),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color.White, c), startY = mid - h, endY = mid),
            topLeft = Offset(x, mid - h),
            size = Size(barW, h),
            cornerRadius = CornerRadius(barW / 2, barW / 2),
        )
        // 床への映り込み
        val rh = h / upMax * downMax * 0.8f
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(c.copy(alpha = 0.45f), Color.Transparent), startY = mid, endY = mid + rh),
            topLeft = Offset(x, mid + 2f),
            size = Size(barW, rh),
            cornerRadius = CornerRadius(barW / 2, barW / 2),
        )
        // ピーク
        if (pk[i] > 0.02f) {
            drawCircle(Color.White.copy(alpha = 0.9f), radius = barW * 0.32f, center = Offset(x + barW / 2, mid - pk[i] * upMax - barW * 0.6f))
        }
    }
}

// ---------------- イルミネーション：電球が並んで光る ----------------

private fun DrawScope.drawLights(m: Meter, t: Float) {
    val rows = 6
    val cell = size.height / rows
    val cols = max(8, (size.width / cell).toInt())
    val lv = m.bands(m.level, cols)
    val gapX = size.width / cols
    val r = min(cell, gapX) * 0.26f
    for (i in 0 until cols) {
        val lit = (lv[i] * rows + 0.15f)
        // 色はゆっくり流れる（暖色〜寒色）
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
                    Brush.radialGradient(
                        listOf(base.copy(alpha = 0.55f * a), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = r * 3f,
                    ),
                    radius = r * 3f,
                    center = Offset(cx, cy),
                )
                drawCircle(lerp(base, Color.White, 0.45f).copy(alpha = a), radius = r, center = Offset(cx, cy))
            } else {
                drawCircle(Color(0x1FFFFFFF), radius = r * 0.7f, center = Offset(cx, cy))
            }
        }
        // 電線
        if (i == 0) {
            for (row in 0 until rows) {
                val cy = size.height - cell * row - cell / 2
                drawLine(Color(0x14FFFFFF), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5f)
            }
        }
    }
}

// ---------------- レトロLED：昔のグラフィックイコライザー ----------------

private val LedGreen = Color(0xFF33FF66)
private val LedYellow = Color(0xFFFFD233)
private val LedRed = Color(0xFFFF3B30)

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
