package dev.vxcc.b8.e2e

import org.junit.jupiter.api.Tag
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit tests of the harness itself; these run in every `gradlew build`. */
@Tag("unit")
class HarnessTest {
    @Test
    fun varIntRoundTrip() {
        for (v in listOf(0, 1, 127, 128, 255, 25565, Int.MAX_VALUE, -1)) {
            val bytes = PacketWriter().apply { writeVarInt(v) }.bytes.toByteArray()
            assertEquals(v, ByteArrayInputStream(bytes).readVarInt())
        }
        assertEquals(listOf(0xff, 0xff, 0xff, 0xff, 0x0f), PacketWriter().apply { writeVarInt(-1) }.bytes.toByteArray().map { it.toInt() and 0xff })
    }

    @Test
    fun parsesStatusWithComponentMotd() {
        val s = StatusPing.parse(
            """{"version":{"name":"Velocity 4.3.1","protocol":775},"players":{"online":2,"max":500},
               "description":{"text":"","extra":[{"text":"168blocks","color":"gold"},{"text":" — 2 online"}]}}""",
        )
        assertEquals("168blocks — 2 online", s.description)
        assertEquals(2, s.onlinePlayers)
        assertEquals(775, s.protocol)
    }

    @Test
    fun pingsAFakeServer() {
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { c ->
                    val input = DataInputStream(c.getInputStream())
                    repeat(2) { ByteArray(input.readVarInt()).also(input::readFully) } // handshake, request
                    val json = """{"version":{"name":"fake","protocol":1},"players":{"online":0,"max":1},"description":"hi"}"""
                    val payload = ByteArrayOutputStream().also { o ->
                        o.write(0)
                        o.write(PacketWriter().apply { writeString(json) }.bytes.toByteArray())
                    }.toByteArray()
                    c.getOutputStream().write(PacketWriter().apply { writeVarInt(payload.size) }.bytes.toByteArray() + payload)
                }
            }
            val status = StatusPing.ping("127.0.0.1", server.localPort)
            t.join()
            assertEquals("hi", status.description)
            assertEquals("fake", status.versionName)
        }
    }

    @Test
    fun networkFromEnv() {
        val n = Network.fromEnv(mapOf("BLOCKS_E2E_EDGES" to "a:1, b:2"))
        assertEquals(Network.Endpoint("b", 2), n.edge(2))
    }
}
