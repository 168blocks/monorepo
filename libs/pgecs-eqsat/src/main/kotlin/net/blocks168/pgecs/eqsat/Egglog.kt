package net.blocks168.pgecs.eqsat

import net.blocks168.pgecs.eqsat.aot.EgglogModule
import run.endive.runtime.ExportFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.wasi.WasiOptions
import run.endive.wasi.WasiPreview1
import java.io.ByteArrayOutputStream

/** egglog rejected a program (parse, type or runtime error); [message] is egglog's own message. */
class EgglogException(message: String) : RuntimeException(message)

/**
 * One egglog instance: egglog compiled to wasm32-wasip1, compiled to JVM classes at build time by
 * Endive, with a contiguous [FlatMemory].
 *
 * Programs go in as egglog source text, and their outputs (for example the terms of `(extract ...)`)
 * come back as text. An instance runs one program at a time and is **not thread-safe**: use one per
 * thread, or an [EgglogPool].
 *
 * The first call in a JVM takes ~0.5 s and it takes a few hundred calls before HotSpot settles; run the
 * JVM with `-XX:-DontCompileHugeMethods` (see README.md).
 */
class Egglog private constructor(private val instance: Instance, private val stderr: ByteArrayOutputStream) {
    private val alloc = instance.export("egglog_alloc")
    private val run = instance.export("egglog_run")
    private val sessionRun = instance.export("egglog_session_run")
    private val sessionReset = instance.export("egglog_session_reset")
    private val outPtr = instance.export("egglog_out_ptr")
    private val outLen = instance.export("egglog_out_len")

    /** Runs a self-contained program on a fresh e-graph and returns its outputs. */
    fun run(program: String): String = call(run, program)

    /**
     * Runs a program on this instance's persistent session e-graph, which outlives the call. Load
     * datatypes and rules once, then per query `(push) (let q ...) (run-schedule ...) (extract q) (pop)`.
     * After an [EgglogException] the session may be left in any state (for example still pushed);
     * [resetSession] it.
     */
    fun runInSession(program: String): String = call(sessionRun, program)

    /** Drops the session e-graph; the next [runInSession] starts from an empty one. */
    fun resetSession() {
        sessionReset.apply()
    }

    /** Size of egglog's Wasm linear memory in bytes. */
    val memoryBytes: Long get() = instance.memory().pages().toLong() * 65536

    /** What egglog wrote to stderr so far (normally nothing). */
    val guestStderr: String get() = stderr.toString(Charsets.UTF_8)

    private fun call(fn: ExportFunction, program: String): String {
        val bytes = program.toByteArray(Charsets.UTF_8)
        val ptr = alloc.apply(bytes.size.toLong())[0].toInt()
        instance.memory().write(ptr, bytes)
        val rc = fn.apply(ptr.toLong(), bytes.size.toLong())[0] // frees the input
        val out = instance.memory().readString(outPtr.apply()[0].toInt(), outLen.apply()[0].toInt())
        if (rc != 0L) throw EgglogException(out)
        return out
    }

    companion object {
        /** Creates a new instance (~0.3 s for the first one in a JVM, then a few ms). */
        fun create(): Egglog {
            val stderr = ByteArrayOutputStream()
            val wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder().withStderr(stderr).build())
                .build()
            val instance = Instance.builder(EgglogModule.load())
                .withImportValues(ImportValues.builder().addFunction(*wasi.toHostFunctions()).build())
                .withMachineFactory(EgglogModule::create)
                .withMemoryFactory(::FlatMemory)
                .build()
            return Egglog(instance, stderr)
        }
    }
}
