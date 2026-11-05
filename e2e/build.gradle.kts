plugins {
    id("blocks.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

description = "End-to-end tests of the whole network (deploy/compose.yaml); see docs/testing.md"

dependencies {
    implementation(project(":libs:api"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // the scenario DSL (dev.vxcc.b8.e2e.scenario) builds on JUnit
    implementation(platform(libs.junit.bom))
    implementation(libs.junit.jupiter)
}

// The network tests need the running network: tools/network.sh test (passes -Pe2e). A plain
// `gradlew build` only runs the harness's own unit tests (tagged "unit").
val e2e = providers.gradleProperty("e2e").isPresent
tasks.named<Test>("test") {
    useJUnitPlatform { if (!e2e) includeTags("unit") }
    // the network is outside Gradle's view: never consider a run up to date
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    // the network's addresses (tools/instance.sh), whether the Minecraft servers run (EULA), the
    // game client environment (flake.nix), the compose project (container logs of failed scenarios) and
    // its images (ComposeNetwork)
    val passed = setOf("BLOCKS_ACCEPT_EULA", "BLOCKS_CLIENT_ENV", "BLOCKS_SWAY", "BLOCKS_COMPOSE_PROJECT", "BLOCKS_RUNTIME_IMAGE", "BLOCKS_POSTGRES_IMAGE")
    System.getenv().filterKeys { it.startsWith("BLOCKS_E2E_") || it in passed }.forEach { (k, v) -> environment(k, v) }
    // real game clients (dev.vxcc.b8.e2e.client): the dev client's launch description, run directories
    if (e2e) {
        dependsOn(":mods:e2e-client:clientLaunch")
        environment("BLOCKS_E2E_CLIENT_LAUNCH", rootDir.resolve("mods/e2e-client/build/e2e-client/launch.json").absolutePath)
        environment("BLOCKS_E2E_CLIENT_DIR", layout.buildDirectory.dir("e2e-clients").get().asFile.absolutePath)
    }
    // -PupdateScreenshots: (re)write the reference screenshots (src/test/screenshots) instead of comparing
    if (providers.gradleProperty("updateScreenshots").isPresent) environment("BLOCKS_E2E_UPDATE_SCREENSHOTS", "true")
}
