package com.bytarch.dbt3.data

import android.content.Context
import java.io.File

/**
 * Where everything lives. The layout is the one the PC port expects next to its program, so the engine can be
 * started with this folder as its working directory and needs no Android-specific paths:
 *
 *   <root>/gamedata/            the disc unpacked (disc/, pzs3us0/ ..., .installed marker)
 *   <root>/gamedata/mods/       file overrides (same path as in gamedata/) — rebuilt from modlib/
 *   <root>/textures/            texture packs in PCSX2 naming, any depth (one folder per pack)
 *   <root>/textures_off/        packs switched off in the app (the engine never looks here)
 *   <root>/stages/  songs/      extra stages (.unk) and songs (.adx), with maps.txt / songs.txt for names
 *   <root>/saves/               memory card
 *   <root>/bt3_settings.txt     the engine's settings (name=number)
 *   <root>/modlib/<mod>/        the app's library of file-override mods, each one switchable
 *
 * The root is the app's folder on shared storage (Android/data/<package>/files), so a file manager or adb can
 * reach it too.
 */
class GamePaths(context: Context) {
    val root: File = (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }
    val gameData = File(root, "gamedata")
    val installedMarker = File(gameData, ".installed")
    val mods = File(gameData, "mods")
    val textures = File(root, "textures")
    val texturesOff = File(root, "textures_off")
    val stages = File(root, "stages")
    val songs = File(root, "songs")
    val saves = File(root, "saves")
    val settings = File(root, "bt3_settings.txt")
    val engineEnv = File(root, "bt3_env.txt")
    val modLibrary = File(root, "modlib")
    val temp = File(context.cacheDir, "import")

    fun ensure() {
        listOf(gameData, textures, texturesOff, stages, songs, saves, modLibrary, temp).forEach { it.mkdirs() }
    }

    /** The loader's switch file (main/cpp/loader.c, NAME=value lines): while it holds BT3_NET_OPEN=1 the engine
     * opens its own online screen by itself at the main menu, which is what the launcher's "Online" asks for. Any
     * other line a player put in the file is kept. */
    fun setOnlineEnv(on: Boolean) {
        runCatching {
            val kept = if (engineEnv.exists()) engineEnv.readLines().filterNot { it.startsWith("BT3_NET_OPEN") } else emptyList()
            val lines = kept + if (on) listOf("BT3_NET_OPEN=1") else emptyList()
            if (lines.isEmpty()) engineEnv.delete() else engineEnv.writeText(lines.joinToString("\n") + "\n")
        }
    }

    /** The lines an engine session is started with (port/src/gs/net.c relaunch writes the same ones when it restarts
     * itself for a match). They are replaced as a family: a stale line from last time (a port, a join address)
     * cannot be left behind by accident, and a null map erases them all before a plain Play. */
    fun setSessionEnv(vars: Map<String, String>?) {
        runCatching {
            val kept = if (engineEnv.exists()) {
                engineEnv.readLines().filterNot { line -> sessionKeys.any { line.startsWith("$it=") } }
            } else {
                emptyList()
            }
            val lines = kept + (vars ?: emptyMap()).map { (name, value) -> "$name=$value" }
            if (lines.isEmpty()) engineEnv.delete() else engineEnv.writeText(lines.joinToString("\n") + "\n")
        }
    }

    /** The session's save folder starts empty, the way the engine's own session start has it. */
    fun clearNetSession() {
        runCatching { File(root, "net_session").deleteRecursively() }
    }

    private val sessionKeys = listOf(
        "BT3_NET_SESSION", "BT3_NET_HOST", "BT3_NET_JOIN", "BT3_NET_LOCALPORT", "BT3_NET_PUNCH",
        "BT3_NET_RULES", "BT3_SAVES", "BT3_SOUND_TICKS", "BT3_NOMOVIE",
        "BT3_TURN_SERVER", "BT3_TURN_USER", "BT3_TURN_CRED",
    )
}
