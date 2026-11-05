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
}

// The network tests need the running network: tools/network.sh test (passes -Pe2e). A plain
// `gradlew build` only runs the harness's own unit tests (tagged "unit").
val e2e = providers.gradleProperty("e2e").isPresent
tasks.named<Test>("test") {
    useJUnitPlatform { if (!e2e) includeTags("unit") }
    // the network is outside Gradle's view: never consider a run up to date
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    // the network's addresses (tools/instance.sh) and whether the Minecraft servers run (EULA)
    System.getenv().filterKeys { it.startsWith("BLOCKS_E2E_") || it == "BLOCKS_ACCEPT_EULA" }.forEach { (k, v) -> environment(k, v) }
}
