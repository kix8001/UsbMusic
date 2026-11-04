package app.mileage.log

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * GPS で走行距離を測るフォアグラウンドサービス。
 * 画面を消しても、ほかのアプリを使っていても計測を続ける。
 * 途中でプロセスが落ちても、保存しておいた距離から再開する（START_STICKY）。
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "app.mileage.log.START"
        const val ACTION_STOP = "app.mileage.log.STOP"
        private const val CHANNEL = "tracking"
        private const val NOTIFY_ID = 1

        /** これより悪い精度（m）の位置は使わない */
        private const val MAX_ACCURACY = 25f
        /** これを超える速度（m/s、約250km/h）になる位置は飛びとみなして捨てる */
        private const val MAX_SPEED = 70f
        /** これより短い移動は止まっているときの GPS のゆらぎとみなして足さない */
        private const val MIN_STEP = 8f
        /** これ未満の走行は保存しない（誤操作対策） */
        private const val MIN_SAVE_METERS = 50.0

        private const val PREFS = "tracking"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TrackingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var lm: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var listening = false
    private var last: Location? = null
    /** 出発地（最初に取れた良い位置）と、最後に取れた良い位置。住所の自動入力に使う */
    private var firstLoc: Pair<Double, Double>? = null
    private var lastLoc: Pair<Double, Double>? = null
    private var lastNotify = 0L
    private var lastPersist = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onLocation(loc)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            Tracking.mutable.value = Tracking.mutable.value.copy(hasFix = false)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        when (intent?.action) {
            ACTION_STOP -> {
                finish()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!Tracking.state.value.running) {
                    val now = System.currentTimeMillis()
                    prefs.edit().clear().putLong("start", now).putFloat("meters", 0f).apply()
                    firstLoc = null
                    lastLoc = null
                    begin(now, 0.0)
                }
            }
            else -> {
                // システムに再起動された：計測中だったら続きから
                val start = prefs.getLong("start", 0L)
                if (start == 0L) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (prefs.contains("firstLat")) {
                    firstLoc = prefs.getFloat("firstLat", 0f).toDouble() to prefs.getFloat("firstLon", 0f).toDouble()
                }
                begin(start, prefs.getFloat("meters", 0f).toDouble())
            }
        }
        return START_STICKY
    }

    private fun begin(start: Long, meters: Double) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        Tracking.mutable.value = Tracking.State(running = true, startTime = start, meters = meters)
        ServiceCompat.startForeground(
            this, NOTIFY_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mileage:tracking")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        }
        if (!listening) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
                listening = true
            } catch (e: SecurityException) {
                stopSelf()
            } catch (e: IllegalArgumentException) {
                // GPS が無い端末
                stopSelf()
            }
        }
    }

    private fun onLocation(loc: Location) {
        val s = Tracking.mutable.value
        if (!s.running) return
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val speed = if (loc.hasSpeed()) loc.speed * 3.6f else s.speedKmh
        if (acc != null && acc > MAX_ACCURACY) {
            Tracking.mutable.value = s.copy(accuracy = acc, hasFix = false)
            return
        }
        val here = loc.latitude to loc.longitude
        if (firstLoc == null) {
            firstLoc = here
            // Float だと誤差が数m出るが、住所を引くには十分
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat("firstLat", here.first.toFloat()).putFloat("firstLon", here.second.toFloat()).apply()
        }
        lastLoc = here
        var meters = s.meters
        val prev = last
        if (prev == null) {
            last = loc
        } else {
            val d = prev.distanceTo(loc)
            val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
            when {
                dt <= 0 -> {}
                d / dt > MAX_SPEED -> {} // 位置が飛んだ：前の点のまま次を待つ
                d >= maxOf(MIN_STEP, acc ?: 0f) -> {
                    meters += d
                    last = loc
                }
                // 短い移動はためておき、しきい値を超えたらまとめて足す
            }
        }
        Tracking.mutable.value = s.copy(meters = meters, speedKmh = speed, accuracy = acc, hasFix = true)

        val now = SystemClock.elapsedRealtime()
        if (now - lastPersist > 5000) {
            lastPersist = now
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("meters", meters.toFloat()).apply()
        }
        if (now - lastNotify > 5000) {
            lastNotify = now
            getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, buildNotification())
        }
    }

    private fun finish() {
        val s = Tracking.mutable.value
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val start = if (s.running) s.startTime else prefs.getLong("start", 0L)
        val meters = if (s.running) s.meters else prefs.getFloat("meters", 0f).toDouble()
        if (start != 0L && meters >= MIN_SAVE_METERS) {
            val first = firstLoc ?: if (prefs.contains("firstLat"))
                prefs.getFloat("firstLat", 0f).toDouble() to prefs.getFloat("firstLon", 0f).toDouble() else null
            Store.addTrip(
                Trip(
                    id = Store.newId(),
                    start = start,
                    end = System.currentTimeMillis(),
                    meters = meters,
                    category = Store.settings.value.nextCategory,
                    startLat = first?.first, startLon = first?.second,
                    endLat = lastLoc?.first, endLon = lastLoc?.second,
                )
            )
            Geo.fillMissing(this)
        }
        prefs.edit().clear().apply()
        Tracking.mutable.value = Tracking.State()
        cleanup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        if (listening) {
            lm.removeUpdates(listener)
            listening = false
        }
        last = null
        firstLoc = null
        lastLoc = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        cleanup()
        // 停止ボタン以外で終わった場合も、画面が「計測中」のままにならないようにする
        // （保存済みの距離は prefs に残り、再起動時に続きから再開される）
        Tracking.mutable.value = Tracking.mutable.value.copy(running = false)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "走行の計測", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val s = Tracking.state.value
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("走行を記録中")
            .setContentText(String.format(Locale.JAPAN, "%.2f km", s.meters / 1000.0))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止して保存", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
