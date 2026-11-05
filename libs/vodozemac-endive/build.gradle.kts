import blocks.RewriteNativeMethods

plugins {
    id("blocks.kotlin-jvm")
    `java-library`
}

description = "vodozemac (Matrix end-to-end encryption) as wasm on the JVM through Endive, for Trixnity without a native library"

// Endive's build-time (AOT) compiler turns vodozemac.wasm into JVM classes when this module is built.
val endiveCompiler = configurations.create("endiveCompiler")

dependencies {
    api(libs.endive.runtime)
    endiveCompiler(libs.endive.build.time.compiler)

}

// vodozemac.wasm is built by Nix (nix/vodozemac-wasm.nix); the dev shell points BLOCKS_VODOZEMAC_WASM at it.
val vodozemacWasm: Provider<String> = providers.environmentVariable("BLOCKS_VODOZEMAC_WASM").orElse("")

val aotName = "dev.vxcc.b8.vodozemac.aot.VodozemacModule"
val aotDir = layout.buildDirectory.dir("generated/endive")
val aotClasses = aotDir.map { it.dir("classes") }
val aotSources = aotDir.map { it.dir("sources") }
val aotResources = aotDir.map { it.dir("resources") }

val compileVodozemac = tasks.register<JavaExec>("compileVodozemac") {
    description = "Compiles vodozemac.wasm to JVM classes with Endive's build-time compiler"
    classpath = endiveCompiler
    mainClass.set("run.endive.build.time.compiler.GeneratorMain")
    inputs.files(vodozemacWasm.map { if (it.isEmpty()) emptyList() else listOf(File(it)) })
        .withPropertyName("vodozemacWasm")
        .withPathSensitivity(PathSensitivity.NONE)
    outputs.dir(aotDir)
    outputs.cacheIf { true }
    maxHeapSize = "2g"
    val wasm = vodozemacWasm
    val classes = aotClasses
    val sources = aotSources
    val resources = aotResources
    val root = aotDir
    val name = aotName
    doFirst {
        if (wasm.get().isEmpty()) {
            throw GradleException("BLOCKS_VODOZEMAC_WASM is not set: run Gradle in the Nix dev shell (./dev ./gradlew ...)")
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
                // fail the build if any function would fall back to Endive's (slow) interpreter
                "FAIL",
            )
        },
    )
}

sourceSets.main {
    java.srcDir(files(aotSources).builtBy(compileVodozemac))
    resources.srcDir(files(aotResources).builtBy(compileVodozemac))
    output.dir(mapOf("builtBy" to compileVodozemac), aotClasses)
}

dependencies {
    compileOnly(files(aotClasses).builtBy(compileVodozemac))
}

// Trixnity's vodozemac bindings (trixnity-vodozemac-jvm), rewritten: every `native` method calls
// VodozemacWasm.call(name, args) instead, and JvmLoader.load (which loads libvodozemac.so) does nothing.
// The rewritten classes are part of this module's output; consumers substitute this project for
// trixnity-vodozemac-jvm (see backend/build.gradle.kts).
val trixnityVodozemacJvm = configurations.create("trixnityVodozemacJvm") {
    isTransitive = false
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    trixnityVodozemacJvm(libs.trixnity.vodozemac.jvm) // depends on kotlin-stdlib only (and the native library)
}

val rewriteTrixnityVodozemac = tasks.register<RewriteNativeMethods>("rewriteTrixnityVodozemac") {
    description = "Rewrites trixnity-vodozemac-jvm's native methods into calls of the Endive-hosted vodozemac"
    jars.from(trixnityVodozemacJvm)
    bridgeClass.set("dev/vxcc/b8/vodozemac/VodozemacWasm")
    noOpMethods.add("de/connect2x/trixnity/vodozemac/JvmLoader.load")
    outputDirectory.set(layout.buildDirectory.dir("generated/trixnity-vodozemac"))
}

sourceSets.main {
    output.dir(mapOf("builtBy" to rewriteTrixnityVodozemac), rewriteTrixnityVodozemac.flatMap { it.outputDirectory })
}

tasks.register<JavaExec>("bench") {
    description = "Prints rough timings of vodozemac on Endive (VodozemacBench)"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.vxcc.b8.vodozemac.VodozemacBenchKt")
    jvmArgs("-XX:-DontCompileHugeMethods")
}
