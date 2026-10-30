package app.usbmusic.player.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "LibraryRepository"

private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "mp4", "3gp", "amr", "mka")
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
private val COVER_NAMES = setOf("cover", "folder", "front", "album", "albumart", "albumartsmall", "jacket")
private val NUMBER = Regex("""\d+""")

private fun firstNumber(s: String?): Int = s?.let { NUMBER.find(it)?.value?.toIntOrNull() } ?: 0

/**
 * USBメモリ内の音楽を読み込む。
 *  - 自動モード: Android標準のメディアライブラリ(MediaStore)から、USB(リムーバブル)ボリュームの曲を取得
 *  - フォルダモード: 指定フォルダを直接たどり、各ファイルのIDタグを読む（表示されない曲がある時用）
 */
class LibraryRepository(private val context: Context, private val prefs: Prefs) {

    private val cacheFile = File(context.filesDir, "library.json")

    // ---------- キャッシュ（起動直後にすぐ一覧を出すため） ----------

    fun loadCache(): List<Track>? = runCatching {
        if (!cacheFile.exists()) return null
        val root = JSONObject(cacheFile.readText())
        if (root.optString("mode") != prefs.sourceMode) return null
        val arr = root.getJSONArray("tracks")
        List(arr.length()) { i -> arr.getJSONArray(i).toTrack() }
    }.getOrElse {
        Log.w(TAG, "cache read failed", it)
        null
    }

    private fun saveCache(tracks: List<Track>) {
        runCatching {
            val arr = JSONArray()
            tracks.forEach { arr.put(it.toJson()) }
            val root = JSONObject().put("v", 1).put("mode", prefs.sourceMode).put("tracks", arr)
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(cacheFile)
        }.onFailure { Log.w(TAG, "cache write failed", it) }
    }

    private fun Track.toJson(): JSONArray = JSONArray()
        .put(uri).put(title).put(artist).put(albumArtist).put(album)
        .put(trackNo).put(discNo).put(year).put(durationMs).put(folder)
        .put(cover ?: "").put(fromMediaStore).put(lastModified)

    private fun JSONArray.toTrack(): Track = Track(
        uri = getString(0), title = getString(1), artist = getString(2),
        albumArtist = getString(3), album = getString(4),
        trackNo = getInt(5), discNo = getInt(6), year = getInt(7),
        durationMs = getLong(8), folder = getString(9),
        cover = getString(10).ifEmpty { null }, fromMediaStore = getBoolean(11),
        lastModified = getLong(12),
    )

    // ---------- 読み込み ----------

    /** 直近の読み込みの診断情報（曲が見つからない時に画面へ表示する） */
    @Volatile
    var report: List<String> = emptyList()
        private set

