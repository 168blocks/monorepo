package dev.vxcc.b8.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiTest {
    @Test
    fun counterJson() {
        assertEquals("""{"value":3}""", ApiJson.encodeToString(Counter.serializer(), Counter(3)))
        assertEquals(Counter(5), ApiJson.decodeFromString(Counter.serializer(), """{"value":5,"extra":1}"""))
    }
}
