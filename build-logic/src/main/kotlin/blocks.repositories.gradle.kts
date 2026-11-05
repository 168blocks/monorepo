// Repositories shared by every module. Declared per project (not in settings) because Loom adds its own
// repositories to the mod projects, which would make Gradle ignore settings-level ones there.
repositories {
    mavenCentral()
    google()
    exclusiveContent {
        forRepository { maven("https://maven.vxcc.dev/libs") { name = "vxcc" } }
        filter { includeGroup("dev.vxcc") }
    }
    exclusiveContent {
        forRepository { maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" } }
        filter {
            includeGroup("com.velocitypowered")
            includeGroup("net.kyori")
        }
    }
    exclusiveContent {
        forRepository { maven("https://maven.fabricmc.net/") { name = "Fabric" } }
        filter { includeGroupAndSubgroups("net.fabricmc") }
    }
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}
