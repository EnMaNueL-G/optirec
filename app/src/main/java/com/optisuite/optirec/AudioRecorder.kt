package com.optisuite.optirec

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grabadora de SOLO AUDIO (voz) en segundo plano. Analoga a [CameraRecorder]
 * pero sin camara: la unica salida es el propio MediaRecorder. Mucho menos
 * consumo y calor; ideal para grabaciones largas.
 *
 * Formato: MP4/AAC-LC (.m4a) mono 44.1 kHz a 96 kbps. Guarda en Grabaciones/OptiRec.
 * Todo el trabajo corre en el hilo `handler`.
 */
class AudioRecorder(
    private val context: Context,
    private val handler: Handler,
    private val events: RecorderEvents
) : Recorder {

    private var recorder: MediaRecorder? = null
    private var currentSink: MediaSink? = null
    private var rotating = false
    @Volatile private var recording = false

    override val isRecording get() = recording

    fun start() {
        recording = true
        handler.post { beginSegment() }
    }

    private fun beginSegment() {
        if (!recording) { stopInternal(); return }
        try {
            val name = "OptiRec_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
            val sink = MediaSink.create(context, name, MediaType.AUDIO)

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(sink.fd)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(96_000)
                setMaxDuration(SEGMENT_MS)
                setMaxFileSize(MAX_SEGMENT_BYTES)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                    ) {
                        handler.post { rotate() }
                    }
                }
                prepare()
                start()
            }
            recorder = rec
            currentSink = sink
            events.onSegmentStarted()
        } catch (e: Exception) {
            fail("No se pudo iniciar el audio: ${e.message}")
        }
    }

    private fun rotate() {
        if (rotating || recorder == null) return
        rotating = true
        closeSegment()
        if (recording) beginSegment() else stopInternal()
        rotating = false
    }

    private fun closeSegment() {
        var stopFailed = false
        try { recorder?.stop() } catch (_: Exception) { stopFailed = true }
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { currentSink?.finish(!stopFailed) } catch (_: Exception) {}
        currentSink = null
    }

    override fun stop() {
        recording = false
        handler.post { stopInternal() }
    }

    private fun stopInternal() {
        closeSegment()
        events.onStoppedAll()
    }

    private fun fail(message: String) {
        recording = false
        closeSegment()
        events.onError(message)
    }

    override fun release() {
        recording = false
        handler.post { closeSegment() }
    }

    companion object {
        const val SEGMENT_MS = 30 * 60 * 1000            // 30 min por archivo de audio
        const val MAX_SEGMENT_BYTES = 512_000_000L       // o ~512 MB
    }
}
