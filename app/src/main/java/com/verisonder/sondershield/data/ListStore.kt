package com.verisonder.sondershield.data

import android.content.Context
import com.verisonder.sondershield.core.BlockList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads block lists to app storage. The engine reads the files directly. */
class ListStore(context: Context) {
    private val dir = File(context.filesDir, "lists").apply { mkdirs() }
    private val prefs = Prefs(context)

    fun file(id: String) = File(dir, "$id.txt")

    /** Files of the enabled lists that have been downloaded, one path per line. */
    fun enabledPaths(): String =
        prefs.allLists().filter { it.id in prefs.enabledLists }
            .map { file(it.id) }.filter { it.isFile && it.length() > 0 }
            .joinToString("\n") { it.absolutePath }

    fun missingEnabled(): List<BlockList> =
        prefs.allLists().filter { it.id in prefs.enabledLists && !file(it.id).isFile }

    /**
     * Downloads one list unless the server says it hasn't changed.
     * Returns true when the file changed.
     */
    suspend fun update(list: BlockList, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val old = prefs.listMeta(list.id)
        val target = file(list.id)
        try {
            val c = URL(list.url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "SonderShield")
            if (!force && target.isFile) {
                if (old.etag.isNotEmpty()) c.setRequestProperty("If-None-Match", old.etag)
                if (old.lastModified.isNotEmpty()) c.setRequestProperty("If-Modified-Since", old.lastModified)
            }
            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                prefs.setListMeta(list.id, old.copy(updated = System.currentTimeMillis(), error = ""))
                c.disconnect()
                return@withContext false
            }
            if (code !in 200..299) {
                c.disconnect()
                throw RuntimeException("HTTP $code")
            }
            val tmp = File(dir, "${list.id}.part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val entries = shield.Shield.countList(tmp.absolutePath).toInt()
            if (entries == 0) {
                tmp.delete()
                throw RuntimeException("no domains in the file")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            prefs.setListMeta(
                list.id,
                ListMeta(
                    entries, System.currentTimeMillis(),
                    c.getHeaderField("ETag").orEmpty(), c.getHeaderField("Last-Modified").orEmpty(), "",
                ),
            )
            c.disconnect()
            true
        } catch (e: Exception) {
            prefs.setListMeta(list.id, old.copy(error = e.message ?: e.javaClass.simpleName))
            false
        }
    }

    /** Updates every enabled list. Returns true when any file changed. */
    suspend fun updateEnabled(force: Boolean = false): Boolean {
        var changed = false
        for (l in prefs.allLists().filter { it.id in prefs.enabledLists }) {
            if (update(l, force)) changed = true
        }
        return changed
    }

    fun delete(id: String) {
        file(id).delete()
        prefs.sp.edit().remove("meta.$id").apply()
    }
}
