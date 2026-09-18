package com.optisuite.optirec

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Acciones que entiende el servicio. */
object Actions {
    const val START = "com.optisuite.optirec.START"   // armar + grabar ya
    const val ARM = "com.optisuite.optirec.ARM"       // solo escuchar el atajo
    const val TOGGLE = "com.optisuite.optirec.TOGGLE" // iniciar/detener grabacion
    const val STOP_REC = "com.optisuite.optirec.STOP_REC" // parar grabacion, seguir armado
    const val EXIT = "com.optisuite.optirec.EXIT"     // salir del todo
}

const val CHANNEL_ID = "optirec_recording"
const val NOTIF_ID = 4711

/** Estados posibles de la app. */
enum class RecStatus { STOPPED, ARMED, RECORDING }

/** Modo de grabacion. */
enum class Mode { VIDEO, AUDIO }

/** Eventos que un grabador reporta al servicio. */
interface RecorderEvents {
    fun onSegmentStarted()
    fun onStoppedAll()
    fun onError(message: String)
}

/** Lo que el servicio necesita en comun de cualquier grabador. */
interface Recorder {
    val isRecording: Boolean
    fun stop()
    fun release()
}

/**
 * Estado global observable, sin dependencias externas.
 * El servicio escribe; la Activity observa para pintar la UI.
 */
object RecState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(RecStatus, String) -> Unit>()

    @Volatile var status: RecStatus = RecStatus.STOPPED
        private set
    @Volatile var detail: String = ""
        private set

    fun set(newStatus: RecStatus, newDetail: String = "") {
        status = newStatus
        detail = newDetail
        main.post { listeners.forEach { it(newStatus, newDetail) } }
    }

    fun addListener(l: (RecStatus, String) -> Unit) {
        listeners.add(l)
        main.post { l(status, detail) }
    }

    fun removeListener(l: (RecStatus, String) -> Unit) = listeners.remove(l)
}

/** Presets de calidad. Menor resolucion/fps/bitrate = menos calor y bateria. */
enum class Quality(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int
) {
    LOW("480p - ahorro (menos calor)", 854, 480, 24, 3_500_000),
    MEDIUM("720p - recomendado", 1280, 720, 24, 6_000_000),
    HIGH("1080p - calidad", 1920, 1080, 30, 10_000_000);

    companion object {
        fun from(ordinal: Int) = entries.getOrElse(ordinal) { MEDIUM }
    }
}

/** Preferencias simples. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("optirec", Context.MODE_PRIVATE)

    var quality: Quality
        get() = Quality.from(sp.getInt("quality", Quality.MEDIUM.ordinal))
        set(v) = sp.edit().putInt("quality", v.ordinal).apply()

    /** true = camara trasera, false = frontal */
    var useBackCamera: Boolean
        get() = sp.getBoolean("back_cam", true)
        set(v) = sp.edit().putBoolean("back_cam", v).apply()

    var mode: Mode
        get() = Mode.entries.getOrElse(sp.getInt("mode", 0)) { Mode.VIDEO }
        set(v) = sp.edit().putInt("mode", v.ordinal).apply()

    var torch: Boolean
        get() = sp.getBoolean("torch", false)
        set(v) = sp.edit().putBoolean("torch", v).apply()

    /** Ventana para detectar la doble pulsacion de Vol + (ms). */
    var doubleWindowMs: Long
        get() = sp.getLong("dbl_ms", 500L)
        set(v) = sp.edit().putLong("dbl_ms", v).apply()
}
