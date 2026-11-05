package net.blocks168.velocity.example

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class MotdTest {
    @Test
    fun motdText() {
        assertEquals("168blocks — 3 online", PlainTextComponentSerializer.plainText().serialize(motd(3)))
    }
}
