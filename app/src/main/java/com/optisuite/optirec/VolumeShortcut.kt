package com.optisuite.optirec

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.VolumeProviderCompat

/**
 * Atajo "doble Vol +" que funciona con la PANTALLA APAGADA.
 *
 * Truco: mantenemos una MediaSession activa reproduciendo audio en silencio y
 * enrutamos el volumen a nuestro VolumeProvider con setPlaybackToRemote(). A
 * partir de ahi el sistema entrega las pulsaciones fisicas de volumen a
 * onAdjustVolume() aunque la pantalla este apagada y la app en segundo plano.
 *
 * La pulsacion normal se reenvia al volumen real al instante (no molesta al
 * usuario); dos Vol + en menos de la ventana definida disparan el atajo.
 */
class VolumeShortcut(
    private val context: Context,
    private val doubleWindowMs: Long,
    private val onDoubleVolumeUp: () -> Unit
) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private var session: MediaSessionCompat? = null
    private var silence: SilentPlayer? = null

    @Volatile private var lastUp = 0L

    // Reafirma la prioridad de la sesion para no perder el enrutado del volumen
    // si otra app de musica se pone a "reproducir".
    private val reassertHandler = Handler(Looper.getMainLooper())
    private val reassert = object : Runnable {
        override fun run() {
            try {
                session?.let {
                    it.isActive = true
                    it.setPlaybackState(playingState())
                }
            } catch (_: Exception) {}
            reassertHandler.postDelayed(this, 2500L)
        }
    }

    private fun playingState(): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
            .build()

    private val volumeProvider = object : VolumeProviderCompat(
        VOLUME_CONTROL_RELATIVE, maxVol, am.getStreamVolume(AudioManager.STREAM_MUSIC)
    ) {
        override fun onAdjustVolume(direction: Int) {
            when {
                direction > 0 -> { // Vol +
                    // Reenvia al instante para que el volumen responda normal.
                    // Sin FLAG_SHOW_UI: no despertar la pantalla al grabar a oscuras.
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        0
                    )
                    currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUp <= doubleWindowMs) {
                        lastUp = 0L
                        onDoubleVolumeUp() // <-- atajo
                    } else {
                        lastUp = now
                    }
                }
                direction < 0 -> { // Vol -
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        0
                    )
                    currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    lastUp = 0L
                }
            }
        }
    }

    fun start() {
        if (session != null) return
        silence = SilentPlayer().also { it.start() }
        session = MediaSessionCompat(context, "OptiRecShortcut").apply {
            // Un callback vacio marca la sesion como reproductor real (y evita el
            // flag deprecado); asi cuenta para el enrutado del volumen.
            setCallback(object : MediaSessionCompat.Callback() {})
            setPlaybackState(playingState())
            setPlaybackToRemote(volumeProvider)
            isActive = true
        }
        reassertHandler.postDelayed(reassert, 2500L)
    }

    fun stop() {
        reassertHandler.removeCallbacks(reassert)
        session?.release()
        session = null
        silence?.stop()
        silence = null
    }
}

/**
 * Reproduce silencio continuo con un AudioTrack. Mantiene viva una sesion de
 * audio para que el enrutado de volumen nos elija. No pide foco de audio, asi
 * que NO pausa la musica del usuario.
 */
private class SilentPlayer {
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        val sampleRate = 8000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf)
            .build()

        track = t
        t.setVolume(0f)
        t.play()
        running = true
        thread = Thread {
            val zeros = ShortArray(minBuf / 2)
            while (running) {
                try {
                    t.write(zeros, 0, zeros.size)
                } catch (_: Exception) {
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { thread?.join(300) } catch (_: Exception) {}
        thread = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}