    suspend fun scan(progress: (Int) -> Unit): List<Track> = withContext(Dispatchers.IO) {
        val log = ArrayList<String>()
        val previous = loadCache()?.filter { !it.fromMediaStore }?.associateBy { it.uri } ?: emptyMap()
        val result = try {
            if (prefs.sourceMode == Prefs.MODE_FOLDER) {
                val tree = prefs.treeUri?.let(Uri::parse)
                if (tree == null) {
                    log += "フォルダが選ばれていません"
                    emptyList()
                } else {
                    log += "選択フォルダ: ${DocumentsContract.getTreeDocumentId(tree)}"
                    val files = ArrayList<FileEntry>()
                    val covers = HashMap<String, String>()
                    val anyImage = HashMap<String, String>()
                    val stats = WalkStats()
                    walk(tree, DocumentsContract.getTreeDocumentId(tree), "", files, covers, anyImage, 0, stats)
                    log += "フォルダ ${stats.dirs}個 / ファイル ${stats.entries}個を確認、音楽 ${files.size}曲"
                    if (stats.error != null) log += "エラー: ${stats.error}"
                    readAll(files, covers, anyImage, previous, progress)
                }
            } else {
                volumeReport(log)
                val fromStore = scanMediaStore(log)
                val usbCount = fromStore.count { !it.folder.startsWith(MediaStore.VOLUME_EXTERNAL_PRIMARY + "/") }
                if (usbCount > 0) {
                    fromStore
                } else {
                    // ナビのメディアライブラリにUSBが登録されていない場合は、USBのファイルを直接探す
                    log += "→ USBのファイルを直接探します"
                    fromStore + scanFilePaths(log, previous, progress)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log += "読み込みエラー: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "scan failed", e)
            emptyList()
        }
        report = log
        if (result.isNotEmpty() || prefs.sourceMode == Prefs.MODE_FOLDER) saveCache(result)
        result
    }

    private suspend fun readAll(
        files: List<FileEntry>,
        covers: HashMap<String, String>,
        anyImage: Map<String, String>,
        previous: Map<String, Track>,
        progress: (Int) -> Unit,
    ): List<Track> = coroutineScope {
        for ((folder, img) in anyImage) covers.putIfAbsent(folder, img)
        val out = ArrayList<Track>(files.size)
        files.forEachIndexed { i, f ->
            ensureActive()
            val cover = coverFor(f.folder, covers)
            val prev = previous[f.uri]
            out += if (prev != null && prev.lastModified == f.lastModified && f.lastModified != 0L) {
                prev.copy(cover = cover)
            } else {
                readTags(f, cover)
            }
            if (i % 10 == 0) progress(i)
        }
        progress(files.size)
        out
    }

    private fun volumeReport(log: MutableList<String>) {
        runCatching {
            val sm = context.getSystemService(StorageManager::class.java) ?: return
            for (v in sm.storageVolumes) {
                val path = if (Build.VERSION.SDK_INT >= 30) v.directory?.path ?: "-" else "-"
                val name = if (Build.VERSION.SDK_INT >= 30) v.mediaStoreVolumeName ?: "-" else "-"
                log += "ストレージ: ${v.getDescription(context)} / ${if (v.isRemovable) "取り外し可" else "本体"} / ${v.state} / $path / $name"
            }
        }.onFailure { log += "ストレージ一覧エラー: ${it.message}" }
    }

    // ---------- ファイルを直接探す（予備） ----------

    private fun candidateRoots(): List<File> {
        val roots = LinkedHashMap<String, File>()
        fun add(f: File?) {
            if (f == null) return
            val key = runCatching { f.canonicalPath }.getOrDefault(f.path)
            if (!roots.containsKey(key)) roots[key] = f
        }
        if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(StorageManager::class.java)?.storageVolumes
                ?.filter { it.isRemovable }?.forEach { add(it.directory) }
        }
        File("/storage").listFiles()?.forEach {
            if (it.name != "emulated" && it.name != "self" && it.isDirectory) add(it)
        }
        listOf("/mnt/media_rw", "/mnt/usb", "/mnt/usb_storage", "/mnt/udisk", "/mnt/usbhost", "/mnt/usbotg").forEach { p ->
            val d = File(p)
            if (d.isDirectory) {
                val kids = d.listFiles()?.filter { it.isDirectory }.orEmpty()
                if (kids.isEmpty()) add(d) else kids.forEach(::add)
            }
        }
        if (prefs.includeInternal) add(File("/storage/emulated/0/Music"))
        return roots.values.toList()
    }

    private suspend fun scanFilePaths(
        log: MutableList<String>,
        previous: Map<String, Track>,
        progress: (Int) -> Unit,
    ): List<Track> {
        val files = ArrayList<FileEntry>()
        val covers = HashMap<String, String>()
        val anyImage = HashMap<String, String>()
        val roots = candidateRoots()
        if (roots.isEmpty()) log += "直接検索: 候補の場所が見つかりません"
        for (root in roots) {
            val before = files.size
            val listed = root.listFiles()
            if (listed == null) {
                log += "直接検索 ${root.path}: 読み取れません（権限なし）"
                continue
            }
            walkFiles(root, root.name + "/", files, covers, anyImage, 0)
            log += "直接検索 ${root.path}: 音楽 ${files.size - before}曲"
        }
        return readAll(files, covers, anyImage, previous, progress)
    }

    private fun walkFiles(
        dir: File, path: String,
        files: MutableList<FileEntry>, covers: MutableMap<String, String>, anyImage: MutableMap<String, String>,
        depth: Int,
    ) {
        if (depth > 12) return
        val list = dir.listFiles() ?: return
        val dirs = ArrayList<File>()
        for (f in list) {
            val name = f.name
            if (name.startsWith(".") || name == "Android" || name == "LOST.DIR") continue
            if (f.isDirectory) {
                dirs += f
                continue
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            val uri = Uri.fromFile(f).toString()
            if (ext in AUDIO_EXT) {
                files += FileEntry(uri, name, path, f.lastModified())
            } else if (ext in IMAGE_EXT) {
                val stem = name.substringBeforeLast('.').lowercase()
                if (stem in COVER_NAMES) covers[path] = uri else anyImage.putIfAbsent(path, uri)
            }
        }
        for (d in dirs) walkFiles(d, path + d.name + "/", files, covers, anyImage, depth + 1)
    }

    /** 接続中のUSB（リムーバブル）ボリューム名 */
    fun usbVolumeLabels(): List<String> {
        val labels = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 30) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.filter { it.isRemovable }?.forEach { v ->
                labels += v.getDescription(context) ?: (v.mediaStoreVolumeName ?: "USB")
            }
        } else {
            labels += MediaStore.getExternalVolumeNames(context)
                .filter { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
        }
        return labels
    }

    private fun scanMediaStore(log: MutableList<String>): List<Track> {
        val out = ArrayList<Track>()
        val volumes = MediaStore.getExternalVolumeNames(context)
        for (vol in volumes) {
            val isPrimary = vol == MediaStore.VOLUME_EXTERNAL_PRIMARY
            if (isPrimary && !prefs.includeInternal) {
                log += "メディアライブラリ $vol: 本体のため対象外"
                continue
            }
            val before = out.size
            val base = MediaStore.Audio.Media.getContentUri(vol)
            val projection = arrayOf(
                "_id", "title", "artist", "album", "album_artist", "track", "disc_number",
                "year", "duration", "relative_path", "_display_name", "date_modified",
            )
            val selection = if (isPrimary) "is_music != 0" else null
            try {
                context.contentResolver.query(base, projection, selection, null, null)?.use { c ->
                    val iId = c.getColumnIndex("_id")
                    val iTitle = c.getColumnIndex("title")
                    val iArtist = c.getColumnIndex("artist")
                    val iAlbum = c.getColumnIndex("album")
                    val iAlbumArtist = c.getColumnIndex("album_artist")
                    val iTrack = c.getColumnIndex("track")
                    val iDisc = c.getColumnIndex("disc_number")
                    val iYear = c.getColumnIndex("year")
                    val iDuration = c.getColumnIndex("duration")
                    val iPath = c.getColumnIndex("relative_path")
                    val iName = c.getColumnIndex("_display_name")
                    val iModified = c.getColumnIndex("date_modified")
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        val rel = c.str(iPath)
                        val folderName = rel.trimEnd('/').substringAfterLast('/')
                        val displayName = c.str(iName)
                        val trackRaw = c.num(iTrack)
                        val disc = firstNumber(c.str(iDisc)).takeIf { it > 0 }
                            ?: (trackRaw / 1000).takeIf { it > 0 } ?: 1
                        out += Track(
                            uri = ContentUris.withAppendedId(base, id).toString(),
                            title = c.str(iTitle).ifBlank { displayName.substringBeforeLast('.') },
                            artist = c.str(iArtist).unknownToBlank(),
                            albumArtist = c.str(iAlbumArtist).unknownToBlank(),
                            album = c.str(iAlbum).unknownToBlank().ifBlank { folderName.ifBlank { UNKNOWN_ALBUM } },
                            trackNo = trackRaw % 1000,
                            discNo = disc,
                            year = c.num(iYear).let { if (it in 1..9999) it else 0 },
                            durationMs = c.long(iDuration),
                            folder = "$vol/$rel",
                            fromMediaStore = true,
                            lastModified = c.long(iModified),
                        )
                    }
                }
                log += "メディアライブラリ $vol: ${out.size - before}曲"
            } catch (e: Exception) {
                log += "メディアライブラリ $vol: エラー ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "MediaStore query failed for $vol", e)
            }
        }
        return out
    }

