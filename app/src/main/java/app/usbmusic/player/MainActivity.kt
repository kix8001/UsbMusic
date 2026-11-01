package app.usbmusic.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import app.usbmusic.player.data.Prefs
import app.usbmusic.player.playback.PlayerConnection
import app.usbmusic.player.ui.App

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private lateinit var conn: PlayerConnection
    private var permitted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val wasStarted = permitted
            refreshPermission()
            // 後から許可された時（動画など）は読み込み直す
            if (wasStarted && result.values.any { it }) vm.rescan()
        }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                vm.setTree(uri)
                refreshPermission()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conn = PlayerConnection(applicationContext)
        refreshPermission()
        if (!permitted || !videoGranted()) askPermissions()
        setContent {
            App(
                vm = vm,
                conn = conn,
                permitted = permitted,
                onRequestPermission = { askPermissions() },
                onPickFolder = { folderLauncher.launch(null) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        conn.connect { c ->
            // ナビ起動後に初めて開いた時は、前回の続きから自動再生
            if (!autoResumed && vm.prefs.autoResume && c.mediaItemCount > 0 && !c.isPlaying) {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
            autoResumed = true
        }
    }

    override fun onStop() {
        conn.release()
        super.onStop()
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun videoGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val list = mutableListOf(audioPermission())
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.READ_MEDIA_VIDEO
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(list.toTypedArray())
    }

    private fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED
        permitted = granted || vm.prefs.sourceMode == Prefs.MODE_FOLDER
        if (permitted) vm.start()
    }

    companion object {
        private var autoResumed = false
    }
}
