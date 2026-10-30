package app.usbmusic.player.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "auto" = USBを自動検出（MediaStore）／ "folder" = 指定フォルダを直接読む */
    var sourceMode: String
        get() = sp.getString("source", MODE_AUTO) ?: MODE_AUTO
        set(v) = sp.edit().putString("source", v).apply()

    var treeUri: String?
        get() = sp.getString("tree", null)
        set(v) = sp.edit().putString("tree", v).apply()

    var includeInternal: Boolean
        get() = sp.getBoolean("internal", false)
        set(v) = sp.edit().putBoolean("internal", v).apply()

    var autoResume: Boolean
        get() = sp.getBoolean("autoResume", true)
        set(v) = sp.edit().putBoolean("autoResume", v).apply()

    var visualizer: String
        get() = sp.getString("visualizer", "neon") ?: "neon"
        set(v) = sp.edit().putString("visualizer", v).apply()

    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_FOLDER = "folder"
    }
}
