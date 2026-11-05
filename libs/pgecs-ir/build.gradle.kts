plugins {
    id("blocks.kotlin-jvm")
    `java-library`
}

description = "pgecs-ir: the first-level query IR of pgecs (DSL and lambda decompiler in, planner and SQL compiler out)"

// Deliberately no dependencies beyond the Kotlin standard library: the DSL, the lambda decompiler,
// the planner, pgecs-eqsat and every source build on it.

// pgecs is published apart from the b8 code (dev.vxcc.b8, gradle.properties)
group = "dev.vxcc.alexs168"
