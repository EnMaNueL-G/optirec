package com.optisuite.optirec

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Vigila la temperatura del SoC y traduce el estado termico en una decision:
 *  - NORMAL: graba con la calidad elegida.
 *  - CALIENTE (MODERATE/SEVERE): baja fps y bitrate en el siguiente segmento.
 *  - CRITICO (CRITICAL+): detiene la grabacion para proteger el telefono.
 *
 * Usa PowerManager.OnThermalStatusChangedListener (API 29+). En API < 29
 * simplemente devuelve NORMAL (no hay API termica publica).
 */
class ThermalMonitor(context: Context) {

    enum class Level { NORMAL, HOT, CRITICAL }

    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    @Volatile private var raw: Int = PowerManager.THERMAL_STATUS_NONE
    private var onCritical: (() -> Unit)? = null

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        raw = status
        if (level() == Level.CRITICAL) onCritical?.invoke()
    }

    fun start(onCritical: () -> Unit) {
        this.onCritical = onCritical
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            raw = pm.currentThermalStatus
            pm.addThermalStatusListener(listener)
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { pm.removeThermalStatusListener(listener) } catch (_: Exception) {}
        }
        onCritical = null
    }

    fun level(): Level {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Level.NORMAL
        return when (raw) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> Level.NORMAL
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE -> Level.HOT
            else -> Level.CRITICAL // CRITICAL, EMERGENCY, SHUTDOWN
        }
    }

    /**
     * Ajusta el preset segun el estado termico actual.
     * Si hace calor, recorta fps a 20 y el bitrate al 65 % (lo que mas baja el calor).
     */
    fun adjust(q: Quality): RecParams {
        return when (level()) {
            Level.NORMAL -> RecParams(q.width, q.height, q.fps, q.bitrate)
            Level.HOT -> RecParams(
                q.width, q.height,
                fps = 20,
                bitrate = (q.bitrate * 0.65f).toInt()
            )
            Level.CRITICAL -> RecParams(q.width, q.height, 20, (q.bitrate * 0.5f).toInt())
        }
    }
}

/** Parametros efectivos de un segmento de grabacion. */
data class RecParams(val width: Int, val height: Int, val fps: Int, val bitrate: Int)
