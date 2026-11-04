package app.mileage.log

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.mileage.log.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        // 前回ネットが無くて引けなかった住所を埋める
        Geo.fillMissing(this)
        setContent { App() }
    }
}
