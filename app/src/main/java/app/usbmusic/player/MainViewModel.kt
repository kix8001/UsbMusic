package app.usbmusic.player

import android.app.Application
import android.database.ContentObserver
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainViewModel", "scan failed", e)
            } finally {
                if (generation == scanGeneration) {
                    report = repo.report
                    scanning = false
                    loaded = true
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
