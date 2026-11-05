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
    // egglog (through the query engine): native code through Panama, and for the bytecode fallback JVM
    // methods above HotSpot's 8000-bytecode limit (libs/pgecs-eqsat/README.md)
    jvmArgs("-XX:-DontCompileHugeMethods", "--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "1g"
    // ObserverModelTest: -Ppgecs.model.seed=random -Ppgecs.model.steps=5000 for a long run
    for (name in listOf("seed", "steps")) {
        providers.gradleProperty("pgecs.model.$name").orNull?.let { systemProperty("pgecs.model.$name", it) }
    }
    // LambdaDifferentialTest: -Ppgecs.lambda.report=<file> writes every problem there
    providers.gradleProperty("pgecs.lambda.report").orNull?.let { systemProperty("pgecs.lambda.report", it) }
}

// pgecs is published apart from the b8 code (dev.vxcc.b8, gradle.properties)
group = "dev.vxcc.alexs168"

// ---- generated predicates for the lambda differential test (LambdaDifferentialTest, issue #74)
val lambdaFuzzGen: SourceSet by sourceSets.creating
val generateLambdaFuzz by tasks.registering(JavaExec::class) {
    description = "Writes random query predicates for LambdaDifferentialTest (-Ppgecs.lambda.seed, -Ppgecs.lambda.count)"
    classpath = lambdaFuzzGen.runtimeClasspath
    mainClass.set("dev.vxcc.alexs168.pgecs.lambdafuzz.GenerateKt")
    val out = layout.buildDirectory.dir("generated/lambda-fuzz")
    val seed = providers.gradleProperty("pgecs.lambda.seed").orElse("74")
    val count = providers.gradleProperty("pgecs.lambda.count").orElse("600")
    inputs.property("seed", seed)
    inputs.property("count", count)
    outputs.dir(out)
    argumentProviders += CommandLineArgumentProvider { listOf(out.get().asFile.path, seed.get(), count.get()) }
}
kotlin.sourceSets.named("test") { kotlin.srcDir(generateLambdaFuzz) }
