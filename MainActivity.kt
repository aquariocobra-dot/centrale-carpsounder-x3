package fr.peche.centrale

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.*

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private var dark = false
    private var cBg = 0
    private var cCard = 0
    private var cTx = 0
    private var cMut = 0
    private var cBd = 0
    private var cBtn = 0
    private val cOk = Color.parseColor("#1E9E5A")
    private val cAlarm = Color.parseColor("#D92D20")

    private lateinit var bannerBox: LinearLayout
    private lateinit var bannerTxt: TextView
    private lateinit var tvConn: TextView
    private lateinit var tvBatt: TextView
    private lateinit var barFill: View
    private lateinit var barRest: View
    private lateinit var tvAir: TextView
    private lateinit var tvArm: TextView
    private lateinit var tvLast: TextView
    private lateinit var btnStart: TextView
    private lateinit var btnArm: TextView
    private lateinit var dots: Array<View>
    private lateinit var rodTxt: Array<TextView>

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fd(c: Int, r: Int = 12, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(c)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun texte(t: String, sp: Float, c: Int, gras: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(c)
        if (gras) setTypeface(typeface, Typeface.BOLD)
    }

    private fun ajouter(parent: LinearLayout, v: View, top: Int = 8) {
        parent.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    private fun carte(parent: LinearLayout): LinearLayout {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fd(cCard, 12, cBd)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        ajouter(parent, l, 10)
        return l
    }

    private fun bouton(parent: LinearLayout, t: String, fond: Int, txt: Int, f: () -> Unit): TextView {
        val b = texte(t, 16f, txt, true).apply {
            gravity = Gravity.CENTER
            background = fd(fond, 12, if (fond == cBtn) cBd else 0)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { f() }
        }
        ajouter(parent, b, 8)
        return b
    }

    private fun envoyer(action: String?) {
        startForegroundService(Intent(this, AlarmService::class.java).setAction(action))
    }

    private val maj = object : Runnable {
        override fun run() {
            val p = AlarmService.lastPing
            val age = (System.currentTimeMillis() - p) / 1000
            if (!AlarmService.running) {
                tvConn.text = "Surveillance arrêtée"; tvConn.setTextColor(cMut)
            } else if (p == 0L) {
                tvConn.text = "En attente de la centrale…"; tvConn.setTextColor(cMut)
            } else if (age > 30 && AlarmService.armed) {
                tvConn.text = "Centrale injoignable (vue il y a $age s)"; tvConn.setTextColor(cAlarm)
            } else {
                tvConn.text = "Centrale connectée · il y a $age s"; tvConn.setTextColor(cOk)
            }
            val b = AlarmService.batt
            tvBatt.text = "Batterie centrale : " + (if (b >= 0) "$b %" else "—")
            val fill = if (b < 0) 0 else b.coerceIn(0, 100)
            (barFill.layoutParams as LinearLayout.LayoutParams).weight = fill.toFloat()
            (barRest.layoutParams as LinearLayout.LayoutParams).weight = (100 - fill).toFloat()
            barFill.background = fd(if (b in 0..20) cAlarm else cOk, 4)
            barFill.requestLayout()
            tvAir.text = if (AlarmService.temp > -100)
                "Air : ${"%.1f".format(AlarmService.temp)} °C" +
                    (if (AlarmService.hum >= 0) " · ${"%.0f".format(AlarmService.hum)} %" else "") +
                    (if (AlarmService.pres > 0) " · ${"%.1f".format(AlarmService.pres)} hPa" else "")
            else "Air : —"
            tvArm.text = "Surveillance armée : " + (if (AlarmService.armed) "oui" else "non")
            tvLast.text = "Dernier événement : ${AlarmService.lastEvent}"
            btnStart.text = if (AlarmService.running) "Surveillance active ✓" else "Démarrer la surveillance"
            btnArm.text = if (AlarmService.armed) "Désarmer" else "Armer"
            bannerBox.visibility = if (AlarmService.ringing) View.VISIBLE else View.GONE
            bannerTxt.text = AlarmService.lastEvent
            for (i in 0..2) {
                val n = AlarmService.cnt[i]
                dots[i].background = fd(if (n > 0) cAlarm else cBd, 7)
                rodTxt[i].text = if (n > 0) "$n impulsion" + (if (n > 1) "s" else "") else "Au repos"
            }
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        cBg = Color.parseColor(if (dark) "#0F161D" else "#F4F6F8")
        cCard = Color.parseColor(if (dark) "#18222C" else "#FFFFFF")
        cTx = Color.parseColor(if (dark) "#E8EEF4" else "#14202B")
        cMut = Color.parseColor(if (dark) "#93A4B4" else "#5B6B7A")
        cBd = Color.parseColor(if (dark) "#2A3744" else "#D7DEE5")
        cBtn = Color.parseColor(if (dark) "#223040" else "#E8EDF2")
        actionBar?.hide()
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        racine.addView(texte("Centrale Pêche v2", 24f, cTx, true))
        racine.addView(texte("Écoute des 3 détecteurs", 14f, cMut))

        // Bandeau d'alarme
        bannerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = fd(cAlarm, 12)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            visibility = View.GONE
        }
        bannerBox.addView(texte("ALARME", 20f, Color.WHITE, true))
        bannerTxt = texte("", 16f, Color.WHITE).apply { gravity = Gravity.CENTER }
        bannerBox.addView(bannerTxt)
        val stop = texte("ARRÊTER L’ALARME", 17f, cAlarm, true).apply {
            gravity = Gravity.CENTER
            background = fd(Color.WHITE, 10)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { envoyer(AlarmService.A_STOP) }
        }
        ajouter(bannerBox, stop, 10)
        ajouter(racine, bannerBox, 12)

        // Carte d'état
        val st = carte(racine)
        tvConn = texte("", 17f, cTx, true)
        st.addView(tvConn)
        tvBatt = texte("", 15f, cTx)
        ajouter(st, tvBatt, 8)
        val barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = fd(cBtn, 4)
        }
        barFill = View(this)
        barRest = View(this)
        barre.addView(barFill, LinearLayout.LayoutParams(0, dp(8), 0f))
        barre.addView(barRest, LinearLayout.LayoutParams(0, dp(8), 100f))
        ajouter(st, barre, 6)
        tvAir = texte("", 15f, cTx)
        ajouter(st, tvAir, 8)
        tvArm = texte("", 15f, cTx)
        ajouter(st, tvArm, 4)
        tvLast = texte("", 13f, cMut)
        ajouter(st, tvLast, 4)

        // Une carte par canne
        dots = Array(3) { View(this) }
        rodTxt = Array(3) { texte("Au repos", 14f, cMut) }
        for (i in 0..2) {
            val c = carte(racine)
            val ligne = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            ligne.addView(dots[i], LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(12) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(texte("Canne ${i + 1}", 17f, cTx, true))
            col.addView(rodTxt[i])
            ligne.addView(col)
            c.addView(ligne)
        }

        // Boutons
        val zone = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ajouter(racine, zone, 12)
        btnStart = bouton(zone, "Démarrer la surveillance", cOk, Color.WHITE) { envoyer(null) }
        btnArm = bouton(zone, "Désarmer", cBtn, cTx) { AlarmService.armed = !AlarmService.armed }
        bouton(zone, "Test alarme", cBtn, cTx) { envoyer(AlarmService.A_TEST) }
        bouton(zone, "Autoriser en arrière-plan (batterie)", cBtn, cTx) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
        bouton(zone, "Quitter", cBtn, cMut) { envoyer(AlarmService.A_QUIT) }

        setContentView(ScrollView(this).apply { setBackgroundColor(cBg); addView(racine) })

        if (Build.VERSION.SDK_INT >= 30) {
            val m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else m, m)
        }
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); h.post(maj) }
    override fun onPause() { super.onPause(); h.removeCallbacks(maj) }
}
