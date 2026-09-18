package com.optisuite.optirec

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.OrientationEventListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Servicio en primer plano que es dueno de la camara/microfono, el atajo de
 * volumen y el wake lock. Se arranca desde la Activity (visible) y a partir de
 * ahi la grabacion continua con la pantalla apagada hasta que el usuario para.
 *
 * Soporta dos modos ([Mode.VIDEO] / [Mode.AUDIO]) eligiendo el grabador.
 */
class RecordingService : Service(), RecorderEvents {

    private lateinit var recThread: HandlerThread
    private lateinit var recHandler: Handler
    private lateinit var recorder: Recorder
    private lateinit var thermal: ThermalMonitor
    private lateinit var shortcut: VolumeShortcut
    private lateinit var prefs: Prefs

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var armed = false
    private var segments = 0
    private var builtMode = Mode.VIDEO
    @Volatile private var recStartMs = 0L
    @Volatile private var stopReason: String? = null

    // Orientacion fisica del telefono (para grabar bien a ciegas).
    @Volatile private var deviceDeg = 0
    private val orientationListener by lazy {
        object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                deviceDeg = ((o + 45) / 90 * 90) % 360
            }
        }
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VibratorManager::class.java))?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        recThread = HandlerThread("optirec-rec").apply { start() }
        recHandler = Handler(recThread.looper)
        buildRecorderForMode()
        thermal = ThermalMonitor(this)
        shortcut = VolumeShortcut(this, prefs.doubleWindowMs) { toggle() }
    }

    private fun buildRecorderForMode() {
        builtMode = prefs.mode
        recorder = if (builtMode == Mode.AUDIO)
            AudioRecorder(this, recHandler, this)
        else
            CameraRecorder(this, recHandler, this, { deviceDeg }, { prefs.torch })
    }

    /** Si el usuario cambio de modo (y no esta grabando), recrea el grabador. */
    private fun ensureRecorderForMode() {
        if (builtMode != prefs.mode && !recorder.isRecording) {
            try { recorder.release() } catch (_: Exception) {}
            buildRecorderForMode()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Actions.START -> { arm(); startRecording() }
            Actions.ARM -> arm()
            Actions.TOGGLE -> toggle()
            Actions.STOP_REC -> stopRecording()
            Actions.EXIT -> { fullStop(); return START_NOT_STICKY }
            else -> {
                // intent nulo = el sistema intento resucitar el servicio. Una
                // grabadora la dirige el usuario: no debe autoencenderse (y el
                // tipo camara/micro no se puede arrancar en segundo plano).
                if (intent == null) { stopSelf(); return START_NOT_STICKY }
                arm()
            }
        }
        return START_NOT_STICKY
    }

    // --- Estados -----------------------------------------------------------

    private fun arm() {
        if (armed) return
        armed = true
        // Sin wake lock en reposo: el audio silencioso mantiene vivo el atajo y
        // dejamos que la CPU duerma -> menos bateria y menos calor sin grabar.
        shortcut.start()
        thermal.start(onCritical = { recHandler.post { onThermalCritical() } })
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        goForeground(RecStatus.ARMED)
        RecState.set(RecStatus.ARMED, "Doble Vol + para grabar")
    }

    private fun toggle() {
        if (recorder.isRecording) {
            stopRecording()
            vibrateStop() // 2 pulsos: has parado (feedback a ciegas)
        } else {
            startRecording()
            if (recorder.isRecording) vibrateStart() // 1 pulso: esta grabando
        }
    }

    private fun startRecording() {
        ensureRecorderForMode()
        if (!hasCaptureForMode()) {
            RecState.set(RecStatus.ARMED, "Faltan permisos de " +
                if (prefs.mode == Mode.AUDIO) "micrófono" else "cámara/micrófono")
            return
        }
        if (recorder.isRecording) return
        if (lowStorage()) {
            RecState.set(RecStatus.ARMED, "Espacio insuficiente para grabar")
            return
        }
        if (!armed) arm()
        segments = 0
        stopReason = null
        recStartMs = System.currentTimeMillis()
        acquireWakeLock() // solo mientras se graba
        goForeground(RecStatus.RECORDING)
        RecState.set(RecStatus.RECORDING, "Preparando...")
        when (val r = recorder) {
            is CameraRecorder -> r.start(prefs.useBackCamera) { thermal.adjust(prefs.quality) }
            is AudioRecorder -> r.start()
        }
    }

    private fun stopRecording() {
        if (!recorder.isRecording) return
        recorder.stop() // onStoppedAll actualizara el estado a ARMED
    }

    private fun onThermalCritical() {
        if (recorder.isRecording) {
            stopReason = "Detenido: temperatura crítica del teléfono"
            recorder.stop()
        }
    }

    private fun fullStop() {
        armed = false
        try { thermal.stop() } catch (_: Exception) {}
        try { shortcut.stop() } catch (_: Exception) {}
        try { orientationListener.disable() } catch (_: Exception) {}
        try { recorder.release() } catch (_: Exception) {}
        releaseWakeLock()
        RecState.set(RecStatus.STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Callbacks del grabador -------------------------------------------

    override fun onSegmentStarted() {
        segments++
        goForeground(RecStatus.RECORDING)
        RecState.set(RecStatus.RECORDING, "Grabando · archivo $segments")
    }

    override fun onStoppedAll() {
        releaseWakeLock() // ya no grabamos: soltar CPU
        if (segments > 0 && stopReason == null) toastSaved()
        if (armed) {
            goForeground(RecStatus.ARMED)
            RecState.set(RecStatus.ARMED, stopReason ?: "Guardado. Doble Vol + para grabar")
        }
        stopReason = null
    }

    override fun onError(message: String) {
        releaseWakeLock()
        goForeground(RecStatus.ARMED)
        RecState.set(RecStatus.ARMED, message)
    }

    // --- Notificacion / foreground ----------------------------------------

    private fun goForeground(status: RecStatus) {
        val notif = buildNotification(status)
        val type = foregroundType()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        } catch (e: Exception) {
            // Si el sistema rechaza el tipo camara/micro, degradar a mediaPlayback.
            try {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } catch (_: Exception) {}
        }
    }

    /** Tipo de foreground service segun el modo (audio no declara camara). */
    private fun foregroundType(): Int {
        val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val cam = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return if (prefs.mode == Mode.AUDIO) {
            if (hasMic()) mic or media else media
        } else {
            if (hasMic() && hasCamera()) cam or mic or media else media
        }
    }

    private fun buildNotification(status: RecStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val recording = status == RecStatus.RECORDING
        val text = if (recording) {
            val d = RecState.detail
            if (d.isNotBlank()) d else getString(R.string.notif_recording)
        } else getString(R.string.notif_ready)

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(
                0,
                if (recording) getString(R.string.btn_stop) else getString(R.string.btn_start),
                servicePending(Actions.TOGGLE, 1)
            )
            .addAction(0, getString(R.string.notif_action_stop), servicePending(Actions.EXIT, 2))

        if (recording) {
            b.setWhen(recStartMs).setShowWhen(true).setUsesChronometer(true)
        } else {
            b.setShowWhen(false).setUsesChronometer(false)
        }
        return b.build()
    }

    private fun servicePending(action: String, code: Int): PendingIntent {
        val i = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(
            this, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // --- Feedback / utilidades --------------------------------------------

    private fun toastSaved() {
        val msg = if (prefs.mode == Mode.AUDIO)
            getString(R.string.saved_audio) else getString(R.string.saved_video)
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun vibrateStart() {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun vibrateStop() {
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), -1))
        } catch (_: Exception) {}
    }

    private fun lowStorage(): Boolean {
        return try {
            val path = getExternalFilesDir(null) ?: return false
            val stat = StatFs(path.absolutePath)
            stat.availableBytes < 500_000_000L // < ~500 MB
        } catch (_: Exception) { false }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OptiRec::rec").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // tope de seguridad: 6 h
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasCamera() = ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasCaptureForMode(): Boolean =
        if (prefs.mode == Mode.AUDIO) hasMic() else (hasMic() && hasCamera())

    override fun onDestroy() {
        try { thermal.stop() } catch (_: Exception) {}
        try { shortcut.stop() } catch (_: Exception) {}
        try { orientationListener.disable() } catch (_: Exception) {}
        try { recorder.release() } catch (_: Exception) {}
        releaseWakeLock()
        try { recThread.quitSafely() } catch (_: Exception) {}
        super.onDestroy()
    }
}
