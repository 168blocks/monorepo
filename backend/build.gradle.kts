plugins {
    id("blocks.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

description = "168blocks backend (Ktor)"

application {
    mainClass.set("dev.vxcc.b8.backend.MainKt")
}

dependencies {
    implementation(project(":libs:api"))
    implementation(project(":libs:pgecs"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)
    implementation(libs.kord.rest) // Discord bot, src/main/kotlin/.../discord

    // Matrix bot (dev.vxcc.b8.backend.matrix): Trixnity with end-to-end encryption, store in PostgreSQL
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
    testImplementation(libs.ktor.client.mock)
}

// Discord example (discord/DiscordExample.kt): sends a channel message and/or a DM with a real bot token.
tasks.register<JavaExec>("runDiscordExample") {
    group = "application"
    description = "Sends example Discord messages; needs BLOCKS_DISCORD_TOKEN and a channel or user id"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.vxcc.b8.backend.discord.DiscordExampleKt")
}

// Matrix bot: vodozemac runs as wasm on Endive (libs/vodozemac-endive) instead of as a JNI library, so the
// backend has no native code and no per-platform build. That module carries trixnity-vodozemac-jvm's classes,
// rewritten to call the wasm build, and replaces the original (and its prebuilt glibc binaries) everywhere.
configurations.configureEach {
    exclude(group = "de.connect2x.trixnity", module = "trixnity-vodozemac-binaries")
    exclude(group = "de.connect2x.trixnity", module = "trixnity-vodozemac-binaries-jvm")
    resolutionStrategy.dependencySubstitution {
        substitute(module("de.connect2x.trixnity:trixnity-vodozemac-jvm"))
            .using(project(":libs:vodozemac-endive"))
            .because("vodozemac as wasm instead of JNI")
    }
}
