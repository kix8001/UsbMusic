package app.usbmusic.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections

/**
 * アルバムアートの読み込み。
 * 優先順：IDタグ埋め込み画像 → フォルダ内の cover.jpg 等 → Androidのサムネイル。
 * 一度読んだ画像はアプリ内に保存し、次回からはUSBを読まずに表示する。
 */
object ArtLoader {
    private const val MAX_STORED = 800

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val gate = Semaphore(3)
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private fun bucket(px: Int): Int = when {
        px <= 128 -> 128
        px <= 320 -> 320
        else -> MAX_STORED
    }

    fun peek(track: Track, px: Int): Bitmap? = memory.get("${track.albumKey}@${bucket(px)}")

    fun resetMissing() {
        missing.clear()
    }

    fun clearAll(context: Context) {
        memory.evictAll()
        missing.clear()
        File(context.cacheDir, "art").deleteRecursively()
    }

    suspend fun load(context: Context, track: Track, px: Int): Bitmap? {
        val size = bucket(px)
        val key = "${track.albumKey}@$size"
        memory.get(key)?.let { return it }
        if (track.albumKey in missing) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                memory.get(key) ?: run {
                    val file = File(context.cacheDir, "art/${md5(track.albumKey)}.jpg")
                    if (!file.exists() && track.albumKey !in missing) {
                        if (!extractToFile(context, track, file)) missing += track.albumKey
                    }
                    if (file.exists()) decodeFile(file, size)?.also { memory.put(key, it) } else null
                }
            }
        }
    }

    private fun extractToFile(context: Context, track: Track, file: File): Boolean {
        val bytes = extractBytes(context, track) ?: return false
        val bmp = decodeBytes(bytes, MAX_STORED) ?: return false
        val dir = file.parentFile ?: return false
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        val ok = runCatching {
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            tmp.renameTo(file)
        }.getOrDefault(false)
        bmp.recycle()
        if (tmp.exists()) tmp.delete()
        return ok
    }

    private fun extractBytes(context: Context, track: Track): ByteArray? {
        val uri = Uri.parse(track.uri)
        val embedded = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                r.embeddedPicture
            } finally {
                runCatching { r.release() }
            }
        }.getOrNull()
        if (embedded != null && embedded.isNotEmpty()) return embedded

        track.cover?.let { cover ->
            val bytes = runCatching {
                context.contentResolver.openInputStream(Uri.parse(cover))?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }

        if (track.fromMediaStore) {
            val thumb = runCatching {
                val bmp = context.contentResolver.loadThumbnail(uri, Size(MAX_STORED, MAX_STORED), null)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                bmp.recycle()
                out.toByteArray()
            }.getOrNull()
            if (thumb != null && thumb.isNotEmpty()) return thumb
        }
        return null
    }

    private fun sampleSize(w: Int, h: Int, max: Int): Int {
        var s = 1
        while (w / (s * 2) >= max && h / (s * 2) >= max) s *= 2
        return s
    }

    private fun decodeBytes(bytes: ByteArray, max: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, max)
        opts.inJustDecodeBounds = false
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return scaleDown(bmp, max)
    }

    private fun decodeFile(file: File, max: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, max)
        opts.inJustDecodeBounds = false
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        return scaleDown(bmp, max)
    }

    private fun scaleDown(bmp: Bitmap, max: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= max) return bmp
        val r = max.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bmp, (bmp.width * r).toInt().coerceAtLeast(1), (bmp.height * r).toInt().coerceAtLeast(1), true
        )
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
