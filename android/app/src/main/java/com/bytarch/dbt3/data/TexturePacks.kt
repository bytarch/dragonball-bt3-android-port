package com.bytarch.dbt3.data

import android.content.ContentResolver
import android.content.Context
import com.bytarch.dbt3.R
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TexturePack(
    val name: String,
    val enabled: Boolean,
    val textures: Int,
    val bytes: Long,
    val dir: File,
)

/**
 * Texture packs made for the game under PCSX2. The engine reads every `.dds` / `.png` named
 * `<texture hash>-[<palette hash>-]<bits>` at any depth below `textures/` (port/src/gs/gs_texpack.c), so a
 * pack is simply a folder there; switched off, it moves to `textures_off/`.
 */
class TexturePacks(private val context: Context, private val paths: GamePaths, private val resolver: ContentResolver) {

    fun list(): List<TexturePack> {
        fun scan(dir: File, enabled: Boolean) = dir.listFiles { f -> f.isDirectory }.orEmpty().map { d ->
            var count = 0
            var bytes = 0L
            d.walkTopDown().filter { it.isFile }.forEach {
                bytes += it.length()
                if (isReplacement(it.name)) count++
            }
            TexturePack(d.name, enabled, count, bytes, d)
        }
        return (scan(paths.textures, true) + scan(paths.texturesOff, false)).sortedBy { it.name.lowercase() }
    }

    /** Unpacks a zip as a new pack. Returns the pack, or throws with a message for the user. */
    suspend fun import(uri: Uri, onProgress: (String) -> Unit): TexturePack = withContext(Dispatchers.IO) {
        val fileName = resolver.displayName(uri)
        // a zip is known by its first bytes ("PK"), not by its name: some file pickers give names without ".zip"
        val isZip = resolver.openInputStream(uri)?.use { it.read() == 'P'.code && it.read() == 'K'.code }
            ?: throw IllegalStateException(context.getString(R.string.err_open_file))
        if (!isZip) throw IllegalArgumentException(context.getString(R.string.err_zip_only))
        importFrom(fileName.substringBeforeLast('.'), { resolver.openInputStream(uri) ?: throw IllegalStateException(context.getString(R.string.err_open_file)) }, onProgress)
    }

    /** The same from any stream (a downloaded file); [rawName] becomes the pack's folder name. */
    suspend fun importStream(rawName: String, input: InputStream, onProgress: (String) -> Unit): TexturePack =
        importOnce(rawName, input, Charsets.UTF_8, onProgress)

    /**
     * Imports from a source that can be opened again: zips made on Windows often name their files in the old DOS
     * encoding, which Java refuses as UTF-8 ("MALFORMED"); then it is read once more in that encoding.
     */
    suspend fun importFrom(rawName: String, open: () -> InputStream, onProgress: (String) -> Unit): TexturePack = try {
        open().use { importOnce(rawName, it, Charsets.UTF_8, onProgress) }
    } catch (e: IllegalArgumentException) {
        if (e.message?.contains("MALFORMED", ignoreCase = true) != true) throw e
        Log.w("bt3", "texture import: the zip's names are not UTF-8; reading them as CP437")
        open().use { importOnce(rawName, it, java.nio.charset.Charset.forName("IBM437"), onProgress) }
    }

    private suspend fun importOnce(
        rawName: String,
        input: InputStream,
        charset: java.nio.charset.Charset,
        onProgress: (String) -> Unit,
    ): TexturePack = withContext(Dispatchers.IO) {
        val name = safeName(rawName)
        // unpacked next to textures/ (the same storage: the finished pack is moved, not copied, and takes its room once)
        val stagingRoot = File(paths.root, ".import").also { it.mkdirs() }
        val staging = uniqueChild(stagingRoot, name)
        staging.mkdirs()
        Log.i("bt3", "texture import: \"$rawName\" into ${paths.textures}")
        try {
            var found = 0
            val others = mutableListOf<String>()
            val files = try {
                unzip(input, staging, onBytes = { onProgress(context.getString(R.string.progress_extracting, formatBytes(it))) }, charset = charset) { rel ->
                    val file = rel.substringAfterLast('/')
                    if (isReplacement(file)) found++ else if (others.size < 3 && (file.endsWith(".png", true) || file.endsWith(".dds", true))) others += file
                }
            } catch (e: java.util.zip.ZipException) {
                Log.w("bt3", "texture import: the zip cannot be read", e)
                val m = e.message.orEmpty()
                if (m.contains("compression method", true) || m.contains("DEFLATED", true) || m.contains("encrypt", true)) {
                    throw IllegalArgumentException(context.getString(R.string.err_zip_method))
                }
                throw e
            }
            Log.i("bt3", "texture import: $files files, $found replacement textures" + if (others.isEmpty()) "" else ", other images: ${others.joinToString()}")
            if (files == 0) throw IllegalArgumentException(context.getString(R.string.err_zip_empty))
            if (found == 0) {
                val msg = context.getString(R.string.err_no_textures)
                throw IllegalArgumentException(if (others.isEmpty()) msg else msg + " " + context.getString(R.string.err_no_textures_seen, others.joinToString()))
            }
            paths.textures.mkdirs()
            val dest = uniqueChild(paths.textures, name)
            if (!staging.renameTo(dest)) {
                staging.copyRecursively(dest, overwrite = true)
            }
            val pack = list().firstOrNull { it.dir.canonicalPath == dest.canonicalPath }
                ?: throw IllegalStateException(context.getString(R.string.err_open_file))
            Log.i("bt3", "texture import: \"${pack.name}\" ready, ${pack.textures} textures, ${formatBytes(pack.bytes)}")
            pack
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) Log.w("bt3", "texture import failed: ${e.message}", e)
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun setEnabled(pack: TexturePack, enabled: Boolean) = withContext(Dispatchers.IO) {
        if (pack.enabled == enabled) return@withContext
        val dest = uniqueChild(if (enabled) paths.textures else paths.texturesOff, pack.name)
        if (!pack.dir.renameTo(dest)) {
            pack.dir.copyRecursively(dest, overwrite = true)
            pack.dir.deleteRecursively()
        }
    }

    suspend fun delete(pack: TexturePack) = withContext(Dispatchers.IO) { pack.dir.deleteRecursively() }

    companion object {
        private val NAME = Regex("^[0-9a-fA-F]+-([0-9a-fA-F]+-)?[0-9a-fA-F]+\\.(dds|png)$", RegexOption.IGNORE_CASE)
        fun isReplacement(fileName: String) = NAME.matches(fileName)
    }
}
