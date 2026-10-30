package app.usbmusic.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 再生中の音を周波数ごとの強さ（スペクトラム）に変換して画面へ渡す。
 * プレーヤーの音声出力の手前で音のデータを受け取るため、マイクの許可は不要。
 */
@OptIn(UnstableApi::class)
object Spectrum : TeeAudioProcessor.AudioBufferSink {
    const val BANDS = 48

    private const val N = 2048
    private const val HOP = 1024

    /** 解析した音がスピーカーから出るまでのおおよその遅れ */
    private const val LATENCY_NS = 300_000_000L

    private val lock = Any()
    private val queue = ArrayDeque<Pair<Long, FloatArray>>()
    private var shown = FloatArray(BANDS)
    private var lastTime = 0L

    // 以下は再生スレッドだけが触る
    private var supported = false
    private var channels = 2
    private var sampleRate = 44100
    private val ring = FloatArray(N)
    private var writePos = 0
    private var sinceLast = 0
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val window = FloatArray(N) { (0.5 * (1 - cos(2 * PI * it / (N - 1)))).toFloat() }
    private val cosT = FloatArray(N / 2) { cos(2 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(2 * PI * it / N).toFloat() }
    private var edges = IntArray(BANDS + 1)
    private var tilt = FloatArray(BANDS)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        supported = encoding == C.ENCODING_PCM_16BIT && channelCount > 0 && sampleRateHz > 0
        channels = channelCount.coerceAtLeast(1)
        sampleRate = sampleRateHz.coerceAtLeast(8000)
        writePos = 0
        sinceLast = 0
        ring.fill(0f)
        computeEdges()
        synchronized(lock) { queue.clear() }
    }

    private fun computeEdges() {
        val lo = 40.0
        val hi = minOf(16000.0, sampleRate / 2.0 - 100)
        val e = IntArray(BANDS + 1)
        for (i in 0..BANDS) {
            val f = lo * (hi / lo).pow(i.toDouble() / BANDS)
            e[i] = (f * N / sampleRate).roundToInt().coerceIn(1, N / 2 - 1)
            if (i > 0 && e[i] <= e[i - 1]) e[i] = (e[i - 1] + 1).coerceAtMost(N / 2)
        }
        edges = e
        // 高音ほどエネルギーが小さいので、見た目のバランスのため少し持ち上げる
        tilt = FloatArray(BANDS) { i ->
            val fc = lo * (hi / lo).pow((i + 0.5) / BANDS)
            (3.0 * ln(fc / 1000.0) / ln(2.0)).toFloat()
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!supported) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = 2 * channels
        while (b.remaining() >= frameBytes) {
            var sum = 0f
            for (c in 0 until channels) sum += b.short / 32768f
            ring[writePos] = sum / channels
            writePos = (writePos + 1) % N
            if (++sinceLast >= HOP) {
                sinceLast = 0
                analyze()
            }
        }
    }

    private fun analyze() {
        for (i in 0 until N) {
            re[i] = ring[(writePos + i) % N] * window[i]
            im[i] = 0f
        }
        fft()
        val out = FloatArray(BANDS)
        val ref = N / 4f
        for (band in 0 until BANDS) {
            var peak = 0f
            for (k in edges[band] until max(edges[band + 1], edges[band] + 1)) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                if (m > peak) peak = m
            }
            val db = 20f * log10((peak / ref).coerceAtLeast(1e-6f)) + tilt[band]
            out[band] = ((db + 62f) / 56f).coerceIn(0f, 1f)
        }
        val at = System.nanoTime() + LATENCY_NS
        synchronized(lock) {
            queue.addLast(at to out)
            while (queue.size > 64) queue.removeFirst()
        }
    }

    private fun fft() {
        // ビット反転
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= N) {
            val half = len / 2
            val step = N / len
            var start = 0
            while (start < N) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = -sinT[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += len
            }
            len = len shl 1
        }
    }

    /** いま鳴っている音のスペクトラム（0〜1）。音が止まっていれば null */
    fun current(now: Long = System.nanoTime()): FloatArray? = synchronized(lock) {
        var changed = false
        while (queue.isNotEmpty() && queue.first().first <= now) {
            val (t, v) = queue.removeFirst()
            shown = v
            lastTime = t
            changed = true
        }
        if (changed || now - lastTime < 120_000_000L) shown else null
    }
}
