package com.optisuite.optirec

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor

/** Tipo de medio a guardar. */
enum class MediaType { VIDEO, AUDIO }

/**
 * Destino de un segmento de grabacion.
 *
 * En Android 10+ (API 29) escribe directamente via MediaStore usando IS_PENDING
 * para que no aparezca a medias:
 *  - VIDEO -> Galeria, album Movies/OptiRec.
 *  - AUDIO -> Grabaciones/OptiRec (API 31+) o Music/OptiRec (API 29-30).
 * En versiones anteriores cae al almacenamiento privado de la app.
 *
 * La grabacion escribe en `fd`; al terminar se llama a finish(success) que
 * publica el archivo (o lo borra si fallo).
 */
class MediaSink private constructor(
    private val context: Context,
    val uri: Uri?,
    private val pfd: ParcelFileDescriptor,
    private val legacyFile: File?
) {
    val fd: FileDescriptor get() = pfd.fileDescriptor

    fun finish(success: Boolean) {
        // El MediaRecorder ya se solto; ahora podemos cerrar el descriptor.
        try { pfd.close() } catch (_: Exception) {}

        val cr = context.contentResolver
        if (uri != null) {
            if (success) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                try { cr.update(uri, v, null, null) } catch (_: Exception) {}
            } else {
                try { cr.delete(uri, null, null) } catch (_: Exception) {}
            }
        } else if (legacyFile != null && !success) {
            try { legacyFile.delete() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val ALBUM = "OptiRec"

        fun create(
            context: Context,
            name: String,
            type: MediaType = MediaType.VIDEO
        ): MediaSink {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val cr = context.contentResolver
                    val collection = if (type == MediaType.AUDIO)
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            if (type == MediaType.AUDIO) "audio/mp4" else "video/mp4"
                        )
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(type))
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = cr.insert(collection, values)
                    if (uri != null) {
                        val pfd = cr.openFileDescriptor(uri, "rw")
                        if (pfd != null) return MediaSink(context, uri, pfd, null)
                        try { cr.delete(uri, null, null) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    // cualquier fallo -> almacenamiento privado
                }
            }
            return legacy(context, name, type)
        }

        private fun relativePath(type: MediaType): String = when {
            type == MediaType.VIDEO -> Environment.DIRECTORY_MOVIES + "/" + ALBUM
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                Environment.DIRECTORY_RECORDINGS + "/" + ALBUM
            else -> Environment.DIRECTORY_MUSIC + "/" + ALBUM
        }

        private fun legacy(context: Context, name: String, type: MediaType): MediaSink {
            val base = if (type == MediaType.AUDIO)
                Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            val dir = File(context.getExternalFilesDir(base), ALBUM)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            val pfd = ParcelFileDescriptor.open(
                f,
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            )
            return MediaSink(context, null, pfd, f)
        }
    }
}
