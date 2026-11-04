package app.mileage.log

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/** 出発地・到着地の緯度経度から住所を引く（ネットが必要。失敗したら次回起動時にやり直す） */
object Geo {
    @Volatile private var busy = false

    /** 住所が空で、位置だけ残っている走行の住所を埋める */
    fun fillMissing(context: Context) {
        if (busy || !Geocoder.isPresent()) return
        busy = true
        val app = context.applicationContext
        Thread {
            try {
                val gc = Geocoder(app, Locale.JAPAN)
                for (t in Store.trips.value) {
                    if (t.startAddr.isEmpty() && t.startLat != null && t.startLon != null) {
                        lookup(gc, t.startLat, t.startLon)?.let { a ->
                            Store.modifyTrip(t.id) { if (it.startAddr.isEmpty()) it.copy(startAddr = a) else it }
                        }
                    }
                    if (t.endAddr.isEmpty() && t.endLat != null && t.endLon != null) {
                        lookup(gc, t.endLat, t.endLon)?.let { a ->
                            Store.modifyTrip(t.id) { if (it.endAddr.isEmpty()) it.copy(endAddr = a) else it }
                        }
                    }
                }
            } finally {
                busy = false
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun lookup(gc: Geocoder, lat: Double, lon: Double): String? = try {
        gc.getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
            val line = a.getAddressLine(0)
            if (!line.isNullOrBlank()) clean(line)
            else listOfNotNull(a.adminArea, a.locality, a.subLocality, a.thoroughfare, a.subThoroughfare)
                .joinToString("").ifBlank { null }
        }
    } catch (e: Exception) {
        null
    }

    /** 「日本、〒123-4567 東京都…」→「東京都…」 */
    private fun clean(s: String): String =
        s.replace(Regex("^日本[、,]\\s*"), "")
            .replace(Regex("〒\\d{3}-\\d{4}\\s*"), "")
            .replace(Regex("^Japan,?\\s*"), "")
            .trim()
}
