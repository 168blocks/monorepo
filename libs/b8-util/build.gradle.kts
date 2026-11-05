plugins {
    id("blocks.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    `java-library`
}

description = "b8 utilities on pgecs: expiry"

dependencies {
    api(project(":libs:pgecs"))
    ksp(project(":libs:pgecs-ksp"))
    kspTest(project(":libs:pgecs-ksp"))
    testImplementation(testFixtures(project(":libs:pgecs")))
}

tasks.withType<Test>().configureEach {
    // egglog (through the query engine) has JVM methods above HotSpot's 8000-bytecode limit (libs/pgecs-eqsat/README.md)
    jvmArgs("-XX:-DontCompileHugeMethods")
    maxHeapSize = "1g"
}
