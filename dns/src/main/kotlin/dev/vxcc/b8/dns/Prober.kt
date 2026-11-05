package dev.vxcc.b8.dns

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Health of the edges that have a `probe` address: every [interval] it does a Minecraft status ping (what
 * a client's server list does) to each and records the result ([EdgeStore.recordProbes]). An edge that
 * answers gets a fresh heartbeat; one that does not goes stale and drops out of DNS.
 */
class Prober(
    private val store: EdgeStore,
    private val interval: Duration,
    private val timeout: Duration = Duration.ofSeconds(2),
    private val ping: (HostPort, Duration) -> Unit = StatusPing::ping,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Prober::class.java)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "dns-prober").apply { isDaemon = true } }
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val lastError = HashMap<String, String?>()

    fun start() {
        scheduler.scheduleWithFixedDelay({
            try {
                round()
            } catch (e: Exception) {
                log.warn("probe round failed: {}", e.toString())
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** Probes every edge once, in parallel; returns name to error (null: healthy). */
    fun round(): Map<String, String?> {
        val targets = store.probes()
        val futures = targets.mapValues { (_, target) ->
            workers.submit<String?> {
                try {
                    ping(target, timeout)
                    null
                } catch (e: Exception) {
                    e.toString()
                }
            }
        }
        val results = futures.mapValues { (_, f) -> f.get() }
        store.recordProbes(results)
        for ((name, error) in results) {
            if (lastError.containsKey(name) && lastError[name] == error) continue
            if (error == null) log.info("edge {} ({}) is up", name, targets[name]) else log.warn("edge {} ({}) is down: {}", name, targets[name], error)
            lastError[name] = error
        }
        return results
    }

    override fun close() {
        scheduler.shutdownNow()
        workers.shutdownNow()
    }
}

/** The Minecraft server list ping: handshake (next state status), status request, a JSON response. */
object StatusPing {
    fun ping(target: HostPort, timeout: Duration) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(target.host, target.port), timeout.toMillis().toInt())
            socket.soTimeout = timeout.toMillis().toInt()
            val out = socket.getOutputStream()
            out.packet {
                varInt(0x00)
                varInt(-1) // protocol version: any, this is only a status request
                string(target.host)
                write(target.port shr 8); write(target.port and 0xff)
                varInt(1) // next state: status
            }
            out.packet { varInt(0x00) } // status request
            out.flush()
            val input = DataInputStream(socket.getInputStream())
            val length = input.readVarInt()
            if (length !in 1..(1 shl 21)) throw IOException("bad status response length $length")
            val body = DataInputStream(ByteArray(length).also(input::readFully).inputStream())
            val id = body.readVarInt()
            if (id != 0x00) throw IOException("expected a status response, got packet 0x${id.toString(16)}")
            val json = ByteArray(body.readVarInt()).also(body::readFully).decodeToString()
            if (!json.trimStart().startsWith("{")) throw IOException("status response is not JSON")
        }
    }

    private fun OutputStream.packet(build: ByteArrayOutputStream.() -> Unit) {
        val body = ByteArrayOutputStream().apply(build).toByteArray()
        write(ByteArrayOutputStream().apply { varInt(body.size) }.toByteArray())
        write(body)
    }

    private fun ByteArrayOutputStream.varInt(value: Int) {
        var v = value
        while (true) {
            if (v and 0x7f.inv() == 0) { write(v); return }
            write((v and 0x7f) or 0x80)
            v = v ushr 7
        }
    }

    private fun ByteArrayOutputStream.string(s: String) {
        val bytes = s.encodeToByteArray()
        varInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readVarInt(): Int {
        var result = 0
        for (i in 0 until 5) {
            val b = readUnsignedByte()
            result = result or ((b and 0x7f) shl (7 * i))
            if (b and 0x80 == 0) return result
        }
        throw IOException("VarInt too long")
    }
}
