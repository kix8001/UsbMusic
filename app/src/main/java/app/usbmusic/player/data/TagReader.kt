package app.usbmusic.player.data

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 自前で読み取ったIDタグの値 */
class TagInfo {
    var title: String = ""
    var artist: String = ""
    var albumArtist: String = ""
    var album: String = ""
    var track: Int = 0
    var disc: Int = 0
    var titleSort: String = ""
    var artistSort: String = ""
    var albumArtistSort: String = ""
    var albumSort: String = ""
}

/**
 * MP3(ID3v2)・M4A(MP4)・FLAC のタグを直接読む。
 * Androidの標準ライブラリでは読めない「並べ替え用の読み」やディスク番号を正確に取るため。
 */
object TagReader {
    private const val MAX_FIELD = 64 * 1024
    private val NUMBER = Regex("""\d+""")
    private val SJIS: Charset? = runCatching { Charset.forName("MS932") }.getOrNull()

    private fun interface Src {
        fun read(pos: Long, n: Int): ByteArray?
    }

    private class ChannelSrc(val ch: FileChannel) : Src {
        val size: Long = runCatching { ch.size() }.getOrDefault(Long.MAX_VALUE)
        override fun read(pos: Long, n: Int): ByteArray? {
            if (n < 0 || pos < 0 || pos + n > size) return null
            val buf = ByteBuffer.allocate(n)
            var p = pos
            while (buf.hasRemaining()) {
                val r = ch.read(buf, p)
                if (r <= 0) return null
                p += r
            }
            return buf.array()
        }
    }

    private class MemSrc(val data: ByteArray) : Src {
        override fun read(pos: Long, n: Int): ByteArray? {
            if (n < 0 || pos < 0 || pos + n > data.size) return null
            return data.copyOfRange(pos.toInt(), pos.toInt() + n)
        }
    }

