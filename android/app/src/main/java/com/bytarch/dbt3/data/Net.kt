package com.bytarch.dbt3.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Plain HTTPS downloads (no libraries): GitHub's API, the texture catalog, packs, app updates. */
object Net {
    private fun open(url: String): HttpURLConnection {
        if (!url.startsWith("https://")) throw IOException("Only https links are allowed")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DBT3-Android")
            setRequestProperty("Accept", "application/vnd.github+json, application/json, */*")
        }
    }

    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** POSTs [body] (JSON) and answers with the response's text. A refusal carries the service's own answer as the
     * exception message: the matchmaking service (port/matchmaking) says what went wrong in it. */
    suspend fun postJson(url: String, body: String): String = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (c.responseCode !in 200..299) throw IOException(text.ifBlank { "HTTP ${c.responseCode}" })
            text
        } finally {
            c.disconnect()
        }
    }

    /** Downloads [url] to [dest]; [onProgress] gets (bytes so far, total or -1) a few times a second. */
    suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            dest.parentFile?.mkdirs()
            var done = 0L
            var last = 0L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 200) {
                            last = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, if (total > 0) total else done)
        } catch (e: Throwable) {
            dest.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** True if the file starts with the zip signature ("PK"): catches HTML pages served instead of the file. */
    fun looksLikeZip(f: File): Boolean = f.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
}
