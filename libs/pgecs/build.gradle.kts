plugins {
    id("blocks.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
    `java-test-fixtures`
}

description = "pgecs: a fully ACID, persistent entity component system on top of PostgreSQL"

dependencies {
    api(libs.postgresql)
    api(project(":libs:pgecs-ir"))
    api(project(":libs:pgecs-lambda"))
    api(libs.kotlinx.serialization.json)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
}
