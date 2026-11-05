package net.blocks168.mod.e2e.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

/**
 * Development only: lets end-to-end tests (e2e/, `GameClient`) drive this client over a local RPC.
 *
 * Listens on 127.0.0.1, port `BLOCKS_E2E_RPC_PORT` (default 0: any free port). The bound port is written
 * to the file `BLOCKS_E2E_RPC_PORT_FILE` (default `e2e-rpc.port` in the run directory) once the
 * socket accepts connections, so launchers never need fixed ports. Protocol: [RpcHandler].
 */
object E2eClient : ClientModInitializer {
    private val log = LoggerFactory.getLogger("blocks168-e2e-client")

    /** How often the client entered a world (play phase) in this session */
    val joins = java.util.concurrent.atomic.AtomicInteger()

    override fun onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register { msg, overlay -> if (!overlay) ChatLog.add(msg.string) }
        ClientReceiveMessageEvents.CHAT.register { msg, _, _, _, _ -> ChatLog.add(msg.string) }
        // counts entries into the play phase: the first join and every proxy server switch
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> joins.incrementAndGet() }

        val port = System.getenv("BLOCKS_E2E_RPC_PORT")?.toInt() ?: 0
        val portFile = Path.of(System.getenv("BLOCKS_E2E_RPC_PORT_FILE") ?: "e2e-rpc.port").toAbsolutePath()
        val server = ServerSocket(port, 8, InetAddress.getLoopbackAddress())
        thread(name = "e2e-rpc-accept", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "e2e-rpc-${socket.port}", isDaemon = true) { RpcHandler.serve(socket) }
            }
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register { server.close() }
        // write-then-rename, so a reader never sees a partial file
        val tmp = portFile.resolveSibling(portFile.fileName.toString() + ".tmp")
        Files.writeString(tmp, server.localPort.toString())
        Files.move(tmp, portFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info("e2e RPC listening on 127.0.0.1:{} (port file {})", server.localPort, portFile)
    }
}

/** Chat and system messages the client received, numbered from 0 for the whole session. */
object ChatLog {
    private const val MAX = 5000
    private val lines = ArrayDeque<String>()
    private var base = 0

    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > MAX) {
            lines.removeFirst()
            base++
        }
    }

    /** Lines with index >= [since], and the index of the next line. */
    @Synchronized
    fun since(since: Int): Pair<List<IndexedValue<String>>, Int> {
        val out = lines.withIndex().map { IndexedValue(it.index + base, it.value) }.filter { it.index >= since }
        return out to base + lines.size
    }
}
