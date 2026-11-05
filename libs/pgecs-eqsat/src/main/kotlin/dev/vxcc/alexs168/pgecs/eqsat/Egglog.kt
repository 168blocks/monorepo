package dev.vxcc.alexs168.pgecs.eqsat

import dev.vxcc.alexs168.pgecs.eqsat.aot.EgglogModule
import dev.vxcc.alexs168.pgecs.eqsat.aot.EgglogNativeModule
import run.endive.runtime.ExportFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.wasi.WasiOptions
import run.endive.wasi.WasiPreview1
import java.io.ByteArrayOutputStream

/** egglog rejected a program (parse, type or runtime error); [message] is egglog's own message. */
class EgglogException(message: String) : RuntimeException(message)

/** How an [Egglog] instance runs egglog's Wasm. */
enum class EgglogBackend {
    /**
     * Native code generated at build time by Endive's experimental Redline backend (Cranelift), from
     * egglog built with `opt-level = 3`, loaded through Panama. No JVM warm-up; linear memory off-heap.
     * Available on Linux, macOS and Windows on x86_64 and aarch64, for the targets the build compiled
     * (`nix/egglog-redline.nix`).
     */
    NATIVE,

    /**
     * Endive's bytecode AOT from egglog built with `opt-level = "s"`, with a contiguous [FlatMemory].
     * Runs everywhere; needs `-XX:-DontCompileHugeMethods` and a few hundred calls to warm up.
     */
    BYTECODE,
}

/**
 * One egglog instance: egglog compiled to wasm32-wasip1 and compiled at build time by Endive, either to
 * native code ([EgglogBackend.NATIVE], preferred) or to JVM classes ([EgglogBackend.BYTECODE], the
 * fallback).
 *
 * Programs go in as egglog source text, and their outputs (for example the terms of `(extract ...)`)
 * come back as text. An instance runs one program at a time and is **not thread-safe**: use one per
 * thread, or an [EgglogPool].
 *
 * Run the JVM with `--enable-native-access=ALL-UNNAMED` (Redline's Panama runner) and
 * `-XX:-DontCompileHugeMethods` (the bytecode fallback; see README.md).
 */
class Egglog private constructor(
    private val instance: Instance,
    private val stderr: ByteArrayOutputStream,
    /** The backend this instance runs on. */
    val backend: EgglogBackend,
) {
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
        /** System property that forces a backend: `native`, `bytecode` or `auto` (the default). */
        const val BACKEND_PROPERTY = "pgecs.eqsat.backend"

        private val log = System.getLogger(Egglog::class.java.name)

        /**
         * Why native code cannot be used in this JVM, or null if it can. Checked once: no native code
         * for this platform, or the Panama runner failed to load (for example native access denied).
         */
        val nativeUnavailableReason: String? by lazy {
            try {
                if (EgglogNativeModule.nativeProvider().isPresent) {
                    null
                } else {
                    "no Redline native code for ${System.getProperty("os.name")}/${System.getProperty("os.arch")}"
                }
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                "Redline runner failed to load: $e"
            }
        }

        /** The backend [create] uses without an argument: [BACKEND_PROPERTY], else native where available. */
        val defaultBackend: EgglogBackend by lazy {
            when (val forced = System.getProperty(BACKEND_PROPERTY)?.lowercase()) {
                null, "", "auto" -> {
                    val reason = nativeUnavailableReason
                    if (reason == null) {
                        EgglogBackend.NATIVE
                    } else {
                        log.log(System.Logger.Level.WARNING, "egglog falls back to Endive bytecode: {0}", reason)
                        EgglogBackend.BYTECODE
                    }
                }
                "native" -> EgglogBackend.NATIVE
                "bytecode" -> EgglogBackend.BYTECODE
                else -> throw IllegalArgumentException("$BACKEND_PROPERTY must be native, bytecode or auto, not $forced")
            }
        }

        /**
         * Creates a new instance. Native: ~1 s for the first one in a JVM (loading the native code), then
         * a few ms. Bytecode: ~0.3 s for the first, then a few ms.
         *
         * @throws IllegalStateException for [EgglogBackend.NATIVE] if native code is not available
         */
        fun create(backend: EgglogBackend = defaultBackend): Egglog {
            val stderr = ByteArrayOutputStream()
            val wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder().withStderr(stderr).build())
                .build()
            val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
            val instance = when (backend) {
                EgglogBackend.NATIVE -> {
                    nativeUnavailableReason?.let { throw IllegalStateException("egglog native backend unavailable: $it") }
                    // builder() picks the native machine; its linear memory is Redline's own (off-heap)
                    EgglogNativeModule.builder().withImportValues(imports).build()
                }
                EgglogBackend.BYTECODE -> Instance.builder(EgglogModule.load())
                    .withImportValues(imports)
                    .withMachineFactory(EgglogModule::create)
                    .withMemoryFactory(::FlatMemory)
                    .build()
            }
            return Egglog(instance, stderr, backend)
        }
    }
}
