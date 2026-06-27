package com.hawksnest.ui.cameras

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

/** Pulls the app's singleton OkHttp client into the composable layer (no ViewModel in this path). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MjpegEntryPoint {
    fun okHttpClient(): OkHttpClient
}

/**
 * A live MJPEG view. Android's `ImageView`/Coil can't render `multipart/x-mixed-replace`, so we
 * open the stream on OkHttp and extract each embedded JPEG by scanning for its SOI (`FF D8`) / EOI
 * (`FF D9`) markers — boundary-agnostic, so it tolerates HA's framing. Until the first frame lands
 * it shows the latest snapshot, so the lightbox is never blank. The read loop is tied to the
 * composition: leaving the lightbox cancels it, which closes the socket (hard stop, no leak).
 */
@Composable
fun MjpegView(
    streamUrl: String,
    snapshotUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val client = remember { entryPoint(context).okHttpClient() }
    var frame by remember(streamUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(streamUrl) {
        withContext(Dispatchers.IO) {
            runCatching { streamMjpeg(client, streamUrl) { bmp -> frame = bmp } }
        }
    }

    val current = frame
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = "Live camera",
            modifier = modifier.background(Color.Black),
            contentScale = ContentScale.Fit,
        )
    } else {
        // No frame yet — hold the (self-refreshing) snapshot so the view is never blank.
        RefreshingSnapshot(url = snapshotUrl, modifier = modifier)
    }
}

private fun entryPoint(context: Context): MjpegEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, MjpegEntryPoint::class.java)

/**
 * Read an MJPEG stream and call [onFrame] for each decoded JPEG. Pure byte-marker extraction: it
 * captures bytes between `FF D8` and `FF D9` and decodes the slice. Returns when the coroutine is
 * cancelled or the stream ends; the `use` block closes the socket on exit.
 */
private suspend fun streamMjpeg(
    client: OkHttpClient,
    url: String,
    onFrame: (ImageBitmap) -> Unit,
) {
    val response = client.newCall(Request.Builder().url(url).build()).execute()
    response.use {
        val input = it.body?.byteStream() ?: return
        val buffer = ByteArrayOutputStream(64 * 1024)
        val data = ByteArray(8192)
        var prev = 0
        var inImage = false
        while (currentCoroutineContext().isActive) {
            val n = input.read(data)
            if (n < 0) break
            for (i in 0 until n) {
                val cur = data[i].toInt() and 0xFF
                if (!inImage) {
                    if (prev == 0xFF && cur == 0xD8) {
                        inImage = true
                        buffer.reset()
                        buffer.write(0xFF)
                        buffer.write(0xD8)
                    }
                } else {
                    buffer.write(cur)
                    if (prev == 0xFF && cur == 0xD9) {
                        val bytes = buffer.toByteArray()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                            onFrame(bmp.asImageBitmap())
                        }
                        inImage = false
                        buffer.reset()
                    }
                }
                prev = cur
            }
        }
    }
}
