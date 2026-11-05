package dev.vxcc.b8.mod.creativehost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedResourcesTest {
    @Test
    fun `untracked resources may always close`() {
        assertTrue(SharedResources.release(Any()))
    }

    @Test
    fun `only the last of the owner and its sharers closes`() {
        val r = Any()
        SharedResources.acquire(r) // owner + first sharer
        SharedResources.acquire(r) // second sharer
        assertEquals(3, SharedResources.users(r))
        assertFalse(SharedResources.release(r))
        assertFalse(SharedResources.release(r))
        assertTrue(SharedResources.release(r))
        assertEquals(1, SharedResources.users(r))
        assertTrue(SharedResources.release(r))
    }
}
