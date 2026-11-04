package app.mileage.log

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 1回の走行。GPSで測ったもの（manual=false）と手入力したもの（manual=true）がある */
data class Trip(
    val id: Long,
    val start: Long,
    val end: Long,
    val meters: Double,
    val note: String = "",
    val manual: Boolean = false,
) {
    val km: Double get() = meters / 1000.0
}

/** オドメーター（車のメーター）の記録 */
data class OdoEntry(
    val id: Long,
    val time: Long,
    val km: Double,
    val note: String = "",
)

/** 走行記録とメーター記録を filesDir/data.json に保存する */
object Store {
    private var file: File? = null

    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips

    private val _odos = MutableStateFlow<List<OdoEntry>>(emptyList())
    val odos: StateFlow<List<OdoEntry>> = _odos

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "data.json")
        file = f
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            val t = root.optJSONArray("trips") ?: JSONArray()
            _trips.value = (0 until t.length()).map { i ->
                val o = t.getJSONObject(i)
                Trip(
                    id = o.getLong("id"),
                    start = o.getLong("start"),
                    end = o.getLong("end"),
                    meters = o.getDouble("meters"),
                    note = o.optString("note", ""),
                    manual = o.optBoolean("manual", false),
                )
            }.sortedByDescending { it.start }
            val d = root.optJSONArray("odos") ?: JSONArray()
            _odos.value = (0 until d.length()).map { i ->
                val o = d.getJSONObject(i)
                OdoEntry(
                    id = o.getLong("id"),
                    time = o.getLong("time"),
                    km = o.getDouble("km"),
                    note = o.optString("note", ""),
                )
            }.sortedByDescending { it.time }
        } catch (e: Exception) {
            // 壊れていたら退避して空から始める
            f.renameTo(File(f.parentFile, "data.broken.${System.currentTimeMillis()}.json"))
        }
    }

    private fun newId(): Long = System.currentTimeMillis() * 1000 + (0..999).random()

    @Synchronized
    fun addTrip(start: Long, end: Long, meters: Double, note: String = "", manual: Boolean = false) {
        _trips.value = (_trips.value + Trip(newId(), start, end, meters, note, manual))
            .sortedByDescending { it.start }
        save()
    }

    @Synchronized
    fun updateTrip(trip: Trip) {
        _trips.value = _trips.value.map { if (it.id == trip.id) trip else it }
            .sortedByDescending { it.start }
        save()
    }

    @Synchronized
    fun deleteTrip(id: Long) {
        _trips.value = _trips.value.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun addOdo(time: Long, km: Double, note: String) {
        _odos.value = (_odos.value + OdoEntry(newId(), time, km, note)).sortedByDescending { it.time }
        save()
    }

    @Synchronized
    fun deleteOdo(id: Long) {
        _odos.value = _odos.value.filterNot { it.id == id }
        save()
    }

    private fun save() {
        val f = file ?: return
        val root = JSONObject()
        root.put("trips", JSONArray().apply {
            _trips.value.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id); put("start", t.start); put("end", t.end)
                    put("meters", t.meters); put("note", t.note); put("manual", t.manual)
                })
            }
        })
        root.put("odos", JSONArray().apply {
            _odos.value.forEach { o ->
                put(JSONObject().apply {
                    put("id", o.id); put("time", o.time); put("km", o.km); put("note", o.note)
                })
            }
        })
        // 書きかけで落ちても壊れないよう、一時ファイルに書いてから置き換える
        val tmp = File(f.parentFile, "data.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(root.toString())
            tmp.delete()
        }
    }

    /** 表計算ソフトで開ける CSV */
    fun toCsv(): String {
        val fmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.JAPAN)
        val sb = StringBuilder("開始,終了,距離(km),種別,メモ\n")
        _trips.value.sortedBy { it.start }.forEach { t ->
            sb.append(fmt.format(t.start)).append(',')
                .append(fmt.format(t.end)).append(',')
                .append(String.format(java.util.Locale.US, "%.2f", t.km)).append(',')
                .append(if (t.manual) "手入力" else "GPS").append(',')
                .append('"').append(t.note.replace("\"", "\"\"")).append('"')
                .append('\n')
        }
        if (_odos.value.isNotEmpty()) {
            sb.append("\n日時,メーター(km),メモ\n")
            _odos.value.sortedBy { it.time }.forEach { o ->
                sb.append(fmt.format(o.time)).append(',')
                    .append(String.format(java.util.Locale.US, "%.1f", o.km)).append(',')
                    .append('"').append(o.note.replace("\"", "\"\"")).append('"')
                    .append('\n')
            }
        }
        return sb.toString()
    }
}
