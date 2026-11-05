plugins {
    id("blocks.kotlin-jvm")
    `java-library`
}

description = "pgecs optimizer: equality saturation with egglog (wasm32-wasip1) on the JVM through Endive"

// Endive's build-time (AOT) compiler turns egglog.wasm into JVM classes when this module is built.
val endiveCompiler = configurations.create("endiveCompiler")

dependencies {
    api(libs.endive.runtime)
    implementation(libs.endive.wasi)
    endiveCompiler(libs.endive.build.time.compiler)
}

// egglog.wasm is built by Nix (nix/egglog-wasm.nix); the dev shell points BLOCKS_EGGLOG_WASM at it.
val egglogWasm: Provider<String> = providers.environmentVariable("BLOCKS_EGGLOG_WASM").orElse("")

val aotName = "net.blocks168.pgecs.optimizer.aot.EgglogModule"
val aotDir = layout.buildDirectory.dir("generated/endive")
val aotClasses = aotDir.map { it.dir("classes") }
val aotSources = aotDir.map { it.dir("sources") }
val aotResources = aotDir.map { it.dir("resources") }

val compileEgglog = tasks.register<JavaExec>("compileEgglog") {
    description = "Compiles egglog.wasm to JVM classes with Endive's build-time compiler"
    classpath = endiveCompiler
    mainClass.set("run.endive.build.time.compiler.GeneratorMain")
    inputs.files(egglogWasm.map { if (it.isEmpty()) emptyList() else listOf(File(it)) })
        .withPropertyName("egglogWasm")
        .withPathSensitivity(PathSensitivity.NONE)
    outputs.dir(aotDir)
    outputs.cacheIf { true }
    maxHeapSize = "2g"
    val wasm = egglogWasm
    val classes = aotClasses
    val sources = aotSources
    val resources = aotResources
    val root = aotDir
    val name = aotName
    doFirst {
        if (wasm.get().isEmpty()) {
            throw GradleException("BLOCKS_EGGLOG_WASM is not set: run Gradle in the Nix dev shell (./dev ./gradlew ...)")
        }
        root.get().asFile.deleteRecursively()
    }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                wasm.get(),
                name,
                classes.get().asFile.absolutePath,
                sources.get().asFile.absolutePath,
                resources.get().asFile.absolutePath,
                // fail the build if any egglog function would fall back to Endive's (slow) interpreter
                "FAIL",
            )
        },
    )
}

sourceSets.main {
    // the generated EgglogModule.java (loader, machine factory) is compiled with this module; the bulk
    // of the generated code is already class files, packaged and put on the classpath as is
    java.srcDir(files(aotSources).builtBy(compileEgglog))
    resources.srcDir(files(aotResources).builtBy(compileEgglog))
    output.dir(mapOf("builtBy" to compileEgglog), aotClasses)
}

dependencies {
    compileOnly(files(aotClasses).builtBy(compileEgglog))
}

tasks.withType<Test>().configureEach {
    // egglog's hot functions become JVM methods above HotSpot's 8000-bytecode limit, which it would
    // otherwise never JIT-compile (see README.md). Every JVM that runs the optimizer needs this flag.
    jvmArgs("-XX:-DontCompileHugeMethods")
    maxHeapSize = "1g"
}

// Timing of the join-reordering workload, not part of the build: ./dev ./gradlew :libs:pgecs-optimizer:benchmark
tasks.register<JavaExec>("benchmark") {
    description = "Times egglog on the join-reordering workload (warm-up curve and steady state)"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("net.blocks168.pgecs.optimizer.BenchmarkKt")
    jvmArgs("-XX:-DontCompileHugeMethods")
    maxHeapSize = "1g"
    args(providers.gradleProperty("runs").orElse("1200").get())
}
