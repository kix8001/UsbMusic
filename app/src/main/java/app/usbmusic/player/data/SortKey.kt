package app.usbmusic.player.data

import java.text.CollationKey
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * 並び順と索引（A〜Z・あ〜わ）の計算。
 * 全角/半角・カタカナ/ひらがなを揃え、英語名の先頭 "The " は無視する（CarPlayと同じ）。
 */
object SortKey {
    val GROUPS: List<String> =
        ('A'..'Z').map { it.toString() } +
            listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ", "漢", "#")

    private val KANA_ROW: Map<Char, String> = buildMap {
        val rows = listOf(
            "あ" to "ぁあぃいぅうぇえぉおゔ",
            "か" to "かきくけこゕゖ",
            "さ" to "さしすせそ",
            "た" to "たちっつてと",
            "な" to "なにぬねの",
            "は" to "はひふへほ",
            "ま" to "まみむめも",
            "や" to "ゃやゅゆょよ",
            "ら" to "らりるれろ",
            "わ" to "ゎわゐゑをん",
        )
        for ((row, chars) in rows) for (ch in chars) put(ch, row)
    }

    fun toHiragana(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }

    fun normalize(raw: String): String {
        var t = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        if (t.length > 4 && t.startsWith("the ", ignoreCase = true)) t = t.substring(4)
        return toHiragana(t)
    }

    fun searchNorm(raw: String): String =
        toHiragana(Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)).lowercase()

    /** 正規化済みの文字列が属する索引グループ */
    fun group(norm: String): String {
        val c = norm.firstOrNull() ?: return "#"
        if (c in 'a'..'z' || c in 'A'..'Z') return c.uppercaseChar().toString()
        if (c in 'ぁ'..'ゖ') {
            val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
            return KANA_ROW[base] ?: "あ"
        }
        if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN) return "漢"
        return "#"
    }

    fun groupOf(raw: String): String = group(normalize(raw))

    fun <T> sorted(list: List<T>, name: (T) -> String): List<T> {
        val collator = Collator.getInstance(Locale.JAPANESE).apply { strength = Collator.TERTIARY }
        return list
            .map { item ->
                val n = normalize(name(item))
                Triple(item, GROUPS.indexOf(group(n)), collator.getCollationKey(n))
            }
            .sortedWith(compareBy<Triple<T, Int, CollationKey>> { it.second }.thenBy { it.third })
            .map { it.first }
    }
}
