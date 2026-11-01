package app.usbmusic.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/** 画面とサービスで共有する再生オプション（同じアプリ内なのでここで受け渡す） */
object PlaybackOptions {
    @Volatile var videoFade = true
}

/**
 * 音量を一定にする（オートゲイン＋リミッター）。
 * 再生中の音の大きさを測り、小さい曲・動画は持ち上げ、大きいものは下げる。
 * 大きくなった時はすばやく（約0.1〜0.3秒）、小さくなった時はゆっくり（約1〜3秒）追従して、自然に聞こえるようにする。
 * 追従が間に合わない一瞬の大音量はリミッターで抑える。
 */
@OptIn(UnstableApi::class)
object VolumeLeveler : BaseAudioProcessor() {
    @Volatile var enabled = true

    /** いま再生中の曲・動画だけに掛ける音量（1.0 = そのまま）。曲ごとに記憶される */
    @Volatile var itemGain = 1f

    private const val TARGET_RMS = 0.14f       // 目標の音の大きさ（約 -17 dBFS）
    private const val MAX_GAIN = 4.0f          // 最大 +12 dB
    private const val MIN_GAIN = 0.25f         // 最大 -12 dB
    private const val SILENCE = 0.003f         // これより小さい音（無音）では持ち上げない

    private var channels = 2
    private var blockFrames = 441
    private var env = TARGET_RMS * TARGET_RMS  // 音の大きさ（二乗平均）の推定値
    private var gain = 1f
    private var attackCoef = 0.1f
    private var releaseCoef = 0.007f
    private var envUp = 0.2f
    private var envDown = 0.02f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        blockFrames = (rate / 100).coerceAtLeast(1) // 10ミリ秒ごとに調整
        val blocksPerSec = rate.toFloat() / blockFrames
        envUp = 1f / (0.05f * blocksPerSec)      // 大きくなった音はすぐ測る
        envDown = 1f / (0.5f * blocksPerSec)     // 小さくなった音はゆっくり
        attackCoef = 1f / (0.1f * blocksPerSec)  // 下げる時は約0.1秒
        releaseCoef = 1f / (1.2f * blocksPerSec) // 上げる時は約1.2秒
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val extra = itemGain
        if (!enabled && extra == 1f) {
            out.put(inputBuffer)
            out.flip()
            gain = 1f
            return
        }
        val inp = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = 2 * channels
        val frames = remaining / frameBytes
        var f = 0
        while (f < frames) {
            val n = minOf(blockFrames, frames - f)
            // このブロックの音の大きさを測る
            val start = inp.position()
            var sum = 0.0
            for (i in 0 until n * channels) {
                val v = inp.getShort(start + i * 2) / 32768f
                sum += v * v
            }
            val ms = (sum / (n * channels)).toFloat()
            env += (ms - env) * (if (ms > env) envUp else envDown)
            val rms = sqrt(env)
            val from: Float
            if (enabled) {
                val target = if (rms < SILENCE) gain else (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
                from = gain
                gain += (target - gain) * (if (target < gain) attackCoef else releaseCoef)
            } else {
                gain = 1f
                from = 1f
            }
            // ゲインをブロック内でなめらかに変えながら掛け、はみ出しそうな音は柔らかく抑える
            for (i in 0 until n) {
                val g = (from + (gain - from) * (i + 1) / n) * extra
                for (c in 0 until channels) {
                    val x = inp.short / 32768f * g
                    out.putShort((limit(x) * 32767f).toInt().toShort())
                }
            }
            f += n
        }
        // 端数のバイトがあればそのまま
        while (inp.hasRemaining()) out.put(inp.get())
        out.flip()
    }

    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val y = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
        return if (x < 0) -y else y
    }
}

/**
 * 動画の切り替えフェードの明るさ・音量（0〜1）。
 * 始まってから0.8秒でフェードイン、終わりの1.2秒でフェードアウト。
 */
fun fadeLevel(sinceStartMs: Long, positionMs: Long, durationMs: Long): Float {
    val fin = (sinceStartMs / 800f).coerceIn(0f, 1f)
    val fout = if (durationMs > 0) ((durationMs - positionMs) / 1200f).coerceIn(0f, 1f) else 1f
    return minOf(fin, fout)
}

/** 曲・動画ごとの音量（dB）を覚えておく */
object ItemGains {
    private fun sp(context: android.content.Context) =
        context.getSharedPreferences("item_gain", android.content.Context.MODE_PRIVATE)

    fun get(context: android.content.Context, key: String): Int = sp(context).getInt(key, 0)

    fun put(context: android.content.Context, key: String, db: Int) {
        if (db == 0) sp(context).edit().remove(key).apply() else sp(context).edit().putInt(key, db).apply()
    }

    fun linear(db: Int): Float = Math.pow(10.0, db / 20.0).toFloat()
}
