package com.bytarch.dbt3.data

import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
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
 *   - when the other player is known, the lobby's greeting (the same "BT3N" bytes a running game sends - see probe)
 *     finds the address that really answers, and the engine is started with that address in its environment
 *     (BT3_NET_HOST / BT3_NET_JOIN, read at boot by net.c) - it opens the match itself, with no game running before it.
 *
 * A launcher and a running game can pair this way as easily as two launchers or two games; when nothing direct answers,
 * the hosting side's relayed address is used (the game's own lobby remains the way when that is not enough either).
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

    /** A room this side made: the code the other player types, this side's key for it, and the relay login the service
     * offers (null when it offers none). */
    data class Hosted(val code: String, val key: String, val turn: Turn?)

    /** Makes a room; the code it answers with is what the other player types. */
    suspend fun host(name: String, addrs: List<String>): Hosted {
        val answer = post("host", JSONObject().put("v", VERSION).put("name", name).put("addrs", JSONArray(addrs)))
        return Hosted(answer.getString("code"), answer.getString("key"), turnOf(answer))
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
    data class QueueAnswer(val key: String, val ahead: Int, val total: Int, val pair: QueuePair?, val turn: Turn?)

    /** A pair the queue made: [matched] is which side of it this player took (1 waited and hosts, 2 arrived and joins),
     * and [code]/[key] are the room the service made for the pair. */
    data class QueuePair(val matched: Int, val code: String, val key: String, val peer: Peer)

    /** A TURN login, as the service hands it out ({server, username, credential}; RFC 5766 long-term credentials,
     * the key being MD5 of "user:realm:pass" - port/src/gs/net_relay.c works it the same way). */
    data class Turn(val server: String, val username: String, val credential: String)

    /** The relayed address this side holds, once it has asked for one, and the login the engine is to take the same
     * allocation with (BT3_TURN_* in its environment). */
    var relayAddr: String? = null
        private set
    var relayLogin: Turn? = null
        private set

    /** The place in the queue this side is in while it waits: given up when the wait ends (AppViewModel). */
    var queueKey: String? = null
        private set

    /** Takes a place in the quick-match queue, or is paired at once when somebody is already waiting. */
    suspend fun queueTake(name: String, addrs: List<String>): QueueAnswer =
        queueAnswer(post("queue", JSONObject().put("v", VERSION).put("name", name).put("addrs", JSONArray(addrs))))

    /** Asks whether a place in the queue has become a pair yet; [moreAddrs] are published on the way (the relayed
     * address, once there is one: whoever is paired with this side must be told about it). */
    suspend fun queuePoll(key: String, moreAddrs: List<String> = emptyList()): QueueAnswer {
        val body = JSONObject().put("key", key)
        if (moreAddrs.isNotEmpty()) body.put("addrs", JSONArray(moreAddrs))
        queueKey = key
        return queueAnswer(post("queue", body))
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
        return QueueAnswer(answer.optString("key", ""), answer.optInt("waiting"), answer.optInt("queue"), pair, turnOf(answer))
    }

    /** The login in a service answer ({turn: {...}}, or the three fields at the top of it, as the lobby reads them). */
    private fun turnOf(answer: JSONObject): Turn? {
        val t = answer.optJSONObject("turn") ?: answer
        val server = t.optString("server")
        val user = t.optString("username")
        val cred = t.optString("credential")
        return if (server.isEmpty() || user.isEmpty() || cred.isEmpty()) null else Turn(server, user, cred)
    }

    /** Asks the service's TURN server for a relayed address on this socket, the way net_relay.c does: once without
     * the login (the 401 answer carries the realm and the nonce), then signed with it. Cloudflare's service is
     * sometimes silent on one of its two ports for a whole conversation, so a port that says nothing is tried once
     * on the other. The engine takes this same allocation over when it starts - the same local port to the same
     * server - so the address published to the other player stays the right one. */
    fun takeRelay(turn: Turn?): String? {
        if (turn == null) return null
        val host = turn.server.substringBefore(':')
        val first = turn.server.substringAfter(':', "3478").toIntOrNull() ?: 3478
        for (port in intArrayOf(first, if (first == 3478) 53 else 3478)) {
            val relayed = turnOnce(host, port, turn) ?: continue
            relayAddr = relayed
            relayLogin = turn
            return "relay=$relayed"
        }
        return null
    }

    private fun turnOnce(host: String, port: Int, turn: Turn): String? {
        val server = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        var realm = ""
        var nonce = ""
        socket.soTimeout = 1000
        for (round in 0 until 2) { // the first ask is without the login; the second is with it
            val msg = ByteArray(256) // one Allocate request: header, transport, the three login fields, the integrity
            put16(msg, 0, 0x0003) // Allocate
            put16(msg, 2, 0)
            msg[4] = 0x21; msg[5] = 0x12; msg[6] = 0xA4.toByte(); msg[7] = 0x42 // the cookie RFC 5389 asks for
            ByteArray(12).also { java.util.Random().nextBytes(it) }.copyInto(msg, 8)
            var at = attr(msg, 20, 0x0019, byteArrayOf(17, 0, 0, 0)) // the transport: UDP
            if (round == 1) {
                at = attr(msg, at, 0x0006, turn.username.toByteArray(Charsets.US_ASCII))
                at = attr(msg, at, 0x0014, realm.toByteArray(Charsets.US_ASCII))
                at = attr(msg, at, 0x0015, nonce.toByteArray(Charsets.US_ASCII))
                put16(msg, 2, at - 20 + 24) // the length as it is once the integrity attribute is in it
                val key = java.security.MessageDigest.getInstance("MD5")
                    .digest("$turn.username:$realm:${turn.credential}".toByteArray(Charsets.UTF_8))
                val mac = javax.crypto.Mac.getInstance("HmacSHA1").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA1")) }
                    .doFinal(msg.copyOf(at))
                at = attr(msg, at, 0x0008, mac)
            }
            put16(msg, 2, at - 20)
            runCatching { socket.send(DatagramPacket(msg, at, server, port)) }.getOrElse { return null }
            val rx = ByteArray(512)
            val packet = DatagramPacket(rx, rx.size)
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                if (packet.address != server || packet.port != port) continue
                val type = ((rx[0].toInt() and 0xFF) shl 8) or (rx[1].toInt() and 0xFF)
                if (type == 0x0103) return xorAddr(rx, packet.length, 0x0016) // the allocation is made
                if (type == 0x0113) { // an error answer: 401 carries the realm and the nonce to answer with
                    attrValue(rx, packet.length, 0x0014)?.let { realm = String(it, Charsets.UTF_8) }
                    attrValue(rx, packet.length, 0x0015)?.let { nonce = String(it, Charsets.UTF_8) }
                    break
                }
            }
            if (realm.isEmpty() || nonce.isEmpty()) return null // nothing answered: the caller tries the other port
        }
        return null
    }

    /** Adds an attribute to a message being built; returns where the next one goes. */
    private fun attr(msg: ByteArray, at: Int, type: Int, value: ByteArray): Int {
        put16(msg, at, type)
        put16(msg, at + 2, value.size)
        value.copyInto(msg, at + 4)
        return at + 4 + (value.size + 3 and 3.inv())
    }

    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v shr 8).toByte()
        b[at + 1] = v.toByte()
    }

    /** The value of an attribute of a received TURN/STUN message, or null. */
    private fun attrValue(rx: ByteArray, len: Int, type: Int): ByteArray? {
        var at = 20
        while (at + 4 <= len) {
            val t = ((rx[at].toInt() and 0xFF) shl 8) or (rx[at + 1].toInt() and 0xFF)
            val l = ((rx[at + 2].toInt() and 0xFF) shl 8) or (rx[at + 3].toInt() and 0xFF)
            if (at + 4 + l > len) return null
            if (t == type) return rx.copyOfRange(at + 4, at + 4 + l)
            at += 4 + (l + 3 and 3.inv())
        }
        return null
    }

    /** An XOR-ADDRESS attribute (a relayed or mapped address) as "ip:port". */
    private fun xorAddr(rx: ByteArray, len: Int, type: Int): String? {
        val v = attrValue(rx, len, type) ?: return null
        if (v.size < 8 || v[1].toInt() != 1) return null
        val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
        val port = (((v[2].toInt() and 0xFF) xor 0x21) shl 8) or ((v[3].toInt() and 0xFF) xor 0x12)
        val ip = ByteArray(4) { (v[4 + it].toInt() xor cookie[it].toInt()).toByte() }
        return "${InetAddress.getByAddress(ip).hostAddress}:$port"
    }

    /** Publishes more addresses for a room this side hosts (the relayed address goes in here). */
    suspend fun publishAddrs(code: String, key: String, addrs: List<String>) {
        runCatching { post("addrs", JSONObject().put("code", code).put("key", key).put("addrs", JSONArray(addrs))) }
    }

    /** Gives the room up (best effort: it also runs out on its own once the host stops asking). */
    suspend fun leave(code: String, key: String) {
        runCatching { post("leave", JSONObject().put("code", code).put("key", key)) }
    }

    /** Sends the lobby's own greeting to [addrs] (and answers the other side's) until one arrives; the address a
     * packet arrives FROM is the one this side really sees the other under - the address the engine must be told, not
     * the one the service said. The host only knocks (T_PUNCH, to open its own router) and answers a greeting with
     * three acknowledgements; the one joining greets until an acknowledgement arrives. The same bytes the game's own
     * lobby uses (net.c: "BT3N", the type, the netcode version at the eighth byte), so a launcher and a running game
     * find each other here exactly as two games do. Blocks for at most [timeoutMs] and answers null then. */
    fun probe(addrs: List<String>, timeoutMs: Long, host: Boolean): String? {
        val hello = greeting(T_HELLO, 12)
        val ack = greeting(T_HELLO_ACK, 12)
        val punch = greeting(T_PUNCH, 8)
        socket.soTimeout = 200
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var nextSend = 0L
        while (System.nanoTime() < deadline) {
            val now = System.nanoTime()
            if (now >= nextSend) {
                for (a in addrs) {
                    val at = a.lastIndexOf(':')
                    if (at < 1) continue
                    val bytes = if (host) punch else hello
                    runCatching {
                        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(a.substring(0, at)), a.substring(at + 1).toInt()))
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
            when (typeOf(rx, packet.length)) {
                T_HELLO -> {
                    if (!host || !versionAt(rx, packet.length)) continue // another release knocking: the game waits too
                    runCatching { repeat(3) { socket.send(DatagramPacket(ack, ack.size, packet.address, packet.port)) } }
                    return "${packet.address.hostAddress}:${packet.port}"
                }
                T_HELLO_ACK -> {
                    if (host) continue
                    if (!versionAt(rx, packet.length)) throw IOException("version") // the host is another release
                    return "${packet.address.hostAddress}:${packet.port}"
                }
            }
        }
        return null
    }

    /** The type of one of the lobby's own packets ("BT3N" then a little-endian 32-bit type), or null for anything
     * else (a stray packet, the relay's own, another protocol on the port). */
    private fun typeOf(rx: ByteArray, len: Int): Int? {
        if (len < 8 || rx[0] != MAGIC_B || rx[1] != MAGIC_T || rx[2] != MAGIC_3 || rx[3] != MAGIC_N) return null
        return if (rx[5].toInt() == 0 && rx[6].toInt() == 0 && rx[7].toInt() == 0) rx[4].toInt() and 0xFF else null
    }

    /** Whether a lobby packet carries this build's netcode version (the eighth byte, a little-endian 32-bit number;
     * net.c NET_VERSION). */
    private fun versionAt(rx: ByteArray, len: Int): Boolean =
        len >= 12 && (rx[8].toInt() and 0xFF) == VERSION && rx[9].toInt() == 0 && rx[10].toInt() == 0 && rx[11].toInt() == 0

    /** One of the lobby's own packets: "BT3N", the type, and - for the greeting and its answer - the version; all
     * little-endian, as the game writes them. */
    private fun greeting(type: Int, size: Int): ByteArray {
        val b = ByteArray(size)
        b[0] = MAGIC_B
        b[1] = MAGIC_T
        b[2] = MAGIC_3
        b[3] = MAGIC_N
        b[4] = type.toByte()
        if (size >= 12) b[8] = VERSION.toByte()
        return b
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
        xorAddr(rx, len, 0x0020)?.let { return it }
        val v = attrValue(rx, len, 0x0001) ?: return null // an old server says the address plain
        if (v.size < 8 || v[1].toInt() != 1) return null
        val port = ((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)
        val ip = v.copyOfRange(4, 8)
        return "${InetAddress.getByAddress(ip).hostAddress}:$port"
    }

    private suspend fun post(verb: String, body: JSONObject): JSONObject =
        JSONObject(Net.postJson("$url/v1/$verb", body.toString()))

    private fun peerOf(peer: JSONObject?): Peer? {
        if (peer == null) return null
        val list = peer.optJSONArray("addrs") ?: JSONArray()
        val all = (0 until list.length()).map { list.optString(it) }
        // "seen=" entries are the relay's own bookkeeping (whom to let through); a "relay=" entry is the other side's
        // relayed address, kept aside: an ordinary address to try when nothing direct answers
        val relay = all.firstOrNull { it.startsWith("relay=") }?.removePrefix("relay=").orEmpty()
        val addrs = all.filter { it.isNotEmpty() && !it.contains("=") }
        return Peer(peer.optString("name", "Player"), addrs, relay)
    }

    /** The other player: the name to show while connecting, and the addresses to try. [relay] is the other side's
     * relayed address, when it has one. */
    data class Peer(val name: String, val addrs: List<String>, val relay: String = "")

    companion object {
        /** The game's netcode version (net.c NET_VERSION): the service refuses a mismatched pair, and so should we. */
        private const val VERSION = 3

        /* The lobby's greeting, its knock and the greeting's answer (net.c: T_HELLO, T_PUNCH, T_HELLO_ACK), and the
           "BT3N" magic in front of them - the same bytes a running game sends and answers. */
        private const val T_HELLO = 1
        private const val T_HELLO_ACK = 2
        private const val T_PUNCH = 9
        private const val MAGIC_B: Byte = 0x42
        private const val MAGIC_T: Byte = 0x54
        private const val MAGIC_3: Byte = 0x33
        private const val MAGIC_N: Byte = 0x4E

        /** The room service: the one compiled into the game, or the one named by match_url.txt in the game folder
         * (net_match.c reads the same file on Android). */
        fun service(root: File): String {
            val named = File(root, "match_url.txt")
            val text = runCatching { if (named.exists()) named.readLines().firstOrNull()?.trim().orEmpty() else "" }.getOrDefault("")
            return if (text.startsWith("https://")) text else "https://dbt3-server.bytarch.com"
        }
    }
}
