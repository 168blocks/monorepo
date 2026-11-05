import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("blocks.kotlin-multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

description = "168blocks frontend (Compose Multiplatform: web via Kotlin/Wasm, and desktop)"

kotlin {
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "frontend.js" } }
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":libs:api"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

compose.desktop {
    application {
        mainClass = "net.blocks168.frontend.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "168blocks"
        }
    }
}

// The web build the backend serves (deploy stages it next to the backend)
tasks.register<Sync>("webDist") {
    group = "build"
    description = "Production web build of the frontend"
    from(tasks.named("wasmJsBrowserDistribution"))
    into(layout.buildDirectory.dir("web"))
}
