package com.bytarch.dbt3.data

import java.io.File
import kotlin.random.Random

/**
 * The engine's settings file (port/src/plat_settings.c): `name=value` lines, names up to 23 characters, at most
 * 200 numbers. The app edits the same file the engine reads at start, and keeps every line it does not know (the
 * controller bindings the engine writes, for example, and `net_name`, which is text and not a number).
 */
class EngineSettings(private val file: File) {
    private val values = linkedMapOf<String, Int>()
    private val texts = linkedMapOf<String, String>()

    init {
        load()
    }

    @Synchronized
    fun load() {
        values.clear()
        texts.clear()
        if (!file.exists()) return
        file.forEachLine { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEachLine
            val name = line.substring(0, eq).trim()
            if (name.isEmpty() || name.length >= 24) return@forEachLine
            val v = line.substring(eq + 1).trim()
            val n = v.toIntOrNull()
            // A name that is a number in one line and text in another is the number being junk left behind by an
            // older build (net_id=515): the text is the real value, so the number goes, not the text.
            if (n != null) values[name] = n else if (v.isNotEmpty()) {
                texts[name] = v
                values.remove(name)
            }
        }
    }

    @Synchronized
    fun get(name: String, default: Int): Int = values[name] ?: default

    @Synchronized
    fun snapshot(): Map<String, Int> = LinkedHashMap(values)

    @Synchronized
    fun set(name: String, value: Int) {
        require(name.length < 24) { "setting name too long: $name" }
        if (values[name] == value) return
        values[name] = value
        write()
    }

    /**
     * This player's name, made up and saved the first time there is none, so every player has one of their own
     * instead of everyone being "Player". The engine reads the same line (port/src/gs/net.c, net_name).
     */
    @Synchronized
    fun playerName(): String {
        val have = texts[NAME]
        if (have != null && have.isNotEmpty()) return have
        val made = "Player-" + (Random.nextInt(1000).toString().padStart(3, '0'))
        texts[NAME] = made
        write()
        return made
    }

    /** Stores the name; the characters the service accepts are kept (net_match.c) and the rest dropped. */
    @Synchronized
    fun setPlayerName(name: String) {
        val clean = name.trim().filter { it.isLetterOrDigit() || it in " _-." }.take(16)
        if (clean.isEmpty() || clean == texts[NAME]) return
        texts[NAME] = clean
        write()
    }

    /**
     * This device's recovery code: 32 lower-case hex characters, made up the first time. It carries the record to
     * another device - type it in there and both show the same one - and the service stores only its hash.
     */
    @Synchronized
    fun recoveryCode(): String {
        val have = texts[ID]
        if (have != null && have.length == 32) return have
        val made = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { b -> "%02x".format(b) }
        texts[ID] = made
        write()
        return made
    }

    /**
     * Takes the code from another device, so this one shows that record from now on. Anything that is not 32 hex
     * characters is refused and the old code kept: half a code must not become a new, empty player.
     */
    @Synchronized
    fun setRecoveryCode(code: String): Boolean {
        val clean = code.trim().lowercase()
        if (!clean.matches(Regex("^[0-9a-f]{32}$"))) return false
        if (clean == texts[ID]) return true
        texts[ID] = clean
        write()
        return true
    }

    private fun write() {
        file.parentFile?.mkdirs()
        val lines = values.entries.map { "${it.key}=${it.value}" } + texts.entries.map { "${it.key}=${it.value}" }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object Keys {
        const val NAME = "net_name"               // this player's name (text, not a number)
        const val ID = "net_id"                 // the recovery code (text, 32 hex characters)
        // The engine's own (defaults as in port/src/gs/gs_draw.c)
        const val SCALE = "scale"                 // internal resolution 1..8, default 2 (whole; kept for older engines)
        const val SCALE4 = "scale4"               // the same in quarters: 4 = 1x, 6 = 1.5x .. 32 = 8x (wins over "scale")
        const val ASPECT = "aspect_milli"         // 1333, 1600, 1778, 2389, 3556
        const val FX_OFF = "fx_off"               // bits: 1 outline, 2 see-through tint, 4 depth tint, 8 glow, 16 blur
        const val GLOW = "glow"                   // 0..200 %, default 60
        const val MUSIC = "music"                 // 0..200 %
        const val EFFECTS = "effects"             // 0..200 %, sound effects and voices
        const val METER = "meter"                 // frame-rate meter
        const val TEXTURE_PACK = "texture_pack"   // texture packs on / off, default on
        const val FILTER = "filter"               // screen filter: 0 bilinear, 1 sharp bilinear, 2 FXAA, 3 FSR 1, 4 Snapdragon GSR

        // Dragon Rage's own, read by the Android glue of the engine at start
        const val UNLOCK_ALL = "dr_unlock_all"    // 1: run Save_UnlockAll once on the next start, then 0
        const val FPS60 = "dr_fps60"              // reserved for the 60 fps mode
        const val INTERP = "interp"               // smooth motion: 0 off, 1 60, 2 120, 3 240 pictures a second (the engine's)
        const val PRESENT = "present"             // present mode: 0 automatic, 1 vsync (FIFO), 2 mailbox, 3 immediate
        const val GPU_API = "gpu_api"             // the engine's renderer: 0 Vulkan, 1 OpenGL ES 3.2 (written from RENDERER)
        const val RENDERER = "dr_renderer"        // the player's choice: 0 automatic (OpenGL ES for Mali), 1 Vulkan, 2 OpenGL ES
        const val CLASH_SQUARE = "clash_square"   // clashes (beams, rushes): each press of Square counts as a direction; on by default
        const val TOUCH = "dr_touch"              // on-screen controls: 0 off, 1 automatic, 2 always
        const val TOUCH_OPACITY = "dr_touch_alpha" // 10..100 %

        const val GLOW_DEFAULT = 60
        const val SCALE_DEFAULT = 2
        /** The internal resolutions offered, in quarters (the engine's settings window has the same list). */
        val SCALE_STEPS = listOf(4, 5, 6, 7, 8, 10, 12, 16, 20, 24, 32)
        const val ASPECT_DEFAULT = 0              // 0 = the screen's own shape (the engine measures it)
    }
}
