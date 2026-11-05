package dev.vxcc.b8.vodozemac

import dev.vxcc.b8.vodozemac.aot.VodozemacModule
import run.endive.runtime.ExportFunction
import run.endive.runtime.HostFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.Memory
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.ValType
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * vodozemac compiled to wasm32 (`nix/vodozemac-wasm.nix`) and to JVM bytecode by Endive at build time, standing in
 * for Trixnity's JNI library.
 *
 * Trixnity's `trixnity-vodozemac-jvm` declares one `native` method per vodozemac function; the build rewrites each of
 * them (see `build.gradle.kts`) into a call of [call] with the function's name and its arguments, and this object
 * does what libvodozemac's JNI wrappers do, against the wasm instance:
 *
 * * `long` pointers become wasm32 pointers (`i32`); `long` values stay `i64` where the wasm signature says so.
 * * Java arrays (Trixnity's `InteropPointer` on the JVM) are copied into wasm memory for the call and back afterwards.
 *   A `long[]` is the result buffer of an `#[sret]` function (or the destination of `copy_nonoverlapping`): its
 *   elements are `usize` words, 4 bytes in wasm32.
 * * Pointer results are zero-extended.
 *
 * One instance serves the whole JVM; calls are serialized by a lock, like a single-threaded native library would need
 * to be (vodozemac objects are not shared between threads by Trixnity, but the allocator is global).
 */
object VodozemacWasm {
    private val random = SecureRandom()
    private val lock = ReentrantLock()

    private val instance: Instance by lazy {
        val rng = HostFunction(
            "env",
            "extern_rng",
            FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
        ) { inst, args ->
            val bytes = ByteArray(args[1].toInt())
            random.nextBytes(bytes)
            inst.memory().write(args[0].toInt(), bytes)
            longArrayOf(0L)
        }
        Instance.builder(VodozemacModule.load())
            .withImportValues(ImportValues.builder().addFunction(rng).build())
            .withMachineFactory(VodozemacModule::create)
            .build()
    }

    private class Export(val function: ExportFunction, val params: List<ValType>, val returns: List<ValType>)

    private val exports = ConcurrentHashMap<String, Export>()

    private fun export(name: String): Export = exports.computeIfAbsent(name) {
        val type = instance.exportType(name)
        Export(instance.export(name), type.params(), type.returns())
    }

    private val alloc by lazy { instance.export("alloc") }
    private val dealloc by lazy { instance.export("dealloc") }

    /** Called by the rewritten `native` methods; returns the raw result (callers narrow it to their return type). */
    @JvmStatic
    fun call(name: String, args: Array<Any?>): Long = lock.withLock {
        when (name) {
            "copy_nonoverlapping" -> copyNonoverlapping(args[0] as Long, args[1]!!, args[2] as Int)
            // Trixnity frees arrays of pointers with the JVM's pointer size (8); in wasm32 they were allocated as 4-byte words.
            "dealloc" -> {
                val size = args[1] as Int
                val align = args[2] as Int
                if (align == 8) dealloc.apply(ptr(args[0] as Long), size / 2L, 4L)
                else dealloc.apply(ptr(args[0] as Long), size.toLong(), align.toLong())
                0L
            }
            else -> invoke(name, args)
        }
    }

    private fun invoke(name: String, args: Array<Any?>): Long {
        val export = export(name)
        val memory = instance.memory()
        val wasmArgs = LongArray(export.params.size)
        val buffers = ArrayList<Buffer>(2)
        try {
            var w = 0
            for (arg in args) {
                wasmArgs[w] = when (arg) {
                    null -> 0L
                    is Long -> if (export.params[w] == ValType.I64) arg else ptr(arg)
                    is Int -> arg.toLong()
                    is Short -> arg.toLong()
                    is Byte -> arg.toLong()
                    else -> Buffer.of(arg).also { buffers += it }.copyIn(memory)
                }
                w++
            }
            check(w == wasmArgs.size) { "$name: ${args.size} arguments for wasm signature ${export.params}" }
            val result = export.function.apply(*wasmArgs)
            buffers.forEach { it.copyOut(memory) }
            return when (export.returns.firstOrNull()) {
                null -> 0L
                ValType.I32 -> result[0] and 0xffffffffL
                else -> result[0]
            }
        } finally {
            buffers.forEach { it.free() }
        }
    }

    private fun copyNonoverlapping(src: Long, dest: Any, size: Int): Long {
        val memory = instance.memory()
        val p = ptr(src).toInt()
        when (dest) {
            is ByteArray -> memory.readBytes(p, size).copyInto(dest)
            is ShortArray -> for (i in 0 until size / 2) dest[i] = memory.readShort(p + 2 * i)
            is IntArray -> for (i in 0 until size / 4) dest[i] = memory.readInt(p + 4 * i)
            // NativePointerArray: Trixnity passes count * 8, the words are 4 bytes in wasm32
            is LongArray -> for (i in 0 until size / 8) dest[i] = memory.readInt(p + 4 * i).toLong() and 0xffffffffL
            else -> throw IllegalArgumentException("copy_nonoverlapping: unsupported destination ${dest.javaClass}")
        }
        return 0L
    }

    private fun ptr(value: Long): Long {
        require(value ushr 32 == 0L) { "not a wasm32 pointer: $value" }
        return value
    }

    /** A Java array copied into wasm memory for the duration of one call. */
    private class Buffer(val array: Any, val bytes: Int, val align: Int) {
        var address = 0

        fun copyIn(memory: Memory): Long {
            if (bytes == 0) return 0L
            address = alloc.apply(bytes.toLong(), align.toLong())[0].toInt()
            when (array) {
                is ByteArray -> memory.write(address, array)
                is ShortArray -> array.forEachIndexed { i, v -> memory.writeShort(address + 2 * i, v) }
                is IntArray -> array.forEachIndexed { i, v -> memory.writeI32(address + 4 * i, v) }
                is LongArray -> array.forEachIndexed { i, v -> memory.writeI32(address + 4 * i, v.toInt()) }
            }
            return address.toLong() and 0xffffffffL
        }

        fun copyOut(memory: Memory) {
            if (address == 0) return
            when (array) {
                is ByteArray -> memory.readBytes(address, bytes).copyInto(array)
                is ShortArray -> for (i in array.indices) array[i] = memory.readShort(address + 2 * i)
                is IntArray -> for (i in array.indices) array[i] = memory.readInt(address + 4 * i)
                is LongArray -> for (i in array.indices) array[i] = memory.readInt(address + 4 * i).toLong() and 0xffffffffL
            }
        }

        fun free() {
            if (address != 0) dealloc.apply(address.toLong() and 0xffffffffL, bytes.toLong(), align.toLong())
            address = 0
        }

        companion object {
            fun of(array: Any): Buffer = when (array) {
                is ByteArray -> Buffer(array, array.size, 1)
                is ShortArray -> Buffer(array, array.size * 2, 2)
                is IntArray -> Buffer(array, array.size * 4, 4)
                is LongArray -> Buffer(array, array.size * 4, 4)
                else -> throw IllegalArgumentException("unsupported argument ${array.javaClass}")
            }
        }
    }
}
