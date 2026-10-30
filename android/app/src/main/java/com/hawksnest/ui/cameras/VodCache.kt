package com.hawksnest.ui.cameras

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * On-disk cache for recorded Frigate VOD media (segments + init), so scrubbing back over footage
 * already watched in this or an earlier session plays from disk instead of re-downloading ~1.4 MB
 * per 60 s segment over the tailnet.
 *
 * Scope, on purpose:
 *  - **Media only, never playlists.** `.m3u8` always goes to the network. Pages in the past are
 *    immutable, but a battery camera's island grows while it records and retention deletes the
 *    oldest footage — a cached manifest would answer with yesterday's view of either.
 *  - **Keyed without `authSig`.** Every page turn re-signs, so the raw URL changes for identical
 *    bytes; the key is the path alone (it already names camera + start + end + segment).
 *  - **App-private cache dir, LRU-bounded.** Footage at rest stays inside the app sandbox and the
 *    OS may reclaim it; nothing here is backed up (cacheDir is excluded from backup by definition).
 */
@UnstableApi
internal object VodCache {
    private const val MAX_BYTES: Long = 256L * 1024 * 1024
    private const val DIR = "vod-media"

    @Volatile private var cache: SimpleCache? = null

    private fun get(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, DIR),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }

    /** Wraps [upstream] so media requests read through the cache and playlists bypass it. */
    fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val cached = CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(CacheKeyFactory { spec -> vodCacheKey(spec.uri.toString()) })
            // A cache I/O error must degrade to the network, never to a failed playback.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory {
            PlaylistBypassDataSource(cached.createDataSource(), upstream.createDataSource())
        }
    }
}

/** Cache key for a VOD request: the URL without its query (drops `authSig`). */
internal fun vodCacheKey(url: String): String = url.substringBefore('?').substringBefore('#')

/** Whether a VOD request is a playlist (never cached) rather than media. */
internal fun isPlaylistRequest(url: String): Boolean =
    url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

/** Routes each [open] to [cached] for media and [direct] for playlists. */
@UnstableApi
private class PlaylistBypassDataSource(
    private val cached: DataSource,
    private val direct: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (isPlaylistRequest(dataSpec.uri.toString())) direct else cached
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
