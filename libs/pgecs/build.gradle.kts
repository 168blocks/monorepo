plugins {
    id("blocks.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    `java-library`
    `java-test-fixtures`
}

description = "pgecs: a fully ACID, persistent entity component system on top of PostgreSQL"

dependencies {
    api(libs.postgresql)
    api(project(":libs:pgecs-ir"))
    api(project(":libs:pgecs-lambda"))
    api(libs.kotlinx.serialization.json)
    // Infix relations and write accessors for the library's own types (ChildOf) and the tests'.
    ksp(project(":libs:pgecs-ksp"))
    kspTest(project(":libs:pgecs-ksp"))
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
}
