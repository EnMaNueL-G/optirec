package com.optisuite.optirec

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.util.Range
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Graba video + audio SIN vista previa (headless): unica salida = la Surface del
 * MediaRecorder. Mantiene el CameraDevice abierto entre segmentos. Corta en
 * segmentos periodicos; en cada corte pide parametros nuevos (para adaptarse al
 * calor) mediante nextParams().
 *
 * TODO el trabajo de camara corre en un unico hilo (el `handler`), lo que evita
 * carreras entre iniciar/parar/rotar. Ajustes anti-calor: fps bloqueado,
 * estabilizacion optica/digital apagada, encoder por hardware.
 */
class CameraRecorder(
    private val context: Context,
    private val handler: Handler,
    private val events: RecorderEvents,
    private val deviceRotation: () -> Int,
    private val torchOn: () -> Boolean
) : Recorder {

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var currentSink: MediaSink? = null
    private var rotating = false

    @Volatile private var recording = false
    @Volatile private var useBack = true
    @Volatile private var nextParams: (() -> RecParams?)? = null

    override val isRecording get() = recording

    private fun cameraIdFor(back: Boolean): String? {
        val want = if (back) CameraCharacteristics.LENS_FACING_BACK
        else CameraCharacteristics.LENS_FACING_FRONT
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == want) return id
        }
        return cm.cameraIdList.firstOrNull()
    }

    private fun chooseSize(id: String, w: Int, h: Int): Size {
        val map = cm.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(w, h)
        return sizes.firstOrNull { it.width == w && it.height == h }
            ?: sizes.minByOrNull { Math.abs(it.width.toLong() * it.height - w.toLong() * h) }
            ?: Size(w, h)
    }

    private fun sensorOrientation(id: String): Int =
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    /** Entrada publica: se puede llamar desde cualquier hilo. */
    fun start(useBackCamera: Boolean, provider: () -> RecParams?) {
        useBack = useBackCamera
        nextParams = provider
        recording = true
        handler.post { startOnCam() }
    }

    @SuppressLint("MissingPermission")
    private fun startOnCam() {
        if (!recording) return
        val id = cameraIdFor(useBack) ?: run {
            fail("No hay cámara disponible"); return
        }
        if (device != null) { beginSegment(id); return }
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    if (recording) beginSegment(id) else { cam.close(); device = null }
                }
                override fun onDisconnected(cam: CameraDevice) {
                    cam.close(); device = null
                    fail("La cámara se desconectó")
                }
                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close(); device = null
                    fail("Error de cámara ($error)")
                }
            }, handler)
        } catch (e: Exception) {
            fail("No se pudo abrir la cámara: ${e.message}")
        }
    }

    private fun buildRecorder(id: String, p: RecParams) {
        val name = "OptiRec_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val sink = MediaSink.create(context, name, MediaType.VIDEO)
        val size = chooseSize(id, p.width, p.height)
        // Orientacion real del telefono (a ciegas no hay preview que lo corrija).
        val deg = deviceRotation()
        val sensor = sensorOrientation(id)
        val hint = if (useBack) (sensor - deg + 360) % 360 else (sensor + deg) % 360

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(sink.fd)
            setVideoEncodingBitRate(p.bitrate)
            setVideoFrameRate(p.fps)
            setVideoSize(size.width, size.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setAudioChannels(1)
            setOrientationHint(hint)
            setMaxDuration(SEGMENT_MS)
            setMaxFileSize(MAX_SEGMENT_BYTES)
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    handler.post { rotate(id) }
                }
            }
            prepare()
        }
        recorder = rec
        currentSink = sink
    }

    private fun beginSegment(id: String) {
        if (!recording) { stopInternal(false); return }
        val p = nextParams?.invoke()
        if (p == null) { recording = false; stopInternal(false); return }
        try {
            buildRecorder(id, p)
            val surface = recorder!!.surface
            val cam = device ?: run { fail("Cámara no abierta"); return }

            val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(p.fps, p.fps))
                // Anti-calor: apaga estabilizacion y el postproceso pesado del ISP.
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                if (useBack && torchOn()) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            }.build()

            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        // El usuario pudo parar mientras se configuraba: no dejar
                        // la sesion viva ni marcar "grabando".
                        if (!recording) {
                            try { s.close() } catch (_: Exception) {}
                            closeSegment()
                            stopInternal(false)
                            return
                        }
                        session = s
                        try {
                            s.setRepeatingRequest(request, null, handler)
                            recorder?.start()
                            events.onSegmentStarted()
                        } catch (e: Exception) {
                            fail("No se pudo iniciar: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        try { s.close() } catch (_: Exception) {}
                        fail("Fallo al configurar la sesión de cámara")
                    }
                }, handler)
        } catch (e: Exception) {
            fail("Error preparando la grabación: ${e.message}")
        }
    }

    /** Cierra el segmento actual y arranca el siguiente si seguimos grabando. */
    private fun rotate(id: String) {
        if (rotating) return
        if (recorder == null) return
        rotating = true
        closeSegment()
        if (recording) beginSegment(id) else stopInternal(false)
        rotating = false
    }

    private fun closeSegment() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        var stopFailed = false
        try { recorder?.stop() } catch (_: Exception) { stopFailed = true }
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        // Publica el video en la galeria; si stop() fallo (sin frames), lo borra.
        try { currentSink?.finish(!stopFailed) } catch (_: Exception) {}
        currentSink = null
    }

    /** Detiene la grabacion pero mantiene la camara abierta (para volver rapido). */
    override fun stop() {
        recording = false
        handler.post { stopInternal(false) }
    }

    private fun stopInternal(startNext: Boolean) {
        closeSegment()
        if (!startNext) events.onStoppedAll()
    }

    /** Camino unico de error: limpia todo y avisa. */
    private fun fail(message: String) {
        recording = false
        closeSegment()
        events.onError(message)
    }

    /** Cierra TODO, incluida la camara. */
    override fun release() {
        recording = false
        handler.post {
            closeSegment()
            try { device?.close() } catch (_: Exception) {}
            device = null
        }
    }

    companion object {
        const val SEGMENT_MS = 10 * 60 * 1000            // 10 min por archivo
        const val MAX_SEGMENT_BYTES = 1_500_000_000L     // o ~1.5 GB, lo que llegue antes
    }
}
