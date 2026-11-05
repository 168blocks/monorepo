// A Velocity proxy plugin in Kotlin. The plugin jar bundles its own dependencies (Kotlin stdlib and
// project libraries) because Velocity provides none of them; velocity-api itself is compileOnly.
plugins {
    id("blocks.kotlin-jvm")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "compileOnly"(libs.findLibrary("velocity-api").get())
    "testImplementation"(libs.findLibrary("velocity-api").get())
}

tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("velocity-plugin.json") { expand(props) }
}

// Fat jar: plugin classes plus the runtime classpath (Velocity's own libraries are compileOnly)
val pluginJar = tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "Velocity plugin jar with bundled dependencies"
    archiveClassifier.set("plugin")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(project.the<SourceSetContainer>()["main"].output)
    val runtime = configurations.named("runtimeClasspath")
    dependsOn(runtime)
    from(runtime.map { cfg -> cfg.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}
tasks.named("assemble") { dependsOn(pluginJar) }

// lets other projects (deploy) depend on the plugin jar: project(":velocity:x", "pluginJar")
configurations.create("pluginJar") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add("pluginJar", pluginJar)
