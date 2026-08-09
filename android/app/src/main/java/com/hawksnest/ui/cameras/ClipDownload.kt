package com.hawksnest.ui.cameras

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What happened to an export. [Saved] carries a shareable URI when the platform can produce one. */
sealed interface ClipSaveResult {
    data class Saved(val uri: Uri?, val fileName: String) : ClipSaveResult

    data class Failed(val message: String) : ClipSaveResult
}

/**
 * Longer than the default OkHttp read timeout because Frigate muxes the clip on demand: the
 * response headers can land well before the first bytes, and a 10-minute export legitimately takes
 * a while to produce.
 */
private val EXPORT_CLIENT =
    OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .readTimeout(java.time.Duration.ofMinutes(10))
        .writeTimeout(java.time.Duration.ofSeconds(30))
        .build()

/**
 * Download a clip-export URL and save it to the gallery.
 *
 * **Streams straight into the destination.** The snapshot save next door does
 * `resp.body.bytes()`, which is fine for a jpg and an out-of-memory crash for a 300 MB clip —
 * the one thing not to copy from it.
 *
 * `DownloadManager` was the obvious alternative and is deliberately not used: it can only write to
 * `Downloads/` on Android 10+, not `Movies/`, so the clip would not land in the gallery beside the
 * snapshots; it cannot hand back a `MediaStore.Video` URI to share; and its failures surface as a
 * bare status code rather than Frigate's own message. The cost of doing it here instead is that a
 * very long export dies if the process does — bounded by the 10-minute cap, and the caller runs
 * this in `viewModelScope` so it survives leaving the screen.
 *
 * [url] must already be signed: the proxy 401s otherwise, and this deliberately sends no
 * `Authorization` header.
 */
suspend fun saveClipExport(
    context: Context,
    url: String,
    fileName: String,
): ClipSaveResult =
    withContext(Dispatchers.IO) {
        runCatching {
            EXPORT_CLIENT.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Frigate answers "no recordings for that range" as a 400 with a JSON body.
                    // Reading it is what turns a dead-end failure into a sentence — the Android
                    // equivalent of the web download's pre-flight probe.
                    val body = resp.body?.string().orEmpty()
                    return@withContext ClipSaveResult.Failed(frigateMessage(body, resp.code))
                }
                val source = resp.body ?: return@withContext ClipSaveResult.Failed("Empty response.")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values =
                        ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            put(
                                MediaStore.Video.Media.RELATIVE_PATH,
                                "${Environment.DIRECTORY_MOVIES}/Hawksnest",
                            )
                            // Hide the row until the bytes are all there, so the gallery never
                            // shows a half-written clip.
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    val resolver = context.contentResolver
                    val uri =
                        resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            ?: return@withContext ClipSaveResult.Failed("Couldn't create the file.")
                    resolver.openOutputStream(uri)?.use { out ->
                        source.byteStream().use { input -> input.copyTo(out) }
                    } ?: return@withContext ClipSaveResult.Failed("Couldn't open the file.")
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    ClipSaveResult.Saved(uri, fileName)
                } else {
                    // Pre-Q: app-specific external storage, matching the snapshot save's fallback.
                    // No share sheet here — there is no FileProvider in the manifest, and adding
                    // one for a path the phone in use never takes isn't worth the surface.
                    val dir =
                        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                            ?: return@withContext ClipSaveResult.Failed("No storage available.")
                    val file = File(dir, fileName)
                    file.outputStream().use { out ->
                        source.byteStream().use { input -> input.copyTo(out) }
                    }
                    ClipSaveResult.Saved(null, fileName)
                }
            }
        }
            .getOrElse { ClipSaveResult.Failed(it.message ?: "Export failed.") }
    }

/** Pull Frigate's `message` out of an error body, falling back to the status code. */
private fun frigateMessage(body: String, code: Int): String {
    val match = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)
    return match?.groupValues?.get(1) ?: "Export failed ($code)."
}

/** Share a saved clip. No-op when the save produced no shareable URI (pre-Q). */
fun shareClip(context: Context, uri: Uri) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
