package com.hawksnest.crash

import android.content.Context
import com.hawksnest.core.logic.MAX_STORED_CRASHES
import com.hawksnest.core.logic.trimToMostRecent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crash reports on disk, newest first.
 *
 * Plain files rather than DataStore, deliberately: this is written from an uncaught-exception
 * handler on a process that is already dying, so the write has to be synchronous and must not
 * need a coroutine, a lock, or a healthy Hilt graph. `File.writeText` is about the only thing
 * that qualifies.
 *
 * Lives in `filesDir`, which is app-private. Note it is NOT excluded from cloud backup — unlike
 * the credential DataStore — because [com.hawksnest.core.logic.scrubSecrets] runs before anything
 * is written, so a report should hold nothing worth protecting. If that scrubbing is ever
 * loosened, revisit `res/xml/backup_rules.xml` at the same time.
 */
@Singleton
class CrashStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "crashes").apply { mkdirs() }

    /** Newest first. Filenames are `crash-<epochMillis>.txt`, so name order is time order. */
    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun read(file: File): String = runCatching { file.readText() }.getOrDefault("")

    /** Called from the dying process — synchronous, no coroutines, swallows its own failures. */
    fun write(whenMs: Long, body: String) {
        runCatching {
            File(dir, "crash-$whenMs.txt").writeText(body)
            prune()
        }
    }

    /** Reports captured but not yet published. Marked by a sibling `.sent` file. */
    fun unsent(): List<File> = list().filter { !sentMarker(it).exists() }

    fun markSent(file: File) {
        runCatching { sentMarker(file).writeText("") }
    }

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun sentMarker(f: File) = File(f.parentFile, "${f.name}.sent")

    private fun prune() {
        val keep = trimToMostRecent(list(), MAX_STORED_CRASHES).toSet()
        list().filterNot { it in keep }.forEach { f ->
            f.delete()
            sentMarker(f).delete()
        }
    }
}
