package net.blocks168.frontend

import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {
    @Test
    fun label() {
        assertEquals("Counter: loading…", counterLabel(null))
        assertEquals("Counter: 3", counterLabel(3))
    }
}
