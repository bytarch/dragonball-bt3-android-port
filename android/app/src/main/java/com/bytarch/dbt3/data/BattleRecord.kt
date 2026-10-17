package com.bytarch.dbt3.data

import java.io.File

/** One finished online battle, as the engine wrote it down (port/src/plat_records.c: bt3_battles.txt, one line per
 *  decided fight, `mode|winner|reason|epoch|me`). [winner] is bits of the sides (1 side 0 won, 2 side 1 won, 4 a draw),
 *  [me] the side this copy played (0 the host, 1 the one who joined; -1 in a line written before that was said), so
 *  the fight can be read from this device's point of view. */
data class BattleRecord(val mode: Int, val winner: Int, val reason: Int, val at: Long, val me: Int) {
    /** null when the line did not say which side this copy played: an old line, not a loss. */
    val won: Boolean?
        get() = if (me < 0) null else (winner and (if (me == 1) 2 else 1)) != 0
    val drew: Boolean get() = winner and 4 != 0
    val ko: Boolean get() = reason and 0x1 != 0
    val timeUp: Boolean get() = reason and 0x2 != 0

    /** Nobody was knocked out: a player left, or the connection went, and the session ended without a result. */
    val abandoned: Boolean get() = reason and 0x8 != 0

    companion object {
        /** The record so far, newest first. A line that does not read as one is skipped rather than guessed at. */
        fun read(root: File): List<BattleRecord> = runCatching {
            val file = File(root, "bt3_battles.txt")
            if (!file.isFile) return emptyList()
            file.readLines().mapNotNull { line ->
                val f = line.split('|').map { it.trim() }
                val me = f.getOrNull(4)?.toIntOrNull() ?: -1
                runCatching { BattleRecord(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toLong(), me) }.getOrNull()
            }.sortedByDescending { it.at }
        }.getOrDefault(emptyList())
    }
}