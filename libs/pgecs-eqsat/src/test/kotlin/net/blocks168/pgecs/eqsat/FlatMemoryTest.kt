package net.blocks168.pgecs.eqsat

import run.endive.runtime.Memory
import run.endive.runtime.WasmRuntimeException
import run.endive.wasm.types.MemoryLimits
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlatMemoryTest {
    @Test
    fun `reads and writes little-endian values`() {
        val m = FlatMemory(MemoryLimits(1, 4))
        m.writeI32(0, 0x01020304)
        assertContentEquals(byteArrayOf(4, 3, 2, 1), m.readBytes(0, 4))
        m.writeLong(8, -2L)
        assertEquals(-2L, m.readLong(8))
        m.writeShort(16, (-1).toShort())
        assertEquals(0xFFFFL, m.readU16(16))
        m.writeF64(24, 1.5)
        assertEquals(1.5, m.readDouble(24))
        m.writeF32(32, 2.5f)
        assertEquals(2.5f, m.readFloat(32))
        m.write(40, "héllo".toByteArray())
        assertEquals("héllo", m.readString(40, 6))
        m.copy(41, 40, 6) // overlapping
        assertEquals("hhéllo", m.readString(40, 7))
    }

    @Test
    fun `grows up to the maximum and keeps its contents`() {
        val m = FlatMemory(MemoryLimits(1, 3))
        m.writeI32(Memory.PAGE_SIZE - 4, 42)
        assertEquals(1, m.grow(1))
        assertEquals(2, m.pages())
        assertEquals(42, m.readInt(Memory.PAGE_SIZE - 4))
        m.writeI32(2 * Memory.PAGE_SIZE - 4, 7)
        assertEquals(-1, m.grow(2))
        assertEquals(2, m.pages())
    }

    @Test
    fun `out-of-bounds accesses trap`() {
        val m = FlatMemory(MemoryLimits(1, 1))
        assertFailsWith<WasmRuntimeException> { m.readInt(Memory.PAGE_SIZE - 3) }
        assertFailsWith<WasmRuntimeException> { m.writeLong(-1, 0) }
        assertFailsWith<WasmRuntimeException> { m.read(Memory.PAGE_SIZE) }
        assertFailsWith<WasmRuntimeException> { m.readBytes(Memory.PAGE_SIZE - 1, 2) }
        assertFailsWith<WasmRuntimeException> { m.fill(0, Memory.PAGE_SIZE - 1, Memory.PAGE_SIZE + 1) }
    }
}
