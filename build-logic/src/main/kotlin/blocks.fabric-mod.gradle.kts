// A Fabric mod for Minecraft (gradle/libs.versions.toml: minecraft). Minecraft 26.x is not obfuscated,
// so this uses the non-remapping Loom plugin and needs no mappings. Kotlin code runs through
// fabric-language-kotlin; mixins are written in Java (src/main/java/.../mixin).
plugins {
    id("blocks.kotlin-jvm")
    id("net.fabricmc.fabric-loom")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    "minecraft"(lib("minecraft"))
    "implementation"(lib("fabric-loader"))
    "implementation"(lib("fabric-api"))
    "implementation"(lib("fabric-kotlin"))
}

tasks.named<ProcessResources>("processResources") {
    val props = mapOf(
        "version" to project.version.toString(),
        "minecraft" to libs.findVersion("minecraft").get().requiredVersion,
        "fabricLoader" to libs.findVersion("fabric-loader").get().requiredVersion,
        "fabricKotlin" to libs.findVersion("fabric-kotlin").get().requiredVersion,
        "java" to libs.findVersion("java").get().requiredVersion,
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}
