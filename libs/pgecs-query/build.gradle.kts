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
    // egglog: native code through Panama, and for the bytecode fallback JVM methods above HotSpot's
    // 8000-bytecode limit (libs/pgecs-eqsat/README.md)
    jvmArgs("-XX:-DontCompileHugeMethods", "--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "1g"
}

// pgecs is published apart from the b8 code (dev.vxcc.b8, gradle.properties)
group = "dev.vxcc.alexs168"
