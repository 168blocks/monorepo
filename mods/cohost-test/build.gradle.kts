import java.util.concurrent.TimeUnit

plugins {
    id("blocks.fabric-mod")
}

description = "Dev-only test of several MinecraftServers in one JVM, under the static-guard agent (docs/co-hosting.md)"

dependencies {
    implementation(project(":mods:cohost-fixes"))
}

// ---- cohostTest: a real Fabric server with our server mods, cohost-fixes and this test mod, run under
// the static-guard agent in strict mode. Needs the Minecraft EULA: -PacceptMinecraftEula=true.
//
//   ./dev ./gradlew :mods:cohost-test:cohostTest -PacceptMinecraftEula=true

repositories {
    exclusiveContent {
        // fabric server launcher: fabric-server-launcher:<minecraft>:<loader>/<installer> (as in deploy/)
        forRepository {
            ivy("https://meta.fabricmc.net/v2/versions/loader/") {
                name = "Fabric meta"
                patternLayout { artifact("[module]/[revision]/server/[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeGroup("fabric-server-launcher") }
    }
}

fun nonTransitive(name: String) = configurations.create(name) {
    isTransitive = false
    isCanBeConsumed = false
}
val testLauncher = nonTransitive("cohostLauncher")
val testMods = nonTransitive("cohostMods")
val testAgent = nonTransitive("cohostAgent")

dependencies {
    testLauncher("fabric-server-launcher:${libs.versions.minecraft.get()}/${libs.versions.fabric.loader.get()}:${libs.versions.fabric.installer.get()}@jar")
    // the creative host's mod set: the main game server's performance mods, without Async (it cannot be
    // co-hosted, see CohostFixesMod) and FabricProxy-Lite (no proxy here)
    testMods(libs.fabric.api)
    testMods(libs.fabric.kotlin)
    testMods(libs.mod.c2me)
    testMods(libs.mod.scalablelux)
    testMods(libs.mod.vmp)
    testMods(libs.mod.ferritecore)
    testMods(project(":mods:cohost-fixes"))
    testAgent(project(":libs:static-guard", "agentJar"))
}

val acceptEula = providers.gradleProperty("acceptMinecraftEula").map { it.toBoolean() }.orElse(false)
val strict = providers.gradleProperty("cohost.strict").map { it.toBoolean() }.orElse(true)
val serverPort = providers.environmentVariable("BLOCKS_DEV_SERVER_PORT").orElse("25565")
val javaLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt())) }

tasks.register("cohostTest") {
    group = "verification"
    description = "Runs a Fabric server that starts, crashes, hangs and stops extra servers in its JVM (needs -PacceptMinecraftEula=true)"
    val modJar = tasks.named<Jar>("jar")
    dependsOn(modJar)
    inputs.files(testLauncher, testMods, testAgent, modJar)
    val dir = layout.buildDirectory.dir("cohost-test").map { it.asFile }
    val allow = layout.projectDirectory.file("static-guard-allow.txt").asFile
    val java = javaLauncher.map { it.executablePath.asFile.absolutePath }
    val launcher = testLauncher
    val mods = testMods
    val agent = testAgent
    outputs.upToDateWhen { false }
    doLast {
        check(acceptEula.get()) {
            "cohostTest starts a Minecraft server: accept the Minecraft EULA (https://aka.ms/MinecraftEULA) with -PacceptMinecraftEula=true"
        }
        val d = dir.get()
        d.deleteRecursively()
        d.resolve("mods").mkdirs()
        launcher.singleFile.copyTo(d.resolve("fabric-server-launch.jar"))
        (mods.files + modJar.get().archiveFile.get().asFile).forEach { it.copyTo(d.resolve("mods/${it.name}")) }
        d.resolve("eula.txt").writeText("eula=true\n")
        d.resolve("server.properties").writeText(
            listOf(
                "online-mode=false", "server-port=${serverPort.get()}", "view-distance=4", "simulation-distance=4",
                "management-server-enabled=false", "enable-query=false", "enable-rcon=false", "spawn-protection=0",
            ).joinToString("\n", postfix = "\n"),
        )
        val agentJar = agent.singleFile.absolutePath
        val options = listOfNotNull(
            "strict".takeIf { strict.get() }, "gets=none", "allow=${allow.absolutePath}",
            "report=${d.resolve("static-guard-report.txt")}",
        ).joinToString(";")
        val log = d.resolve("server.log")
        val p = ProcessBuilder(
            java.get(), "-Xmx3G", "-Dfabric.systemLibraries=$agentJar", "-javaagent:$agentJar=$options",
            "-jar", "fabric-server-launch.jar", "nogui",
        ).directory(d).redirectErrorStream(true).redirectOutput(log).redirectInput(ProcessBuilder.Redirect.PIPE).start()
        if (!p.waitFor(20, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            error("cohostTest: server did not finish within 20 minutes, see $log")
        }
        val result = d.resolve("cohost-test-result.txt")
        val verdict = if (result.exists()) result.readText().trim() else "no result (server exited ${p.exitValue()})"
        logger.lifecycle("cohostTest: $verdict (exit ${p.exitValue()}; log $log, static-guard report ${d.resolve("static-guard-report.txt")})")
        check(verdict == "PASS") { "cohostTest failed: $verdict" }
    }
}
