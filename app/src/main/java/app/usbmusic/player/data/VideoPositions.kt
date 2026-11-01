package app.usbmusic.player.data

import android.content.Context

/** 動画ごとに「どこまで見たか」を覚えておく */
object VideoPositions {
    private fun sp(context: Context) = context.getSharedPreferences("video_positions", Context.MODE_PRIVATE)

    /** (再生位置, 長さ) ミリ秒。記録が無ければ null */
    fun get(context: Context, uri: String): Pair<Long, Long>? {
        val v = sp(context).getString(uri, null) ?: return null
        val parts = v.split(',')
        val pos = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val dur = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return pos to dur
    }

    fun all(context: Context): Map<String, Pair<Long, Long>> =
        sp(context).all.mapNotNull { (k, v) ->
            val parts = (v as? String)?.split(',') ?: return@mapNotNull null
            val pos = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            k to (pos to (parts.getOrNull(1)?.toLongOrNull() ?: 0L))
        }.toMap()

    fun put(context: Context, uri: String, pos: Long, dur: Long) {
        // 最後まで見た（残り20秒未満）なら記録を消して、次は最初から
        if (dur > 0 && pos > dur - 20_000) {
            sp(context).edit().remove(uri).apply()
        } else if (pos > 5_000) {
            sp(context).edit().putString(uri, "$pos,$dur").apply()
        }
    }
}