    fun read(context: Context, uri: Uri): TagInfo? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val src = ChannelSrc(ch)
                val head = src.read(0, 12) ?: return@use null
                when {
                    head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() ->
                        TagInfo().also { id3(src, it) }
                    String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> TagInfo().also { flac(src, 4, it) }
                    String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> TagInfo().also { mp4(src, it) }
                    u8(head[0]) == 0x1A && u8(head[1]) == 0x45 && u8(head[2]) == 0xDF && u8(head[3]) == 0xA3 ->
                        TagInfo().also { mkv(src, it) }
                    else -> null
                }
            }
        }
    }.getOrNull()

    private fun firstNumber(s: String): Int = NUMBER.find(s)?.value?.toIntOrNull() ?: 0

    private fun u8(b: Byte): Int = b.toInt() and 0xFF
    private fun be32(b: ByteArray, o: Int): Long =
        (u8(b[o]).toLong() shl 24) or (u8(b[o + 1]).toLong() shl 16) or (u8(b[o + 2]).toLong() shl 8) or u8(b[o + 3]).toLong()
    private fun le32(b: ByteArray, o: Int): Long =
        (u8(b[o + 3]).toLong() shl 24) or (u8(b[o + 2]).toLong() shl 16) or (u8(b[o + 1]).toLong() shl 8) or u8(b[o]).toLong()
    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    // ---------------- ID3v2 ----------------

    private fun deUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (u8(b[i]) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun id3(file: Src, info: TagInfo) {
        val h = file.read(0, 10) ?: return
        val ver = u8(h[3])
        if (ver !in 2..4) return
        val flags = u8(h[5])
        val size = syncsafe(h, 6)
        var src: Src = file
        var base = 10L
        if (flags and 0x80 != 0 && ver < 4) {
            val all = file.read(10, size.coerceAtMost(16 * 1024 * 1024)) ?: return
            src = MemSrc(deUnsync(all))
            base = 0
        }
        var pos = base
        val end = base + size
        if (flags and 0x40 != 0 && ver >= 3) {
            val e = src.read(pos, 4) ?: return
            pos += if (ver == 3) 4 + be32(e, 0) else syncsafe(e, 0).toLong()
        }
        val hdrLen = if (ver == 2) 6 else 10
        while (pos + hdrLen <= end) {
            val fh = src.read(pos, hdrLen) ?: return
            if (fh[0].toInt() == 0) return
            val id: String
            val fsize: Long
            var fflags = 0
            if (ver == 2) {
                id = String(fh, 0, 3, Charsets.ISO_8859_1)
                fsize = ((u8(fh[3]) shl 16) or (u8(fh[4]) shl 8) or u8(fh[5])).toLong()
            } else {
                id = String(fh, 0, 4, Charsets.ISO_8859_1)
                fsize = if (ver == 4) syncsafe(fh, 4).toLong() else be32(fh, 4)
                fflags = u8(fh[9])
            }
            if (fsize <= 0 || pos + hdrLen + fsize > end + 4) return
            val key = id3Key(id)
            if (key != null && fsize <= MAX_FIELD) {
                var data = src.read(pos + hdrLen, fsize.toInt())
                if (data != null) {
                    var ok = true
                    if (ver == 3 && fflags and 0xC0 != 0) ok = false // 圧縮・暗号化
                    if (ver == 4) {
                        if (fflags and 0x0C != 0) ok = false
                        if (fflags and 0x02 != 0) data = deUnsync(data)
                        if (fflags and 0x01 != 0 && data.size > 4) data = data.copyOfRange(4, data.size)
                    }
                    if (ver == 3 && fflags and 0x20 != 0 && data.isNotEmpty()) data = data.copyOfRange(1, data.size)
                    if (ok) assign(info, key, id3Text(data))
                }
            }
            pos += hdrLen + fsize
        }
    }

    private fun id3Key(id: String): String? = when (id) {
        "TIT2", "TT2" -> "title"
        "TPE1", "TP1" -> "artist"
        "TPE2", "TP2" -> "albumartist"
        "TALB", "TAL" -> "album"
        "TRCK", "TRK" -> "track"
        "TPOS", "TPA" -> "disc"
        "TSOT", "TST" -> "titlesort"
        "TSOP", "TSP" -> "artistsort"
        "TSO2", "TS2" -> "albumartistsort"
        "TSOA", "TSA" -> "albumsort"
        else -> null
    }

    private fun id3Text(d: ByteArray): String {
        if (d.isEmpty()) return ""
        val body = d.copyOfRange(1, d.size)
        val s = when (d[0].toInt()) {
            1 -> String(body, Charsets.UTF_16)
            2 -> String(body, Charsets.UTF_16BE)
            3 -> String(body, Charsets.UTF_8)
            else -> latin1OrSjis(body)
        }
        return s.split('\u0000').firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    /** 古い日本語のタグは Shift_JIS で書かれていることが多いので、それらしければ Shift_JIS で読む */
    private fun latin1OrSjis(b: ByteArray): String {
        if (b.all { it >= 0 } || SJIS == null) return String(b, Charsets.ISO_8859_1)
        val trimmed = b.takeWhile { it.toInt() != 0 }.toByteArray()
        return try {
            val dec = SJIS.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val s = dec.decode(ByteBuffer.wrap(trimmed)).toString()
            if (s.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF || it.code in 0xFF00..0xFFEF }) s
            else String(b, Charsets.ISO_8859_1)
        } catch (e: CharacterCodingException) {
            String(b, Charsets.ISO_8859_1)
        }
    }

    // ---------------- FLAC ----------------

    private fun flac(src: Src, start: Long, info: TagInfo) {
        var pos = start
        repeat(64) {
            val h = src.read(pos, 4) ?: return
            val last = u8(h[0]) and 0x80 != 0
            val type = u8(h[0]) and 0x7F
            val len = (u8(h[1]) shl 16) or (u8(h[2]) shl 8) or u8(h[3])
            if (type == 4 && len <= 4 * 1024 * 1024) {
                val d = src.read(pos + 4, len) ?: return
                vorbis(d, info)
                return
            }
            pos += 4 + len
            if (last) return
        }
    }

    private fun vorbis(d: ByteArray, info: TagInfo) {
        var p = 0
        if (d.size < 8) return
        p += 4 + le32(d, 0).toInt()
        if (p + 4 > d.size) return
        val count = le32(d, p).toInt()
        p += 4
        for (i in 0 until count.coerceAtMost(1000)) {
            if (p + 4 > d.size) return
            val len = le32(d, p).toInt()
            p += 4
            if (len < 0 || p + len > d.size) return
            val kv = String(d, p, len, Charsets.UTF_8)
            p += len
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val key = when (kv.substring(0, eq).uppercase()) {
                "TITLE" -> "title"
                "ARTIST" -> "artist"
                "ALBUMARTIST", "ALBUM ARTIST" -> "albumartist"
                "ALBUM" -> "album"
                "TRACKNUMBER" -> "track"
                "DISCNUMBER" -> "disc"
                "TITLESORT" -> "titlesort"
                "ARTISTSORT" -> "artistsort"
                "ALBUMARTISTSORT" -> "albumartistsort"
                "ALBUMSORT" -> "albumsort"
                else -> null
            } ?: continue
            assign(info, key, kv.substring(eq + 1).trim())
        }
    }

    // ---------------- MP4 / M4A ----------------

    private class Box(val type: String, val start: Long, val end: Long)

    private fun children(src: Src, start: Long, end: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = start
        while (pos + 8 <= end && out.size < 512) {
            val h = src.read(pos, 8) ?: break
            var size = be32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) {
                val l = src.read(pos + 8, 8) ?: break
                size = (be32(l, 0) shl 32) or be32(l, 4)
                header = 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < header || pos + size > end) break
            out += Box(type, pos + header, pos + size)
            pos += size
        }
        return out
    }

    private fun mp4(src: ChannelSrc, info: TagInfo) {
        val moov = children(src, 0, src.size).firstOrNull { it.type == "moov" } ?: return
        val udta = children(src, moov.start, moov.end).firstOrNull { it.type == "udta" } ?: return
        val meta = children(src, udta.start, udta.end).firstOrNull { it.type == "meta" } ?: return
        // meta は通常「バージョン4バイト」が先頭に付く
        val probe = src.read(meta.start + 4, 8)
        val metaStart = if (probe != null && String(probe, 4, 4, Charsets.ISO_8859_1) == "hdlr") meta.start + 4 else meta.start
        val ilst = children(src, metaStart, meta.end).firstOrNull { it.type == "ilst" } ?: return
        for (item in children(src, ilst.start, ilst.end)) {
            val key = when (item.type) {
                "©nam" -> "title"
                "©ART" -> "artist"
                "aART" -> "albumartist"
                "©alb" -> "album"
                "trkn" -> "trkn"
                "disk" -> "disk"
                "sonm" -> "titlesort"
                "soar" -> "artistsort"
                "soaa" -> "albumartistsort"
                "soal" -> "albumsort"
                "tvsh" -> "album" // テレビ番組名（シリーズ）
                else -> null
            } ?: continue
            val data = children(src, item.start, item.end).firstOrNull { it.type == "data" } ?: continue
            val len = (data.end - data.start - 8).toInt()
            if (len <= 0 || len > MAX_FIELD) continue
            val payload = src.read(data.start + 8, len) ?: continue
            when (key) {
                "trkn" -> if (payload.size >= 4) info.track = (u8(payload[2]) shl 8) or u8(payload[3])
                "disk" -> if (payload.size >= 4) info.disc = (u8(payload[2]) shl 8) or u8(payload[3])
                else -> assign(info, key, String(payload, Charsets.UTF_8).trim('\u0000').trim())
            }
        }
    }

    /** MP4/M4V に埋め込まれたカバー画像（動画のサムネイル用） */
    fun mp4Cover(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val src = ChannelSrc(ch)
                val head = src.read(0, 8) ?: return@use null
                if (String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return@use null
                val moov = children(src, 0, src.size).firstOrNull { it.type == "moov" } ?: return@use null
                val udta = children(src, moov.start, moov.end).firstOrNull { it.type == "udta" } ?: return@use null
                val meta = children(src, udta.start, udta.end).firstOrNull { it.type == "meta" } ?: return@use null
                val probe = src.read(meta.start + 4, 8)
                val ms = if (probe != null && String(probe, 4, 4, Charsets.ISO_8859_1) == "hdlr") meta.start + 4 else meta.start
                val ilst = children(src, ms, meta.end).firstOrNull { it.type == "ilst" } ?: return@use null
                val covr = children(src, ilst.start, ilst.end).firstOrNull { it.type == "covr" } ?: return@use null
                val data = children(src, covr.start, covr.end).firstOrNull { it.type == "data" } ?: return@use null
                val len = (data.end - data.start - 8).toInt()
                if (len <= 0 || len > 8 * 1024 * 1024) null else src.read(data.start + 8, len)
            }
        }
    }.getOrNull()

    // ---------------- MKV（Matroska） ----------------

    private class Elem(val id: Long, val start: Long, val end: Long)

    /** 要素ID（先頭ビットを含めたまま） */
    private fun readId(src: Src, pos: Long): Pair<Long, Int>? {
        val b0 = src.read(pos, 1)?.get(0)?.let { u8(it) } ?: return null
        val len = when {
            b0 and 0x80 != 0 -> 1
            b0 and 0x40 != 0 -> 2
            b0 and 0x20 != 0 -> 3
            b0 and 0x10 != 0 -> 4
            else -> return null
        }
        val b = src.read(pos, len) ?: return null
        var v = 0L
        for (x in b) v = (v shl 8) or u8(x).toLong()
        return v to len
    }

    /** サイズ（先頭ビットを除く）。不明サイズは -1 */
    private fun readSize(src: Src, pos: Long): Pair<Long, Int>? {
        val b0 = src.read(pos, 1)?.get(0)?.let { u8(it) } ?: return null
        var len = 1
        var mask = 0x80
        while (len <= 8 && b0 and mask == 0) {
            len++
            mask = mask shr 1
        }
        if (len > 8) return null
        val b = src.read(pos, len) ?: return null
        var v = (b0 and (mask - 1)).toLong()
        var allOnes = v == (mask - 1).toLong()
        for (i in 1 until len) {
            v = (v shl 8) or u8(b[i]).toLong()
            if (u8(b[i]) != 0xFF) allOnes = false
        }
        return (if (allOnes) -1L else v) to len
    }

    private fun elemAt(src: Src, pos: Long, limit: Long): Elem? {
        val (id, il) = readId(src, pos) ?: return null
        val (size, sl) = readSize(src, pos + il) ?: return null
        val start = pos + il + sl
        val end = if (size < 0) limit else start + size
        if (end > limit || end < start) return null
        return Elem(id, start, end)
    }

    private fun elems(src: Src, start: Long, end: Long, max: Int = 256): List<Elem> {
        val out = ArrayList<Elem>()
        var p = start
        while (p < end && out.size < max) {
            val e = elemAt(src, p, end) ?: break
            out += e
            if (e.end <= p) break
            p = e.end
        }
        return out
    }

    private fun uint(src: Src, e: Elem): Long {
        val n = (e.end - e.start).toInt()
        if (n !in 1..8) return 0
        val b = src.read(e.start, n) ?: return 0
        var v = 0L
        for (x in b) v = (v shl 8) or u8(x).toLong()
        return v
    }

    private fun str(src: Src, e: Elem): String {
        val n = (e.end - e.start).toInt()
        if (n !in 1..MAX_FIELD) return ""
        return String(src.read(e.start, n) ?: return "", Charsets.UTF_8).trim('\u0000').trim()
    }

    private const val MKV_SEGMENT = 0x18538067L
    private const val MKV_SEEKHEAD = 0x114D9B74L
    private const val MKV_INFO = 0x1549A966L
    private const val MKV_TAGS = 0x1254C367L
    private const val MKV_CLUSTER = 0x1F43B675L

    private fun mkv(src: ChannelSrc, info: TagInfo) {
        val header = elemAt(src, 0, src.size) ?: return
        val seg = elemAt(src, header.end, src.size) ?: return
        if (seg.id != MKV_SEGMENT) return
        val targets = LinkedHashMap<Long, Long>() // 要素ID → 位置
        var infoTitle = ""
        var p = seg.start
        var count = 0
        // 先頭から順に見ていき、映像データ（Cluster）に着いたら目次（SeekHead）で残りを探す
        while (p < seg.end && count++ < 64) {
            val e = elemAt(src, p, seg.end) ?: break
            when (e.id) {
                MKV_SEEKHEAD -> for (seek in elems(src, e.start, e.end)) {
                    var sid = 0L
                    var spos = -1L
                    for (c in elems(src, seek.start, seek.end)) {
                        if (c.id == 0x53ABL) sid = uint(src, c)
                        if (c.id == 0x53ACL) spos = uint(src, c)
                    }
                    if ((sid == MKV_INFO || sid == MKV_TAGS) && spos >= 0) targets.putIfAbsent(sid, seg.start + spos)
                }
                MKV_INFO -> {
                    infoTitle = mkvInfoTitle(src, e)
                    targets.remove(MKV_INFO)
                }
                MKV_TAGS -> {
                    mkvTags(src, e, info)
                    targets.remove(MKV_TAGS)
                }
                MKV_CLUSTER -> break
            }
            if (e.end <= p) break
            p = e.end
        }
        for ((id, pos) in targets) {
            val e = elemAt(src, pos, seg.end) ?: continue
            if (e.id != id) continue
            if (id == MKV_INFO) infoTitle = mkvInfoTitle(src, e) else mkvTags(src, e, info)
        }
        if (info.title.isEmpty() && infoTitle.isNotEmpty()) info.title = infoTitle
    }

    private fun mkvInfoTitle(src: Src, e: Elem): String =
        elems(src, e.start, e.end).firstOrNull { it.id == 0x7BA9L }?.let { str(src, it) }.orEmpty()

    private fun mkvTags(src: Src, tags: Elem, info: TagInfo) {
        for (tag in elems(src, tags.start, tags.end)) {
            if (tag.id != 0x7373L) continue
            var level = 50L
            val simple = ArrayList<Elem>()
            for (c in elems(src, tag.start, tag.end)) {
                if (c.id == 0x63C0L) {
                    elems(src, c.start, c.end).firstOrNull { it.id == 0x68CAL }?.let { level = uint(src, it) }
                } else if (c.id == 0x67C8L) simple += c
            }
            for (st in simple) {
                var name = ""
                var value = ""
                var sort = ""
                for (c in elems(src, st.start, st.end)) {
                    when (c.id) {
                        0x45A3L -> name = str(src, c).uppercase()
                        0x4487L -> value = str(src, c)
                        0x67C8L -> { // 入れ子の SORT_WITH（読み）
                            var n2 = ""
                            var v2 = ""
                            for (d in elems(src, c.start, c.end)) {
                                if (d.id == 0x45A3L) n2 = str(src, d).uppercase()
                                if (d.id == 0x4487L) v2 = str(src, d)
                            }
                            if (n2 == "SORT_WITH") sort = v2
                        }
                    }
                }
                when (name) {
                    // 70以上は「シリーズ・作品集」、それ未満は1本ごとのタイトル
                    "TITLE" -> if (level >= 70) {
                        assign(info, "album", value)
                    } else {
                        assign(info, "title", value)
                        assign(info, "titlesort", sort)
                    }
                    "ARTIST", "DIRECTOR", "LEAD_PERFORMER" -> {
                        assign(info, "artist", value)
                        assign(info, "artistsort", sort)
                    }
                    "ALBUM" -> assign(info, "album", value)
                    "ARTISTSORT" -> assign(info, "artistsort", value)
                    "TITLESORT" -> assign(info, "titlesort", value)
                }
            }
        }
    }

    private fun assign(info: TagInfo, key: String, v: String) {
        if (v.isBlank()) return
        when (key) {
            "title" -> if (info.title.isEmpty()) info.title = v
            "artist" -> if (info.artist.isEmpty()) info.artist = v
            "albumartist" -> if (info.albumArtist.isEmpty()) info.albumArtist = v
            "album" -> if (info.album.isEmpty()) info.album = v
            "track" -> if (info.track == 0) info.track = firstNumber(v)
            "disc" -> if (info.disc == 0) info.disc = firstNumber(v)
            "titlesort" -> if (info.titleSort.isEmpty()) info.titleSort = v
            "artistsort" -> if (info.artistSort.isEmpty()) info.artistSort = v
            "albumartistsort" -> if (info.albumArtistSort.isEmpty()) info.albumArtistSort = v
            "albumsort" -> if (info.albumSort.isEmpty()) info.albumSort = v
        }
    }
}
