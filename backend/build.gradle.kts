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
    implementation(libs.kord.rest) // Discord bot, src/main/kotlin/.../discord

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
    mainClass.set("net.blocks168.backend.discord.DiscordExampleKt")
}
