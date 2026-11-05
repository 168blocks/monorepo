plugins {
    id("blocks.kotlin-jvm")
    `java-library`
    `java-test-fixtures`
}

description = "pgecs: a fully ACID, persistent entity component system on top of PostgreSQL"

dependencies {
    api(libs.postgresql)
    implementation(libs.hikaricp)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
}
