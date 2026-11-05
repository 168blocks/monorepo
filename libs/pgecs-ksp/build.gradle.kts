plugins {
    id("blocks.kotlin-jvm")
}

description = "pgecs-ksp: KSP processor generating pgecs query infix relations and write accessors"

dependencies {
    implementation(libs.ksp.api)
}
