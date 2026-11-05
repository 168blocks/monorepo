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
    for (dep in listOf(project(":libs:common"), project(":libs:pgecs"))) {
        implementation(dep)
        include(dep)
    }
    include(libs.tinyjcbor)
    include(libs.postgresql)
}
