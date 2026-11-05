package net.blocks168.pgecs.optimizer

import run.endive.runtime.ConstantEvaluators
import run.endive.runtime.Instance
import run.endive.runtime.Memory
import run.endive.runtime.WasmRuntimeException
import run.endive.wasm.types.ActiveDataSegment
import run.endive.wasm.types.DataSegment
import run.endive.wasm.types.MemoryLimits
import run.endive.wasm.types.PassiveDataSegment
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * Non-shared Wasm linear memory backed by one contiguous `byte[]`.
 *
 * Endive's `ByteArrayMemory` splits memory into 64 KiB pages, which costs an extra load and branch on
 * every access; with this memory egglog runs ~30% faster (36 to 25 ms per query in the spike).
 * [grow] reallocates. Out-of-bounds accesses become Wasm traps ([WasmRuntimeException]).
 */
internal class FlatMemory(private val limits: MemoryLimits) : Memory {
    private var dataSegments: Array<DataSegment>? = null
    private var buf: ByteArray
    private var nPages: Int

    init {
        require(!limits.shared()) { "FlatMemory does not support shared memory" }
        nPages = limits.initialPages()
        buf = ByteArray(Memory.PAGE_SIZE * nPages)
    }

    private fun check(addr: Int, size: Int) {
        if (addr < 0 || size < 0 || addr.toLong() + size > buf.size) throw oob(addr, size)
    }

    override fun pages(): Int = nPages
    override fun initialPages(): Int = limits.initialPages()
    override fun maximumPages(): Int = minOf(limits.maximumPages(), Memory.RUNTIME_MAX_PAGES)
    override fun shared(): Boolean = false

    override fun grow(size: Int): Int {
        val prev = nPages
        val next = prev + size
        if (size < 0 || next > maximumPages() || next < prev) return -1
        if (size > 0) {
            buf = buf.copyOf(Memory.PAGE_SIZE * next)
            nPages = next
        }
        return prev
    }

    @Suppress("OVERRIDE_DEPRECATION") // deprecated in Memory, still abstract
    override fun lock(address: Int): Any = this
    @Suppress("OVERRIDE_DEPRECATION") // deprecated in Memory, still abstract
    override fun waitOn(address: Int, expected: Int, timeout: Long): Int = throw WasmRuntimeException("wait on non-shared memory")
    @Suppress("OVERRIDE_DEPRECATION") // deprecated in Memory, still abstract
    override fun waitOn(address: Int, expected: Long, timeout: Long): Int = throw WasmRuntimeException("wait on non-shared memory")
    @Suppress("OVERRIDE_DEPRECATION") // deprecated in Memory, still abstract
    override fun notify(address: Int, maxThreads: Int): Int = 0

    override fun initialize(instance: Instance, dataSegments: Array<DataSegment>?) = initialize(instance, dataSegments, 0)

    override fun initialize(instance: Instance, dataSegments: Array<DataSegment>?, memoryIndex: Int) {
        this.dataSegments = dataSegments
        for (seg in dataSegments ?: return) {
            if (seg is ActiveDataSegment && seg.index() == memoryIndex.toLong()) {
                val offset = ConstantEvaluators.computeConstantValue(instance, seg.offsetInstructions())[0].toInt()
                write(offset, seg.bytes(), 0, seg.bytes().size)
            }
        }
    }

    override fun initPassiveSegment(segmentId: Int, dest: Int, offset: Int, size: Int) =
        write(dest, dataSegments!![segmentId].bytes(), offset, size)

    override fun drop(segment: Int) {
        dataSegments!![segment] = PassiveDataSegment.EMPTY
    }

    override fun write(addr: Int, data: ByteArray, offset: Int, size: Int) {
        check(addr, size)
        System.arraycopy(data, offset, buf, addr, size)
    }

    override fun read(addr: Int): Byte =
        try { buf[addr] } catch (_: IndexOutOfBoundsException) { throw oob(addr, 1) }

    override fun readBytes(addr: Int, len: Int): ByteArray {
        check(addr, len)
        return buf.copyOfRange(addr, addr + len)
    }

    override fun readString(addr: Int, len: Int): String {
        check(addr, len)
        return String(buf, addr, len, Charsets.UTF_8)
    }

    // The casts on VarHandle.get pick the exact (non-boxing) call site type for the signature-polymorphic calls.
    override fun writeI32(addr: Int, data: Int) =
        try { INT.set(buf, addr, data) } catch (_: IndexOutOfBoundsException) { throw oob(addr, 4) }

    override fun readInt(addr: Int): Int =
        try { INT.get(buf, addr) as Int } catch (_: IndexOutOfBoundsException) { throw oob(addr, 4) }

    override fun writeLong(addr: Int, data: Long) =
        try { LONG.set(buf, addr, data) } catch (_: IndexOutOfBoundsException) { throw oob(addr, 8) }

    override fun readLong(addr: Int): Long =
        try { LONG.get(buf, addr) as Long } catch (_: IndexOutOfBoundsException) { throw oob(addr, 8) }

    override fun writeShort(addr: Int, data: Short) =
        try { SHORT.set(buf, addr, data) } catch (_: IndexOutOfBoundsException) { throw oob(addr, 2) }

    override fun readShort(addr: Int): Short =
        try { SHORT.get(buf, addr) as Short } catch (_: IndexOutOfBoundsException) { throw oob(addr, 2) }

    override fun readU16(addr: Int): Long = readShort(addr).toLong() and 0xFFFF

    override fun writeByte(addr: Int, data: Byte) =
        try { buf[addr] = data } catch (_: IndexOutOfBoundsException) { throw oob(addr, 1) }

    override fun writeF32(addr: Int, data: Float) =
        try { FLOAT.set(buf, addr, data) } catch (_: IndexOutOfBoundsException) { throw oob(addr, 4) }

    override fun readF32(addr: Int): Long = readInt(addr).toLong()

    override fun readFloat(addr: Int): Float =
        try { FLOAT.get(buf, addr) as Float } catch (_: IndexOutOfBoundsException) { throw oob(addr, 4) }

    override fun writeF64(addr: Int, data: Double) =
        try { DOUBLE.set(buf, addr, data) } catch (_: IndexOutOfBoundsException) { throw oob(addr, 8) }

    override fun readDouble(addr: Int): Double =
        try { DOUBLE.get(buf, addr) as Double } catch (_: IndexOutOfBoundsException) { throw oob(addr, 8) }

    override fun readF64(addr: Int): Long = readLong(addr)

    override fun zero() = buf.fill(0)

    override fun fill(value: Byte, fromIndex: Int, toIndex: Int) {
        check(fromIndex, toIndex - fromIndex)
        buf.fill(value, fromIndex, toIndex)
    }

    override fun copy(dest: Int, src: Int, size: Int) {
        check(dest, size)
        check(src, size)
        System.arraycopy(buf, src, buf, dest, size) // handles overlap
    }

    private companion object {
        @JvmField val SHORT: VarHandle = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.LITTLE_ENDIAN)
        @JvmField val INT: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
        @JvmField val LONG: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)
        @JvmField val FLOAT: VarHandle = MethodHandles.byteArrayViewVarHandle(FloatArray::class.java, ByteOrder.LITTLE_ENDIAN)
        @JvmField val DOUBLE: VarHandle = MethodHandles.byteArrayViewVarHandle(DoubleArray::class.java, ByteOrder.LITTLE_ENDIAN)

        fun oob(addr: Int, size: Int) = WasmRuntimeException(
            "out of bounds memory access: attempted to access address: ${Integer.toUnsignedString(addr)} size: $size",
        )
    }
}
