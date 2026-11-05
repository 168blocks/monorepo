plugins {
    id("blocks.kotlin-jvm")
    `java-library`
    application
}

description = "static-guard: finds and contains static (process-global) state shared by instances co-hosted in one JVM"

application {
    mainClass.set("dev.vxcc.b8.staticguard.scan.MainKt")
}

// The agent is plain Java with no dependencies: it is loaded into the system class loader of a foreign
// JVM (a Fabric server), where a second Kotlin stdlib next to fabric-language-kotlin's would clash.
val agentJar = tasks.register<Jar>("agentJar") {
    group = "build"
    description = "Java agent jar (-javaagent:...): runtime tracing and per-tenant statics"
    archiveClassifier.set("agent")
    from(sourceSets.main.get().output) { include("dev/vxcc/b8/staticguard/agent/**") }
    manifest {
        attributes(
            "Premain-Class" to "dev.vxcc.b8.staticguard.agent.StaticGuardAgent",
            "Agent-Class" to "dev.vxcc.b8.staticguard.agent.StaticGuardAgent",
        )
    }
}
tasks.named("assemble") { dependsOn(agentJar) }

tasks.withType<Test>().configureEach {
    // the end-to-end test runs a child JVM with the agent jar
    dependsOn(agentJar)
    systemProperty("staticguard.agentJar", agentJar.get().archiveFile.get().asFile.absolutePath)
}
