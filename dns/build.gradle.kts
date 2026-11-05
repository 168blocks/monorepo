plugins {
    id("blocks.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

description = "168blocks player DNS: answers PowerDNS's remote backend from the edges in PostgreSQL (docs/dns.md)"

application {
    mainClass.set("dev.vxcc.b8.dns.MainKt")
}

dependencies {
    implementation(project(":libs:pgecs"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(testFixtures(project(":libs:pgecs")))
    testImplementation(libs.ktor.server.test.host)
}
