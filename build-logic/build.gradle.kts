plugins {
    `kotlin-dsl`
}

dependencies {
    // Every Gradle plugin the build uses is on this classpath, so modules apply them by id only and
    // they all share one classloader (Loom and the Kotlin plugin need to see each other).
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.compose.compiler.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.loom.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
}
