package fr.peche.centrale

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

class AlarmService : Service() {
    companion object {
        const val PORT = 8080
        const val A_STOP = "stop"
        const val A_TEST = "test"
        const val A_QUIT = "quit"
        @Volatile var running = false
        @Volatile var ringing = false
        @Volatile var armed = true
        @Volatile var lastPing = 0L
        @Volatile var lastEvent = "Aucun événement"
        @Volatile var batt = -1
    }

    private var server: ServerSocket? = null
    private var ringtone: Ringtone? = null
    private var wl: PowerManager.WakeLock? = null
    private var lostAlerted = false
    private var lowAlerted = false
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel("veille", "Surveillance", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("alarme", "Alarme", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "veille")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Centrale pêche active")
            .setContentText("Écoute sur le port $PORT")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        wl = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "centrale:wl").apply { acquire() }
        running = true
        Thread {
            try {
                server = ServerSocket(PORT)
                while (running) {
                    val s = server!!.accept()
                    Thread { handle(s) }.start()
                }
            } catch (e: Exception) { }
        }.start()
        Thread {
            while (running) {
                try { Thread.sleep(5000) } catch (e: Exception) { }
                val p = lastPing
                if (armed && p > 0 && System.currentTimeMillis() - p > 30000) {
                    if (!lostAlerted) { lostAlerted = true; alarm("Centrale injoignable !") }
                } else lostAlerted = false
            }
        }.start()
    }

    private fun heure() = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 3000
            val line = s.getInputStream().bufferedReader().readLine() ?: ""
            lastPing = System.currentTimeMillis()
            Regex("b=(\\d+)").find(line)?.let {
                batt = it.groupValues[1].toInt()
                if (batt <= 20 && !lowAlerted) { lowAlerted = true; alarm("Batterie centrale faible ($batt %)") }
                else if (batt > 25) lowAlerted = false
            }
            if (line.contains("/touche")) {
                val c = Regex("c=(\\d)").find(line)?.groupValues?.get(1) ?: "?"
                if (armed) alarm("Canne $c")
                else lastEvent = "Canne $c (non armé) à ${heure()}"
            }
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
        } catch (e: Exception) {
        } finally { try { s.close() } catch (e: Exception) { } }
    }

    @Synchronized
    private fun alarm(text: String) {
        lastEvent = "$text à ${heure()}"
        val am = getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        if (ringtone?.isPlaying != true) {
            val r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            r.isLooping = true
            r.play()
            ringtone = r
            getSystemService(Vibrator::class.java)
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        }
        ringing = true
        nm.notify(2, Notification.Builder(this, "alarme")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(text).setContentText(heure())
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build())
    }

    private fun stopAlarm() {
        ringtone?.stop()
        getSystemService(Vibrator::class.java).cancel()
        nm.cancel(2)
        ringing = false
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            A_STOP -> stopAlarm()
            A_TEST -> alarm("Test")
            A_QUIT -> { stopAlarm(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        lastPing = 0L
        try { server?.close() } catch (e: Exception) { }
        stopAlarm()
        wl?.release()
        super.onDestroy()
    }
}
