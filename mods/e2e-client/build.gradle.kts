plugins {
    id("blocks.fabric-mod")
}

description = "Dev-only client mod for end-to-end tests: a local JSON RPC that drives a real game client"

// Never part of a deployment: e2e/ starts dev clients with this mod (see e2e/.../client/GameClients.kt).
//
// `clientLaunch` writes how `runClient` would start the game (java, JVM arguments, classpath, main class,
// program arguments) to build/e2e-client/launch.json. The e2e launcher starts the clients from that
// itself, each with its own run directory and username under its own headless compositor, instead of one
// `gradlew runClient` per client.
val runClient = tasks.named<JavaExec>("runClient")
val launchJvmArgs = providers.provider { runClient.get().allJvmArgs.filterNot { it.startsWith("@") } }
val launchArgs = providers.provider {
    val task = runClient.get()
    task.args.orEmpty() + task.argumentProviders.flatMap { it.asArguments() }
}
val launchMainClass = runClient.flatMap { it.mainClass }
val launchJava = runClient.flatMap { it.javaLauncher }.map { it.executablePath.asFile.absolutePath }

tasks.register("clientLaunch") {
    description = "Writes the dev client's launch description for the e2e client launcher"
    // the same preparation runClient depends on (assets, natives, launch config)
    dependsOn("configureClientLaunch")
    val classpath = sourceSets["main"].runtimeClasspath
    inputs.files(classpath).withNormalizer(ClasspathNormalizer::class)
    inputs.property("jvmArgs", launchJvmArgs)
    inputs.property("args", launchArgs)
    inputs.property("mainClass", launchMainClass)
    inputs.property("java", launchJava)
    val out = layout.buildDirectory.file("e2e-client/launch.json")
    outputs.file(out)
    // locals only: the action must not reference the build script (configuration cache)
    val java = launchJava
    val jvmArgs = launchJvmArgs
    val mainClass = launchMainClass
    val args = launchArgs
    doLast {
        val spec = mapOf(
            "java" to java.get(),
            "jvmArgs" to jvmArgs.get(),
            "classpath" to classpath.files.map { it.absolutePath },
            "mainClass" to mainClass.get(),
            "args" to args.get(),
        )
        out.get().asFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(spec)))
    }
}
