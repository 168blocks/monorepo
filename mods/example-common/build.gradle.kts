plugins {
    id("blocks.fabric-mod")
}

description = "Example 168blocks Fabric mod (Kotlin, Java mixins), for servers and clients"

loom {
    splitEnvironmentSourceSets()
    mods {
        register("blocks168-example") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

dependencies {
    // project libraries are nested into the mod jar (jar-in-jar); Kotlin comes from fabric-language-kotlin
    implementation(project(":libs:pgecs"))
    include(project(":libs:pgecs"))
    include(libs.postgresql)
    include(libs.hikaricp) // slf4j, coroutines and kotlinx.serialization come from Minecraft and fabric-language-kotlin
}
