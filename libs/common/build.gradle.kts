plugins {
    id("blocks.kotlin-jvm")
    `java-library`
}

description = "JVM code shared by the backend, the Fabric mods and the Velocity plugins"

dependencies {
    api(libs.tinyjcbor)
}
