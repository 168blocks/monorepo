plugins {
    id("blocks.kotlin-jvm")
    `java-library`
}

description = "pgecs-lambda: decompiles Serializable query lambdas from JVM bytecode into expression trees"

// pgecs is published apart from the b8 code (dev.vxcc.b8, gradle.properties)
group = "dev.vxcc.alexs168"
