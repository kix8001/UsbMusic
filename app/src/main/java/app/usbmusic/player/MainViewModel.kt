package app.usbmusic.player

import android.app.Application
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.usbmusic.player.data.ArtLoader
import app.usbmusic.player.data.Library
import app.usbmusic.player.data.LibraryRepository
import app.usbmusic.player.data.Prefs
import app.usbmusic.player.ui.VisStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val repo = LibraryRepository(app, prefs)

    var library by mutableStateOf(Library(emptyList()))
        private set
    var scanning by mutableStateOf(false)
        private set
    var scanProgress by mutableIntStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set
    var sourceMode by mutableStateOf(prefs.sourceMode)
        private set
    var treeUri by mutableStateOf(prefs.treeUri)
        private set
    var report by mutableStateOf<List<String>>(emptyList())
        private set
    var tagProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    var visualizer by mutableStateOf(VisStyle.of(prefs.visualizer))
        private set

    fun setVisualizerStyle(style: VisStyle) {
        visualizer = style
        prefs.visualizer = style.id
    }

    /** 再生画面のビジュアライザーをタップした時、次のスタイルへ */
    fun nextVisualizer() {
        val list = VisStyle.cycle
        setVisualizerStyle(list[(list.indexOf(visualizer) + 1) % list.size])
    }

    var mediaScanRunning by mutableStateOf(false)
        private set
    private var mediaScanRequested = false

    private var started = false
    private var scanJob: Job? = null
    private var scanGeneration = 0
    private var debounceJob: Job? = null

    // USBが挿された・Androidの曲情報が更新された時に自動で再読み込み
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (sourceMode != Prefs.MODE_AUTO) return
            debounceJob?.cancel()
            debounceJob = viewModelScope.launch {
                delay(4000)
                rescan()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { repo.loadCache() }
            if (!cached.isNullOrEmpty()) {
                library = withContext(Dispatchers.Default) { Library(cached) }
                loaded = true
            }
            rescan()
        }
        runCatching {
            getApplication<Application>().contentResolver
                .registerContentObserver(MediaStore.AUTHORITY_URI, true, observer)
        }
    }

    fun rescan() {
        scanJob?.cancel()
        val generation = ++scanGeneration
        scanJob = viewModelScope.launch {
            scanning = true
            scanProgress = 0
            try {
                val tracks = repo.scan { n -> viewModelScope.launch { scanProgress = n } }
                library = withContext(Dispatchers.Default) { Library(tracks) }
                ArtLoader.resetMissing()
                // USBがライブラリに未登録なら、一度だけ自動でAndroidに登録を依頼する
                if (tracks.isEmpty() && repo.unindexedUsbDirs.isNotEmpty() && !mediaScanRequested) {
                    requestMediaScan()
                }
                // 並べ替え用の読み・曲順をIDタグから直接読む（初回のみ時間がかかる）
                if (tracks.any { !it.tagsRead }) {
                    tagProgress = 0 to tracks.count { !it.tagsRead }
                    val enriched = repo.enrich(tracks) { done, total ->
                        viewModelScope.launch { tagProgress = done to total }
                    }
                    library = withContext(Dispatchers.Default) { Library(enriched) }
                    withContext(Dispatchers.IO) { repo.saveCache(enriched) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainViewModel", "scan failed", e)
            } finally {
                if (generation == scanGeneration) {
                    tagProgress = null
                    report = repo.report
                    scanning = false
                    loaded = true
                }
            }
        }
    }

    /** AndroidのメディアスキャナーにUSBの中身を調べて登録するよう依頼する */
    fun requestMediaScan() {
        val dirs = repo.removableDirs()
        if (dirs.isEmpty() || mediaScanRunning) return
        mediaScanRequested = true
        mediaScanRunning = true
        var remaining = dirs.size
        MediaScannerConnection.scanFile(getApplication(), dirs.toTypedArray(), null) { _, _ ->
            viewModelScope.launch {
                remaining--
                if (remaining <= 0) {
                    mediaScanRunning = false
                    rescan()
                }
            }
        }
    }

    fun setMode(mode: String) {
        if (mode == sourceMode) return
        prefs.sourceMode = mode
        sourceMode = mode
        rescan()
    }

    fun setTree(uri: Uri) {
        prefs.treeUri = uri.toString()
        treeUri = uri.toString()
        prefs.sourceMode = Prefs.MODE_FOLDER
        sourceMode = Prefs.MODE_FOLDER
        rescan()
    }

    fun setIncludeInternal(on: Boolean) {
        prefs.includeInternal = on
        rescan()
    }

    fun clearArtCache() {
        ArtLoader.clearAll(getApplication())
        library = Library(library.tracks)
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().contentResolver.unregisterContentObserver(observer) }
        super.onCleared()
    }
}
