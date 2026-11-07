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
        val FREQ = intArrayOf(880, 1100, 1320)  // une note par canne
        @Volatile var running = false
        @Volatile var ringing = false
        @Volatile var armed = true
        @Volatile var lastPing = 0L
        @Volatile var lastEvent = "Aucun événement"
        @Volatile var batt = -1
        @Volatile var temp = -999.0
        @Volatile var hum = -1.0
        @Volatile var pres = -1.0
        val cnt = IntArray(3)
    }

    private var server: ServerSocket? = null
    private var ringtone: Ringtone? = null
    private var wl: PowerManager.WakeLock? = null
    private var lostAlerted = false
    private var lowAlerted = false
    private var lastNotif = 0L
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel("veille", "Surveillance", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("alarme", "Alarme", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("touche", "Touches", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
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
            while (running) {
                try {
                    if (server == null || server!!.isClosed) server = ServerSocket(PORT, 50)
                    val s = server!!.accept()
                    Thread { handle(s) }.start()
                } catch (e: Exception) {
                    try { server?.close() } catch (x: Exception) { }
                    server = null
                    try { Thread.sleep(1000) } catch (x: Exception) { }
                }
            }
        }.start()
        Thread {
            while (running) {
                try { Thread.sleep(5000) } catch (e: Exception) { }
                val p = lastPing
                if (armed && p > 0 && System.currentTimeMillis() - p > 30000) {
                    if (!lostAlerted) { lostAlerted = true; try { alarm("Centrale injoignable !") } catch (e: Exception) { } }
                } else lostAlerted = false
            }
        }.start()
    }

    private fun heure() = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())

    private fun handle(s: Socket) {
        var c = 0
        var n = 0
        try {
            s.soTimeout = 3000
            val line = s.getInputStream().bufferedReader().readLine() ?: ""
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
            s.getOutputStream().flush()
            lastPing = System.currentTimeMillis()
            Regex("b=(\\d+)").find(line)?.let {
                batt = it.groupValues[1].toInt()
                if (batt <= 20 && !lowAlerted) { lowAlerted = true; alarm("Batterie centrale faible ($batt %)") }
                else if (batt > 25) lowAlerted = false
            }
            Regex("t=(-?\\d+(\\.\\d+)?)").find(line)?.let { temp = it.groupValues[1].toDouble() }
            Regex("[?&]h=(\\d+(\\.\\d+)?)").find(line)?.let { hum = it.groupValues[1].toDouble() }
            Regex("[?&]p=(\\d+(\\.\\d+)?)").find(line)?.let { pres = it.groupValues[1].toDouble() }
            if (line.contains("/touche")) {
                c = (Regex("c=(\\d)").find(line)?.groupValues?.get(1)?.toInt() ?: 1).coerceIn(1, 3)
                n = (Regex("n=(\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: 1).coerceIn(1, 50)
            }
        } catch (e: Exception) {
        } finally { try { s.close() } catch (e: Exception) { } }
        if (c > 0) try { touche(c, n) } catch (e: Exception) { }
    }

    // Un bip par impulsion : la cadence des bips suit celle des touches
    private fun touche(c: Int, n: Int) {
        val i = c - 1
        if (!armed) { lastEvent = "Canne $c (non armé) à ${heure()}"; return }
        cnt[i] += n
        lastEvent = "Canne $c : ${cnt[i]} impulsion(s), dernière à ${heure()}"
        ringing = true
        val am = getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        getSystemService(Vibrator::class.java)
            .vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        notifier(c)
        for (k in 0 until n) {
            bip(FREQ[i], 120)
            if (k < n - 1) Thread.sleep(150)
        }
    }

    private fun bip(freq: Int, ms: Int) {
        Thread {
            try {
                val sr = 22050
                val n = sr * ms / 1000
                val buf = ShortArray(n) {
                    val env = minOf(1.0, minOf(it, n - it) / 200.0)
                    (Math.sin(2.0 * Math.PI * it * freq / sr) * 28000 * env).toInt().toShort()
                }
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC).build()
                at.write(buf, 0, n)
                at.play()
                Thread.sleep(ms + 100L)
                at.release()
            } catch (e: Exception) { }
        }.start()
    }

    private fun notifier(c: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotif < 1000) return
        lastNotif = now
        nm.notify(3, Notification.Builder(this, "touche")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Touche canne $c")
            .setContentText("C1=${cnt[0]}  C2=${cnt[1]}  C3=${cnt[2]}")
            .setCategory(Notification.CATEGORY_ALARM).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build())
    }

    // Alarme continue : réservée aux alertes système (batterie faible, centrale injoignable, test)
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
        nm.cancel(3)
        cnt.fill(0)
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
