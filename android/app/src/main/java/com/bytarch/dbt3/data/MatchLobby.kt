package com.bytarch.dbt3.data

import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Matchmaking from the launcher, with a room code or the quick-match queue (the two ways the service pairs players,
 * port/matchmaking; every answer is documented at the top of worker.js), and the same trick the lobby plays before a
 * session begins (port/src/gs/net.c, net_match.c):
 *
 *   - one UDP socket is kept for the whole wait; the match is begun on the port it had, so the routers on the way
 *     keep taking the session for the conversation they already saw;
 *   - STUN says which address and port the internet sees that socket under; that, the local address (two players
 *     behind one router) and the netcode version are what the service is told;
 *   - when the other player is known, a few probes over the socket find the address that really answers, and the
 *     engine is started with that address in its environment (BT3_NET_HOST / BT3_NET_JOIN, read at boot by net.c) -
 *     it opens the match itself, with no game running before it.
 *
 * Direct connections only: when the two machines cannot open a path to each other, the game's own lobby (with its
 * relay) is the way.
 */
class MatchLobby(service: String) {
    /** The socket the service's answer and the probes go through. The engine binds this same port at session start. */
    val socket = DatagramSocket()
    val localPort: Int get() = socket.localPort

    private val url = service.trimEnd('/')

    /** The address the internet sees this machine under ("ip:port"), or "" when no STUN server answered. */
    fun publicAddr(): String = stun("stun.cloudflare.com", 3478) ?: stun("stun.l.google.com", 19302) ?: ""

    /** This machine's address on its own network, for two players behind one router. */
    fun lanAddr(): String = runCatching {
        for (iface in NetworkInterface.getNetworkInterfaces()) {
            if (iface.isLoopback) continue
            for (a in iface.inetAddresses) {
                if (a.isSiteLocalAddress && a.address.size == 4) {
                    return@runCatching "${a.hostAddress}:$localPort"
                }
            }
        }
        ""
    }.getOrDefault("")

    /** Makes a room; the code it answers with is what the other player types. */
    suspend fun host(name: String, addrs: List<String>): Pair<String, String> {
        val answer = post("host", JSONObject().put("v", VERSION).put("name", name).put("addrs", JSONArray(addrs)))
        return answer.getString("code") to answer.getString("key")
    }

    /** Whether anyone has joined the room yet; null while it waits. */
    suspend fun poll(code: String, key: String): Peer? {
        val answer = post("poll", JSONObject().put("code", code).put("key", key))
        return peerOf(answer.optJSONObject("peer"))
    }

    /** Joins a room: the answer carries the host's addresses at once. */
    suspend fun join(code: String, name: String, addrs: List<String>): Peer {
        val answer = post("join", JSONObject().put("v", VERSION).put("code", code).put("name", name).put("addrs", JSONArray(addrs)))
        return peerOf(answer.optJSONObject("peer")) ?: throw IOException("no peer")
    }

    /** What one answer of the quick-match queue says. */
    data class QueueAnswer(val key: String, val ahead: Int, val total: Int, val pair: QueuePair?)

    /** A pair the queue made: [matched] is which side of it this player took (1 waited and hosts, 2 arrived and joins),
     * and [code]/[key] are the room the service made for the pair. */
    data class QueuePair(val matched: Int, val code: String, val key: String, val peer: Peer)

    /** The place in the queue this side is in while it waits: given up when the wait ends (AppViewModel). */
    var queueKey: String? = null
        private set

    /** Quick match: waits in the service's queue until it pairs this side with somebody, or [giveUpMs] passes with
     * nobody waiting (null then). [onWaiting] carries the numbers the service reports while waiting. The pair is the
     * same as a room the service made for us: the waiting player hosts, this player joins when it arrived second. */
    suspend fun quickMatch(name: String, addrs: List<String>, giveUpMs: Long, onWaiting: (ahead: Int, total: Int) -> Unit): QueuePair? {
        var answer = queueAnswer(post("queue", JSONObject().put("v", VERSION).put("name", name).put("addrs", JSONArray(addrs))))
        var waited = 0L
        while (answer.pair == null && waited < giveUpMs) {
            queueKey = answer.key
            onWaiting(answer.ahead, answer.total)
            delay(2000)
            waited += 2000
            answer = queueAnswer(post("queue", JSONObject().put("key", answer.key)))
        }
        queueKey = null // the place is gone: what is left is a room
        return answer.pair
    }

    /** Gives the place in the queue up (best effort: a pair already made is left alone, and the service forgets a
     * waiting player that stops asking). */
    suspend fun queueGiveUp() {
        val key = queueKey ?: return
        queueKey = null
        runCatching { post("qcancel", JSONObject().put("key", key)) }
    }

    private fun queueAnswer(answer: JSONObject): QueueAnswer {
        val pair = if (answer.optInt("matched") != 0)
            QueuePair(
                answer.getInt("matched"),
                answer.getString("code"),
                answer.getString("key"),
                peerOf(answer.optJSONObject("peer")) ?: throw IOException("no peer"),
            )
        else null
        return QueueAnswer(answer.optString("key", ""), answer.optInt("waiting"), answer.optInt("queue"), pair)
    }

    /** Gives the room up (best effort: it also runs out on its own once the host stops asking). */
    suspend fun leave(code: String, key: String) {
        runCatching { post("leave", JSONObject().put("code", code).put("key", key)) }
    }

