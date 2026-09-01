package com.ewslereng.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitoringService : Service() {

    companion object {
        const val CHANNEL_PERSISTENT = "ews_persistent"
        const val CHANNEL_ALERT = "ews_alert"
        const val NOTIF_ID_PERSISTENT = 1001
        const val NOTIF_ID_ALERT = 1002

        const val ACTION_SILENCE = "com.ewslereng.app.ACTION_SILENCE"
        const val ACTION_STOP_SERVICE = "com.ewslereng.app.ACTION_STOP_SERVICE"
        const val ACTION_CHECK_NOW = "com.ewslereng.app.ACTION_CHECK_NOW"

        const val ACTION_DATA_UPDATED = "com.ewslereng.app.DATA_UPDATED"
        const val EXTRA_WORST_STATUS = "worst_status"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollJob: Job? = null

    // Level severity TERTINGGI yang sudah pernah membunyikan alarm, PER SENSOR.
    // Sensor yang levelnya belum berubah (masih di level yang sama/lebih rendah dari
    // yang sudah pernah dialarm) TIDAK akan bunyi berulang tiap siklus polling --
    // hanya sensor yang levelnya NAIK melebihi rekor terakhirnya yang membunyikan alarm.
    // Begitu sensor kembali ke aman, catatannya dihapus (kalau naik lagi nanti, dianggap
    // kejadian baru lagi).
    private val alertedSeverityBySensor = mutableMapOf<String, Int>()
    private var currentWorstStatus = "aman"
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val notification = buildPersistentNotification("aman", muted = false)
        ServiceCompat.startForeground(
            this,
            NOTIF_ID_PERSISTENT,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        acquireWakeLock()
        startPolling()
    }

    private fun acquireWakeLock() {
        // Tanpa wake lock ini, CPU HP bisa "tidur" saat layar mati/standby,
        // dan siklus pengecekan data di latar belakang ikut berhenti sampai
        // pengguna menyentuh HP lagi. Wake lock ini menahan CPU tetap aktif
        // KHUSUS untuk proses servis ini (layar tetap boleh mati/hemat baterai).
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "EWSLereng::MonitoringWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L /*maksimal 12 jam, jaga-jaga kalau lupa dilepas*/)
            }
        } catch (e: Exception) {
            // Kalau gagal (jarang terjadi), servis tetap jalan seperti biasa,
            // cuma tanpa jaminan tetap aktif saat layar mati.
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SILENCE -> {
                AlarmPlayer.stop()
                (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.cancel()
                val nm = getSystemService(NotificationManager::class.java)
                nm.cancel(NOTIF_ID_ALERT)
                nm.notify(NOTIF_ID_PERSISTENT, buildPersistentNotification(currentWorstStatus, muted = true))
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
            }
            ACTION_CHECK_NOW -> {
                scope.launch {
                    val result = SensorRepository.fetchLatest()
                    if (result is FetchResult.Success) handleData(result.data)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        AlarmPlayer.stop()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { }
    }

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val result = SensorRepository.fetchLatest()
                    if (result is FetchResult.Success) {
                        handleData(result.data)
                    }
                } catch (e: Exception) {
                    // Jangan biarkan satu error (misal AlarmPlayer gagal di background)
                    // menghentikan loop pengecekan ini selamanya -- lanjut ke siklus berikutnya.
                }
                delay(Config.POLL_INTERVAL_SERVICE_MS)
            }
        }
    }

    private fun handleData(data: Map<String, SensorLatest>) {
        val worstEntry = SensorRepository.worstEntry(data)
        val worst = worstEntry?.status ?: "aman"
        val worstSev = severityOf(worst)
        currentWorstStatus = worst

        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_DATA_UPDATED).putExtra(EXTRA_WORST_STATUS, worst)
        )

        // Sensor yang sudah kembali aman -> hapus catatannya, supaya kalau naik lagi
        // nanti (kapan pun), dianggap kejadian baru dan membunyikan alarm lagi.
        data.forEach { (id, s) ->
            if (severityOf(s.status) == 0) alertedSeverityBySensor.remove(id)
        }

        if (worstSev == 0) {
            AlarmPlayer.stop()
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIF_ID_ALERT)
            nm.notify(NOTIF_ID_PERSISTENT, buildPersistentNotification(worst, muted = false))
            return
        }

        // Sensor mana saja yang levelnya NAIK melebihi rekor terakhir yang sudah dialarm
        // untuk sensor itu -- bukan cuma sensor yang levelnya paling parah saat ini, dan
        // bukan sekadar pembacaan baru di level yang SAMA (itu tidak perlu bunyi ulang).
        val escalated = data.filter { (id, s) ->
            val sev = severityOf(s.status)
            sev > 0 && sev > (alertedSeverityBySensor[id] ?: 0)
        }

        if (escalated.isNotEmpty()) {
            AlarmPlayer.start()
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500, 300, 500, 300, 500), 0)
            )
            showAlertNotification(worst)
            escalated.forEach { (id, s) -> alertedSeverityBySensor[id] = severityOf(s.status) }
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID_PERSISTENT, buildPersistentNotification(worst, muted = escalated.isEmpty()))
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        val persistent = NotificationChannel(
            CHANNEL_PERSISTENT, "Status pemantauan", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Notifikasi tetap yang menunjukkan status lereng saat ini" }

        val alert = NotificationChannel(
            CHANNEL_ALERT, "Peringatan lereng", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Peringatan saat kondisi waspada, siaga, atau bahaya"
            enableVibration(true)
            // Suara notifikasi sistem dimatikan di sini karena alarm dibunyikan manual lewat AlarmPlayer
            setSound(null, null)
        }

        nm.createNotificationChannel(persistent)
        nm.createNotificationChannel(alert)
    }

    private fun buildPersistentNotification(status: String, muted: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = "EWS Lereng - Status: ${labelOf(status)}"
        val text = if (muted && severityOf(status) > 0) "Alarm dimatikan, tetap dipantau"
                   else "Memantau kondisi lereng secara berkala"

        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun showAlertNotification(status: String) {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val silenceIntent = PendingIntent.getService(
            this, 1, Intent(this, MonitoringService::class.java).setAction(ACTION_SILENCE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when (status) {
            "bahaya" -> "⚠ BAHAYA — potensi longsor terdeteksi"
            "siaga" -> "⚠ SIAGA — pergerakan lereng signifikan"
            "waspada" -> "Waspada — pergeseran lereng terdeteksi"
            else -> "Peringatan lereng"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle(title)
            .setContentText("Ketuk untuk lihat detail, atau matikan notifikasi setelah membaca kondisi.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_media_pause, "Matikan Notifikasi", silenceIntent)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_ALERT, notification)
    }
}
