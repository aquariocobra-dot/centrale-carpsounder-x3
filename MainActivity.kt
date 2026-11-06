package fr.peche.centrale

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private lateinit var tv: TextView
    private val h = Handler(Looper.getMainLooper())

    private val maj = object : Runnable {
        override fun run() {
            val p = AlarmService.lastPing
            val etat = when {
                !AlarmService.running -> "Surveillance arrêtée"
                p == 0L -> "En attente de la centrale…"
                else -> "Centrale vue il y a ${(System.currentTimeMillis() - p) / 1000} s"
            }
            tv.text = etat +
                (if (AlarmService.batt >= 0) "\nBatterie centrale : ${AlarmService.batt} %" else "") +
                (if (AlarmService.temp > -100) "\nAir : ${"%.1f".format(AlarmService.temp)} °C" + (if (AlarmService.hum >= 0) " · ${"%.0f".format(AlarmService.hum)} %" else "") + (if (AlarmService.pres > 0) " · ${"%.1f".format(AlarmService.pres)} hPa" else "") else "") +
                (if (AlarmService.cnt.any { it > 0 }) "\nImpulsions : C1=${AlarmService.cnt[0]}  C2=${AlarmService.cnt[1]}  C3=${AlarmService.cnt[2]}" else "") +
                "\nArmée : " + (if (AlarmService.armed) "oui" else "non") +
                (if (AlarmService.ringing) "\n*** ALARME EN COURS ***" else "") +
                "\nDernier événement : " + AlarmService.lastEvent
            h.postDelayed(this, 1000)
        }
    }

    private fun envoyer(action: String?) {
        startForegroundService(Intent(this, AlarmService::class.java).setAction(action))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        tv = TextView(this).apply { textSize = 18f }
        l.addView(tv)
        fun bouton(t: String, f: () -> Unit) {
            l.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        }
        bouton("Démarrer la surveillance") { envoyer(null) }
        bouton("ARRÊTER L'ALARME") { envoyer(AlarmService.A_STOP) }
        bouton("Armer / désarmer") { AlarmService.armed = !AlarmService.armed }
        bouton("Test alarme") { envoyer(AlarmService.A_TEST) }
        bouton("Autoriser en arrière-plan (batterie)") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
        bouton("Quitter") { envoyer(AlarmService.A_QUIT) }
        setContentView(ScrollView(this).apply { addView(l) })
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); h.post(maj) }
    override fun onPause() { super.onPause(); h.removeCallbacks(maj) }
}
