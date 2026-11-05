package dev.vxcc.b8.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ServerHelloTest {
    @Test
    fun roundTrip() {
        val hello = ServerHello("lobby-1", "0.1.0", 42)
        assertEquals(hello, ServerHello.decode(hello.encode()))
    }

    @Test
    fun wireFormatIsCborArray() {
        // 0x83 = array(3), 0x61 'a' = text(1), 0x61 'b', 0x07
        assertEquals(listOf(0x83, 0x61, 'a'.code, 0x61, 'b'.code, 0x07), ServerHello("a", "b", 7).encode().map { it.toInt() and 0xff })
    }

    @Test
    fun rejectsWrongShape() {
        assertFails { ServerHello.decode(byteArrayOf(0x82.toByte(), 0x61, 'a'.code.toByte(), 0x07)) }
    }
}