    private fun String.unknownToBlank(): String = if (this == "<unknown>") "" else this

    private fun Cursor.str(i: Int): String = if (i < 0 || isNull(i)) "" else getString(i)?.trim().orEmpty()
    private fun Cursor.num(i: Int): Int = if (i < 0 || isNull(i)) 0 else runCatching { getInt(i) }.getOrDefault(0)
    private fun Cursor.long(i: Int): Long = if (i < 0 || isNull(i)) 0L else runCatching { getLong(i) }.getOrDefault(0L)

    // ---------- フォルダ直接読み込み ----------

    private data class FileEntry(val uri: String, val name: String, val folder: String, val lastModified: Long)

    private class WalkStats {
        var dirs = 0
        var entries = 0
        var error: String? = null
    }

    private fun walk(
        tree: Uri, docId: String, path: String,
        files: MutableList<FileEntry>, covers: MutableMap<String, String>, anyImage: MutableMap<String, String>,
        depth: Int, stats: WalkStats,
    ) {
        stats.dirs++
        if (depth > 12) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val dirs = ArrayList<Pair<String, String>>()
        try {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    stats.entries++
                    val id = c.getString(0)
                    val name = c.getString(1)
                    val mime = c.getString(2).orEmpty()
                    val modified = if (c.isNull(3)) 0L else c.getLong(3)
                    if (id != null && name != null && !name.startsWith(".")) {
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            dirs += id to name
                        } else {
                            val ext = name.substringAfterLast('.', "").lowercase()
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                            if (ext in AUDIO_EXT || mime.startsWith("audio/")) {
                                files += FileEntry(docUri, name, path, modified)
                            } else if (ext in IMAGE_EXT) {
                                val stem = name.substringBeforeLast('.').lowercase()
                                if (stem in COVER_NAMES) covers[path] = docUri else anyImage.putIfAbsent(path, docUri)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (stats.error == null) stats.error = "$path: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "walk failed at $path", e)
        }
        for ((id, name) in dirs) walk(tree, id, "$path$name/", files, covers, anyImage, depth + 1, stats)
    }

    private fun coverFor(folder: String, covers: Map<String, String>): String? {
        covers[folder]?.let { return it }
        val parent = albumFolder(folder)
        return if (parent.isNotEmpty()) covers["$parent/"] else null
    }

    private fun readTags(f: FileEntry, cover: String?): Track {
        val folderName = f.folder.trimEnd('/').substringAfterLast('/')
        val fallbackTitle = f.name.substringBeforeLast('.')
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(f.uri))
            fun k(key: Int): String = r.extractMetadata(key)?.trim().orEmpty()
            return Track(
                uri = f.uri,
                title = k(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { fallbackTitle },
                artist = k(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = k(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = k(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifBlank { folderName.ifBlank { UNKNOWN_ALBUM } },
                trackNo = firstNumber(k(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNo = firstNumber(k(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)).coerceAtLeast(1),
                year = firstNumber(k(MediaMetadataRetriever.METADATA_KEY_YEAR)).let { if (it in 1..9999) it else 0 },
                durationMs = k(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L,
                folder = f.folder,
                cover = cover,
                fromMediaStore = false,
                lastModified = f.lastModified,
            )
        } catch (e: Exception) {
            Log.w(TAG, "tag read failed: ${f.name}", e)
            return Track(
                uri = f.uri, title = fallbackTitle, artist = "", albumArtist = "",
                album = folderName.ifBlank { UNKNOWN_ALBUM }, trackNo = 0, discNo = 1, year = 0,
                durationMs = 0L, folder = f.folder, cover = cover, fromMediaStore = false,
                lastModified = f.lastModified,
            )
        } finally {
            runCatching { r.release() }
        }
    }
}
