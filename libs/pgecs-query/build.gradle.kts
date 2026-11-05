plugins {
    id("blocks.kotlin-jvm")
    `java-library`
}

description = "pgecs query engine: planner, equality-saturation optimisation, SQL compiler, plan cache and execution"

dependencies {
    api(project(":libs:pgecs-ir"))
    implementation(project(":libs:pgecs-eqsat"))
    implementation(libs.caffeine)

    testImplementation(testFixtures(project(":libs:pgecs")))
    testImplementation(libs.postgresql)
}

tasks.withType<Test>().configureEach {
    // egglog's hot functions are JVM methods above HotSpot's 8000-bytecode limit (libs/pgecs-eqsat/README.md)
    jvmArgs("-XX:-DontCompileHugeMethods")
    maxHeapSize = "1g"
    // RandomQueryTest: -Ppgecs.fuzz.seed=random -Ppgecs.fuzz.queries=100000 for a long run (see the test)
    for (name in listOf("seed", "datasets", "queries", "case")) {
        providers.gradleProperty("pgecs.fuzz.$name").orNull?.let { systemProperty("pgecs.fuzz.$name", it) }
    }
}

// pgecs is published apart from the b8 code (dev.vxcc.b8, gradle.properties)
group = "dev.vxcc.alexs168"
