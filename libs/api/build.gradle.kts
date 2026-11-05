plugins {
    id("blocks.kotlin-multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

description = "Backend HTTP API model, shared by the backend and the frontend"

kotlin {
    wasmJs { nodejs() } // run the common tests on Node.js (from Nix) too
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}
