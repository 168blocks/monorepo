package dev.vxcc.b8.mod.creativehost

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerPropertiesTest {
    @Test
    fun `replaces entries in place and appends missing ones`() {
        val base = "# comment\nserver-port=25565\nmotd=host\nlevel-name=survival\n"
        val merged = ServerProperties.merge(base, mapOf("server-port" to "25601", "level-name" to "world", "gamemode" to "creative"))
        assertEquals("# comment\nserver-port=25601\nmotd=host\nlevel-name=world\ngamemode=creative\n", merged)
    }

    @Test
    fun `leaves commented keys alone`() {
        assertEquals("#server-port=1\nserver-port=2\n", ServerProperties.merge("#server-port=1\n", mapOf("server-port" to "2")))
    }

    @Test
    fun `empty base`() {
        assertEquals("a=1\n", ServerProperties.merge("", mapOf("a" to "1")))
    }
}
