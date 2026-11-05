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

    testImplementation(testFixtures(project(":libs:pgecs")))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}
