package app.mileage.log

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 走行の区分。通勤・業務はガソリン代の支給対象、私用は対象外 */
enum class Category(val label: String) {
    UNSET("未分類"), COMMUTE("通勤"), BUSINESS("業務"), PRIVATE("私用");

    companion object {
        fun of(name: String?): Category = entries.firstOrNull { it.name == name } ?: UNSET
    }
}

/** 1回の走行。GPSで測ったもの（manual=false）と手入力したもの（manual=true）がある */
data class Trip(
    val id: Long,
    val start: Long,
    val end: Long,
    val meters: Double,
    val note: String = "",
    val manual: Boolean = false,
    val category: Category = Category.UNSET,
    /** 行き先（訪問先の名前など） */
    val destination: String = "",
    /** 用件 */
    val purpose: String = "",
    /** 出発地・到着地の住所（GPS の位置から自動で入れる。手で直してもよい） */
    val startAddr: String = "",
    val endAddr: String = "",
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
) {
    val km: Double get() = meters / 1000.0
    /** 申請に使う距離（0.1km 単位に四捨五入） */
    val reportKm: Double get() = Math.round(meters / 100.0) / 10.0
}

/** オドメーター（車のメーター）の記録 */
data class OdoEntry(
    val id: Long,
    val time: Long,
    val km: Double,
    val note: String = "",
)

data class Settings(
    /** 1km あたりの支給額（円） */
    val commuteRate: Double = 18.0,
    val businessRate: Double = 36.0,
    /** レポートに載せる氏名 */
    val name: String = "",
    /** 次に計測する走行の区分（計測画面で選ぶ）。基本は業務 */
    val nextCategory: Category = Category.BUSINESS,
) {
    fun rate(c: Category): Double = when (c) {
        Category.COMMUTE -> commuteRate
        Category.BUSINESS -> businessRate
        else -> 0.0
    }
}

/** 走行記録・メーター記録・設定を filesDir/data.json に保存する */
object Store {
    private var file: File? = null

    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips

    private val _odos = MutableStateFlow<List<OdoEntry>>(emptyList())
    val odos: StateFlow<List<OdoEntry>> = _odos

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "data.json")
        file = f
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            val t = root.optJSONArray("trips") ?: JSONArray()
            _trips.value = (0 until t.length()).map { i -> tripFrom(t.getJSONObject(i)) }
                .sortedByDescending { it.start }
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
            root.optJSONObject("settings")?.let { s ->
                _settings.value = Settings(
                    commuteRate = s.optDouble("commuteRate", 18.0),
                    businessRate = s.optDouble("businessRate", 36.0),
                    name = s.optString("name", ""),
                    nextCategory = if (s.has("nextCategory")) Category.of(s.optString("nextCategory")) else Category.BUSINESS,
                )
            }
        } catch (e: Exception) {
            // 壊れていたら退避して空から始める
            f.renameTo(File(f.parentFile, "data.broken.${System.currentTimeMillis()}.json"))
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun tripFrom(o: JSONObject) = Trip(
        id = o.getLong("id"),
        start = o.getLong("start"),
        end = o.getLong("end"),
        meters = o.getDouble("meters"),
        note = o.optString("note", ""),
        manual = o.optBoolean("manual", false),
        category = Category.of(o.optString("category")),
        destination = o.optString("destination", ""),
        purpose = o.optString("purpose", ""),
        startAddr = o.optString("startAddr", ""),
        endAddr = o.optString("endAddr", ""),
        startLat = o.optDoubleOrNull("startLat"),
        startLon = o.optDoubleOrNull("startLon"),
        endLat = o.optDoubleOrNull("endLat"),
        endLon = o.optDoubleOrNull("endLon"),
    )

    private fun tripTo(t: Trip) = JSONObject().apply {
        put("id", t.id); put("start", t.start); put("end", t.end)
        put("meters", t.meters); put("note", t.note); put("manual", t.manual)
        put("category", t.category.name)
        put("destination", t.destination); put("purpose", t.purpose)
        put("startAddr", t.startAddr); put("endAddr", t.endAddr)
        t.startLat?.let { put("startLat", it) }; t.startLon?.let { put("startLon", it) }
        t.endLat?.let { put("endLat", it) }; t.endLon?.let { put("endLon", it) }
    }

    fun newId(): Long = System.currentTimeMillis() * 1000 + (0..999).random()

    @Synchronized
    fun addTrip(trip: Trip) {
        _trips.value = (_trips.value + trip).sortedByDescending { it.start }
        save()
    }

    @Synchronized
    fun updateTrip(trip: Trip) {
        _trips.value = _trips.value.map { if (it.id == trip.id) trip else it }
            .sortedByDescending { it.start }
        save()
    }

    /** 住所の自動入力など、今の値をもとに書き換える（別スレッドからでも安全） */
    @Synchronized
    fun modifyTrip(id: Long, f: (Trip) -> Trip) {
        val cur = _trips.value.firstOrNull { it.id == id } ?: return
        updateTrip(f(cur))
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

    @Synchronized
    fun updateSettings(s: Settings) {
        _settings.value = s
        save()
    }

    private fun save() {
        val f = file ?: return
        val root = JSONObject()
        root.put("trips", JSONArray().apply { _trips.value.forEach { put(tripTo(it)) } })
        root.put("odos", JSONArray().apply {
            _odos.value.forEach { o ->
                put(JSONObject().apply {
                    put("id", o.id); put("time", o.time); put("km", o.km); put("note", o.note)
                })
            }
        })
        val s = _settings.value
        root.put("settings", JSONObject().apply {
            put("commuteRate", s.commuteRate); put("businessRate", s.businessRate)
            put("name", s.name); put("nextCategory", s.nextCategory.name)
        })
        // 書きかけで落ちても壊れないよう、一時ファイルに書いてから置き換える
        val tmp = File(f.parentFile, "data.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(root.toString())
            tmp.delete()
        }
    }
}
