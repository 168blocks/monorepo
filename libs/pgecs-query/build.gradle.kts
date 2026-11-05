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
}
