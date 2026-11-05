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
    implementation(libs.hikaricp)
    // the query engine behind Ecs.query (QueryExecutor)
    api(project(":libs:pgecs-query"))
    api(libs.kotlinx.coroutines.core)
    api(project(":libs:pgecs-ir"))
    api(project(":libs:pgecs-lambda"))
    // Component handles expose KSerializer and the DSL decodes with kotlinx.serialization.json.
    api(libs.kotlinx.serialization.json)
    // Infix relations and write accessors for the library's own types (ChildOf) and the tests'.
    ksp(project(":libs:pgecs-ksp"))
    kspTest(project(":libs:pgecs-ksp"))
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
}

tasks.withType<Test>().configureEach {
    // egglog (through the query engine) has JVM methods above HotSpot's 8000-bytecode limit (libs/pgecs-eqsat/README.md)
    jvmArgs("-XX:-DontCompileHugeMethods")
    maxHeapSize = "1g"
}

// pgecs is published apart from the b8 code (dev.vxcc.b8, gradle.properties)
group = "dev.vxcc.alexs168"
