package dev.vxcc.b8.mod.example

import dev.vxcc.b8.common.ServerHello
import kotlin.test.Test
import kotlin.test.assertEquals

/** Plain unit test in a mod project (no Minecraft classes involved). */
class HelloTest {
    @Test
    fun helloRoundTripsThroughSharedCode() {
        val hello = ServerHello("lobby-1", "0.1.0", 0)
        assertEquals(hello, ServerHello.decode(hello.encode()))
    }
}
