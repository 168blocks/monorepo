plugins {
    id("blocks.kotlin-jvm")
}

description = "pgecs-ksp: KSP processor generating pgecs query infix relations and write accessors"

dependencies {
    implementation(libs.ksp.api)
}

// pgecs is published apart from the b8 code (dev.vxcc.b8, gradle.properties)
group = "dev.vxcc.alexs168"