    /** Sends probes to [addrs] (and answers the other side's) until one arrives; the address a packet arrives FROM is
     * the one this side really sees the other under - the address the engine must be told, not the one the service
     * said. Blocks for at most [timeoutMs] and answers null then. */
    fun probe(addrs: List<String>, timeoutMs: Long): String? {
        val ping = PROBE_PING.toByteArray(Charsets.US_ASCII)
        val pong = PROBE_PONG.toByteArray(Charsets.US_ASCII)
        socket.soTimeout = 200
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var nextSend = 0L
        while (System.nanoTime() < deadline) {
            val now = System.nanoTime()
            if (now >= nextSend) {
                for (a in addrs) {
                    val at = a.lastIndexOf(':')
                    if (at < 1) continue
                    runCatching {
                        socket.send(DatagramPacket(ping, ping.size, InetAddress.getByName(a.substring(0, at)), a.substring(at + 1).toInt()))
                    }
                }
                nextSend = now + 400_000_000
            }
            val rx = ByteArray(64)
            val packet = DatagramPacket(rx, rx.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            }
            val text = String(rx, 0, packet.length, Charsets.US_ASCII)
            if (text.startsWith(PROBE_PING)) {
                runCatching { socket.send(DatagramPacket(pong, pong.size, packet.address, packet.port)) }
                return "${packet.address.hostAddress}:${packet.port}"
            }
            if (text.startsWith(PROBE_PONG)) {
                return "${packet.address.hostAddress}:${packet.port}"
            }
        }
        return null
    }

    /** One STUN binding request over the socket: the address the server answers with is what it sees this side under.
     * Two servers are tried by the caller; a lost packet or a server that never answers is just a null. */
    private fun stun(host: String, port: Int): String? = runCatching {
        val ask = ByteArray(20)
        ask[1] = 1 // binding request, no attributes
        ask[4] = 0x21; ask[5] = 0x12; ask[6] = 0xA4.toByte(); ask[7] = 0x42 // the cookie RFC 5389 asks for
        ByteArray(12).also { java.util.Random().nextBytes(it) }.copyInto(ask, 8)
        socket.soTimeout = 700
        socket.send(DatagramPacket(ask, ask.size, InetAddress.getByName(host), port))
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            val rx = ByteArray(512)
            val answer = DatagramPacket(rx, rx.size)
            try {
                socket.receive(answer)
            } catch (e: SocketTimeoutException) {
                continue
            }
            mapped(rx, answer.length)?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    /** The XOR-MAPPED-ADDRESS (or MAPPED-ADDRESS) of a STUN answer, as "ip:port"; null for anything else. */
    private fun mapped(rx: ByteArray, len: Int): String? {
        if (len < 28 || (rx[0].toInt() and 0x80) != 0) return null
        val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
        for (i in 0..3) if (rx[4 + i] != cookie[i]) return null
        var off = 20
        while (off + 4 <= len) {
            val type = ((rx[off].toInt() and 0xFF) shl 8) or (rx[off + 1].toInt() and 0xFF)
            val size = ((rx[off + 2].toInt() and 0xFF) shl 8) or (rx[off + 3].toInt() and 0xFF)
            if ((type == 0x0020 || type == 0x0001) && size >= 8 && off + 12 <= len) {
                var at = ((rx[off + 6].toInt() and 0xFF) shl 8) or (rx[off + 7].toInt() and 0xFF)
                val ip = rx.copyOfRange(off + 8, off + 12)
                if (type == 0x0020) {
                    at = at xor 0x2112
                    for (i in 0..3) ip[i] = (ip[i].toInt() xor cookie[i].toInt()).toByte()
                }
                return "${InetAddress.getByAddress(ip).hostAddress}:$at"
            }
            off += 4 + size + (4 - size % 4) % 4
        }
        return null
    }

    private suspend fun post(verb: String, body: JSONObject): JSONObject =
        JSONObject(Net.postJson("$url/v1/$verb", body.toString()))

    private fun peerOf(peer: JSONObject?): Peer? {
        if (peer == null) return null
        val list = peer.optJSONArray("addrs") ?: JSONArray()
        // "relay=" and "seen=" entries are the relay's own bookkeeping: a direct path is what this launcher is for
        val addrs = (0 until list.length()).map { list.optString(it) }.filter { it.isNotEmpty() && !it.contains("=") }
        return Peer(peer.optString("name", "Player"), addrs)
    }

    /** The other player: the name to show while connecting, and the addresses to try. */
    data class Peer(val name: String, val addrs: List<String>)

    companion object {
        /** The game's netcode version (net.c NET_VERSION): the service refuses a mismatched pair, and so should we. */
        private const val VERSION = 3

        private const val PROBE_PING = "dbt3-launcher-ping"
        private const val PROBE_PONG = "dbt3-launcher-pong"

        /** The room service: the one compiled into the game, or the one named by match_url.txt in the game folder
         * (net_match.c reads the same file on Android). */
        fun service(root: File): String {
            val named = File(root, "match_url.txt")
            val text = runCatching { if (named.exists()) named.readLines().firstOrNull()?.trim().orEmpty() else "" }.getOrDefault("")
            return if (text.startsWith("https://")) text else "https://dbt3-server.bytarch.com"
        }
    }
}
