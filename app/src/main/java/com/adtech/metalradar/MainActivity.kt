package com.adtech.metalradar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        // Écart (en µT) au-dessus de la valeur de base qui déclenche l'alerte
        private const val SEUIL_UT = 60f

        // Nombre de mesures utilisées pour calculer la valeur de base
        private const val ECHANTILLONS_CALIBRATION = 25

        // Lissage de la valeur affichée (0 = très lisse, 1 = brut)
        private const val LISSAGE = 0.25f

        private const val DELAI_VIBRATION_MS = 400L
        private const val DUREE_VIBRATION_MS = 120L
        private const val JAUGE_MAX = 120
    }

    private lateinit var sensorManager: SensorManager
    private var magnetometre: Sensor? = null
    private lateinit var vibrator: Vibrator

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusPanel: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvValue: TextView
    private lateinit var tvBase: TextView
    private lateinit var tvDelta: TextView
    private lateinit var progressGauge: ProgressBar
    private lateinit var btnCalibrate: MaterialButton

    private var calibre = false
    private var nbEchantillons = 0
    private var sommeEchantillons = 0f
    private var valeurBase = 0f
    private var valeurLissee = 0f
    private var premiereMesure = true
    private var alerteActive = false
    private var derniereVibration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        statusPanel = findViewById(R.id.statusPanel)
        tvStatus = findViewById(R.id.tvStatus)
        tvValue = findViewById(R.id.tvValue)
        tvBase = findViewById(R.id.tvBase)
        tvDelta = findViewById(R.id.tvDelta)
        progressGauge = findViewById(R.id.progressGauge)
        btnCalibrate = findViewById(R.id.btnCalibrate)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometre = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        btnCalibrate.setOnClickListener { demarrerCalibration() }

        if (magnetometre == null) {
            tvStatus.text = "ERREUR : AUCUN MAGNÉTOMÈTRE"
            btnCalibrate.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        demarrerCalibration()
        magnetometre?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Intensité totale du champ magnétique en µT
        val intensite = sqrt(x * x + y * y + z * z)

        if (premiereMesure) {
            valeurLissee = intensite
            premiereMesure = false
        } else {
            valeurLissee += LISSAGE * (intensite - valeurLissee)
        }

        tvValue.text = String.format(Locale.US, "%.1f", valeurLissee)

        if (!calibre) {
            sommeEchantillons += valeurLissee
            nbEchantillons++
            tvStatus.text = "CALIBRATION… " + (nbEchantillons * 100 / ECHANTILLONS_CALIBRATION) + "%"

            if (nbEchantillons >= ECHANTILLONS_CALIBRATION) {
                valeurBase = sommeEchantillons / nbEchantillons
                calibre = true
                tvBase.text = String.format(Locale.US, "BASE : %.1f µT", valeurBase)
                tvStatus.text = "CALIBRÉ // EN ÉCOUTE"
            }
            return
        }

        val ecart = valeurLissee - valeurBase
        tvDelta.text = String.format(Locale.US, "Δ : %+.1f µT", ecart)
        progressGauge.progress = ecart.coerceIn(0f, JAUGE_MAX.toFloat()).toInt()

        val detecte = ecart > SEUIL_UT
        if (detecte != alerteActive) {
            alerteActive = detecte
            appliquerAffichage(detecte)
        }
        if (detecte) {
            vibrer()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Rien à faire
    }

    private fun demarrerCalibration() {
        calibre = false
        nbEchantillons = 0
        sommeEchantillons = 0f
        premiereMesure = true
        alerteActive = false

        tvBase.text = "BASE : -- µT"
        tvDelta.text = "Δ : +0.0 µT"
        progressGauge.progress = 0
        appliquerAffichage(false)
        tvStatus.text = "CALIBRATION EN COURS…"
    }

    private fun appliquerAffichage(detecte: Boolean) {
        val accent = ContextCompat.getColor(
            this,
            if (detecte) R.color.alert_red else R.color.neon_green
        )

        rootLayout.setBackgroundColor(
            if (detecte) Color.parseColor("#2A0610")
            else ContextCompat.getColor(this, R.color.bg_dark)
        )

        tvStatus.setTextColor(accent)
        tvValue.setTextColor(accent)
        tvDelta.setTextColor(accent)
        progressGauge.progressTintList = ColorStateList.valueOf(accent)

        val epaisseur = ((if (detecte) 4 else 2) * resources.displayMetrics.density).toInt()
        (statusPanel.background.mutate() as? GradientDrawable)?.setStroke(epaisseur, accent)

        if (detecte) {
            tvStatus.text = ">> MÉTAL / PIÈCE DÉTECTÉ ! <<"
        } else if (calibre) {
            tvStatus.text = "CALIBRÉ // EN ÉCOUTE"
        }
    }

    private fun vibrer() {
        val maintenant = SystemClock.elapsedRealtime()
        if (maintenant - derniereVibration < DELAI_VIBRATION_MS) return
        derniereVibration = maintenant

        if (vibrator.hasVibrator()) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(DUREE_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }
}
