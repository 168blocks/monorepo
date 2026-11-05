plugins {
    id("blocks.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

description = "168blocks backend (Ktor)"

application {
    mainClass.set("net.blocks168.backend.MainKt")
}

dependencies {
    implementation(project(":libs:api"))
    implementation(project(":libs:pgecs"))
    implementation(project(":libs:common"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)

    // Matrix bot (net.blocks168.backend.matrix): Trixnity with end-to-end encryption, store in PostgreSQL
    implementation(platform(libs.trixnity.bom))
    implementation(platform(libs.ktor.bom)) // Trixnity's Ktor modules (auth, ...) at our Ktor version
    implementation(libs.trixnity.client)
    implementation(libs.trixnity.client.cryptodriver.vodozemac)
    implementation(libs.trixnity.client.repository.exposed)
    implementation(libs.r2dbc.postgresql)
    implementation(libs.ktor.client.cio)

    testImplementation(testFixtures(project(":libs:pgecs")))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

// Matrix bot: vodozemac's JNI library comes from Nix (nix/vodozemac-jni.nix, BLOCKS_VODOZEMAC_JNI in the dev
// shell) instead of Trixnity's prebuilt glibc binaries. Trixnity loads natives/<os>-<arch>/libvodozemac.so
// from the classpath, so the Nix output goes into this module's resources (and the staged backend's jar).
configurations.configureEach {
    exclude(group = "de.connect2x.trixnity", module = "trixnity-vodozemac-binaries")
    exclude(group = "de.connect2x.trixnity", module = "trixnity-vodozemac-binaries-jvm")
}

val vodozemacJni: Provider<String> = providers.environmentVariable("BLOCKS_VODOZEMAC_JNI").orElse("")

sourceSets.main {
    resources.srcDir(vodozemacJni.map { if (it.isEmpty()) emptyList() else listOf(it) })
}

tasks.processResources {
    val jni = vodozemacJni
    doFirst {
        if (jni.get().isEmpty()) {
            throw GradleException("BLOCKS_VODOZEMAC_JNI is not set: run Gradle in the Nix dev shell (./dev ./gradlew ...)")
        }
    }
}

