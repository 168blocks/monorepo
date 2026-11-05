// Stages everything the podman compose network (compose.yaml) runs into build/stage/<service>/, which
// the containers mount at /srv. Third-party jars are resolved (and cached) by Gradle like any other
// dependency; their versions live in gradle/libs.versions.toml.
//
//   ./dev ./gradlew :deploy:stage                                 (tools/network.sh does this)
//   ./dev ./gradlew :deploy:stage -PacceptMinecraftEula=true      also writes eula.txt for the servers

plugins {
    base
    id("blocks.repositories")
}

repositories {
    exclusiveContent {
        // velocity proxy jars, addressed by content hash: <sha256>:velocity:<version>-<build>
        forRepository {
            ivy("https://fill-data.papermc.io/v1/objects/") {
                name = "PaperMC fill"
                patternLayout { artifact("[organisation]/[module]-[revision].[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeModule(libs.versions.velocity.proxy.sha256.get(), "velocity") }
    }
    exclusiveContent {
        // fabric server launcher: fabric-server-launcher:<minecraft>:<loader>/<installer>
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

val velocityProxy = nonTransitive("velocityProxy")
val velocityPlugins = nonTransitive("velocityPlugins")
val fabricLauncher = nonTransitive("fabricLauncher")
val lobbyMods = nonTransitive("lobbyMods")
val survivalMods = nonTransitive("survivalMods")
val ourMods = nonTransitive("ourMods")
val backendDist = nonTransitive("backendDist")
// dev-only end-to-end test agents (docs/testing.md); this network is the local test network, a
// production deployment never stages them
val e2eMods = nonTransitive("e2eMods")
val e2ePlugins = nonTransitive("e2ePlugins")

dependencies {
    velocityProxy("${libs.versions.velocity.proxy.sha256.get()}:velocity:${libs.versions.velocity.asProvider().get()}-${libs.versions.velocity.proxy.build.get()}@jar")
    velocityPlugins(project(":velocity:example-plugin", "pluginJar"))
    fabricLauncher("fabric-server-launcher:${libs.versions.minecraft.get()}/${libs.versions.fabric.loader.get()}:${libs.versions.fabric.installer.get()}@jar")

    // mods on every Fabric server
    for (cfg in listOf(lobbyMods, survivalMods)) {
        cfg(libs.fabric.api)
        cfg(libs.fabric.kotlin)
        cfg(libs.mod.fabricproxy.lite)
        cfg(libs.mod.vmp)
        cfg(libs.mod.async)
        cfg(libs.mod.ferritecore)
    }
    // main game server only
    survivalMods(libs.mod.c2me)
    survivalMods(libs.mod.scalablelux)

    ourMods(project(":mods:example-common"))

    e2eMods(project(":mods:e2e-agent"))
    e2ePlugins(project(":velocity:e2e-agent", "pluginJar"))
}

val stageDir = layout.buildDirectory.dir("stage")
val acceptEula = providers.gradleProperty("acceptMinecraftEula").map { it.toBoolean() }.orElse(false)

// Forwarding secret shared by the edge Velocity proxies and FabricProxy-Lite; generated once per checkout.
val forwardingSecret = tasks.register("forwardingSecret") {
    val out = layout.buildDirectory.file("forwarding.secret")
    outputs.file(out)
    onlyIf { !out.get().asFile.exists() }
    doLast {
        val bytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        out.get().asFile.writeText(java.util.HexFormat.of().formatHex(bytes))
    }
}

/**
 * Edge Velocity proxies: each gets its own directory (logs, plugin data) with velocity.toml rendered
 * from deploy/edge-velocity/velocity.toml. Edge 3 sits behind an L4 filter stand-in (HAProxy, PROXY
 * protocol v2), the other edges take players directly.
 */
val edges = mapOf("edge-1" to false, "edge-2" to false, "edge-3" to true) // name to haproxy-protocol

val stageEdges = edges.map { (edge, haproxy) ->
    tasks.register<Sync>("stage${edge.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }}") {
        dependsOn(forwardingSecret)
        inputs.property("haproxy", haproxy)
        into(stageDir.map { it.dir(edge) })
        from(velocityProxy) { rename { "velocity.jar" } }
        from(velocityPlugins) { into("plugins") }
        from(e2ePlugins) { into("plugins") }
        from("edge-velocity") {
            filesMatching("velocity.toml") {
                filter { line -> line.replace("@EDGE@", edge).replace("@HAPROXY_PROTOCOL@", haproxy.toString()) }
            }
        }
        from(layout.buildDirectory.file("forwarding.secret"))
        preserve { include("logs/**", "plugins/*/**") }
    }
}

// generatedWorld: the world holds no state and is generated from fabric/<name> (the lobby). A world staged
// from other files there (an older world preset) is deleted, so the server generates it anew.
fun stageFabricServer(name: String, mods: Configuration, generatedWorld: Boolean = false) = tasks.register<Sync>("stage${name.replaceFirstChar(Char::uppercase)}") {
    dependsOn(forwardingSecret)
    val secret = layout.buildDirectory.file("forwarding.secret")
    val eula = acceptEula
    inputs.property("eula", eula)
    if (generatedWorld) {
        val source = fileTree("fabric/$name")
        val fingerprint = provider {
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            source.visit { if (!isDirectory) { sha.update(relativePath.pathString.toByteArray()); sha.update(file.readBytes()) } }
            java.util.HexFormat.of().formatHex(sha.digest())
        }
        doFirst {
            val world = destinationDir.resolve("world")
            val marker = world.resolve(".blocks168-source")
            if (world.exists() && (!marker.exists() || marker.readText() != fingerprint.get())) world.deleteRecursively()
        }
        doLast { destinationDir.resolve("world/.blocks168-source").writeText(fingerprint.get()) }
    }
    into(stageDir.map { it.dir(name) })
    from(fabricLauncher) { rename { "fabric-server-launch.jar" } }
    from(mods) { into("mods") }
    from(ourMods) { into("mods") }
    from(e2eMods) { into("mods") }
    from("fabric/common")
    from("fabric/$name")
    // the downloaded server, libraries, worlds and logs survive re-staging
    preserve {
        include("server.jar", ".fabric/**", "libraries/**", "versions/**", "world/**", "logs/**", "eula.txt", "*.json", "config/**")
    }
    doLast {
        val dir = destinationDir
        // server.properties = fabric/common + fabric/<name>/server.properties.extra
        val extra = dir.resolve("server.properties.extra")
        if (extra.exists()) {
            dir.resolve("server.properties").appendText(extra.readText())
            extra.delete()
        }
        dir.resolve("config").mkdirs()
        dir.resolve("config/FabricProxy-Lite.toml").writeText(
            """
            hackOnlineMode = true
            hackEarlySend = false
            hackMessageChain = true
            disconnectMessage = "This server is only reachable through the 168blocks proxy."
            secret = "${secret.get().asFile.readText().trim()}"
            """.trimIndent() + "\n",
        )
        if (eula.get()) dir.resolve("eula.txt").writeText("eula=true\n")
    }
}

val stageLobby = stageFabricServer("lobby", lobbyMods, generatedWorld = true)
val stageSurvival = stageFabricServer("survival", survivalMods)

val stageBackend = tasks.register<Sync>("stageBackend") {
    into(stageDir.map { it.dir("backend") })
    from(project(":backend").tasks.named("installDist"))
    from(project(":frontend").tasks.named("webDist")) { into("web") }
}

tasks.register("stage") {
    group = "deploy"
    description = "Stages the compose network into deploy/build/stage"
    dependsOn(stageEdges, stageLobby, stageSurvival, stageBackend)
}
