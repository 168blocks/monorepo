import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec

// Kotlin Multiplatform module for code shared with the browser: JVM + Kotlin/Wasm (browser).
plugins {
    id("blocks.repositories")
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("java").get().requiredVersion.toInt()

kotlin {
    jvmToolchain(javaVersion)
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString())) }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        // browser tests would need a browser and Karma; common tests run on the JVM, and libraries
        // without UI also run them on Node.js (add `wasmJs { nodejs() }`)
        browser { testTask { enabled = false } }
    }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// Node.js and Binaryen come from the Nix dev shell (flake.nix) instead of being downloaded: the
// downloaded builds are dynamically linked against glibc and do not run on every host.
// The npm/yarn install runs in the root project, so configure it there as well as here.
for (p in setOf(project, rootProject)) {
    p.plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
        p.extensions.configure<WasmNodeJsEnvSpec> {
            System.getenv("BLOCKS_NODE")?.let { node ->
                download.set(false)
                command.set(node)
            }
        }
    }
    p.plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
        p.extensions.configure<BinaryenEnvSpec> {
            System.getenv("BLOCKS_BINARYEN")?.let { dir ->
                download.set(false)
                command.set("$dir/bin/wasm-opt")
            }
        }
    }
}
