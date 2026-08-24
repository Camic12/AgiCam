package com.epm.camaraterreno

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Detecta movimiento del dispositivo via acelerómetro lineal.
 * TYPE_LINEAR_ACCELERATION = aceleración sin gravedad.
 * Si magnitud > UMBRAL → dispositivo en movimiento → bloquear captura.
 */
class GestorMovimiento(contexto: Context) : SensorEventListener {

    private val sensorManager =
        contexto.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val usaAcelerometroRaw = sensor?.type == Sensor.TYPE_ACCELEROMETER

    // Filtro pasa-bajas para estimar gravedad (solo si usamos acelerómetro raw)
    private val gravedad = FloatArray(3)
    private val ALPHA = 0.8f

    private var magnitudActual = 0f

    // 0.75 m/s² — más de esto = mano no está quieta
    private val UMBRAL = 0.75f

    val hayMovimiento: Boolean
        get() = magnitudActual > UMBRAL

    fun iniciar() {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun detener() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x: Float
        val y: Float
        val z: Float

        if (usaAcelerometroRaw) {
            // Filtro pasa-bajas → estima gravedad → aceleración lineal
            gravedad[0] = ALPHA * gravedad[0] + (1 - ALPHA) * event.values[0]
            gravedad[1] = ALPHA * gravedad[1] + (1 - ALPHA) * event.values[1]
            gravedad[2] = ALPHA * gravedad[2] + (1 - ALPHA) * event.values[2]
            x = event.values[0] - gravedad[0]
            y = event.values[1] - gravedad[1]
            z = event.values[2] - gravedad[2]
        } else {
            // TYPE_LINEAR_ACCELERATION ya excluye gravedad
            x = event.values[0]
            y = event.values[1]
            z = event.values[2]
        }

        magnitudActual = sqrt(x * x + y * y + z * z)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
