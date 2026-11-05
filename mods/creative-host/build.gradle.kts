plugins {
    id("blocks.fabric-mod")
}

description = "Creative host: starts extra MinecraftServers in a running server's JVM, fast (issue #6, #88)"

dependencies {
    // required at runtime (fabric.mod.json): the fixes for static state the hosted servers share
    runtimeOnly(project(":mods:cohost-fixes"))
}

loom {
    accessWidenerPath = file("src/main/resources/blocks168-creative-host.accesswidener")
}

// `gradlew :mods:creative-host:runServer -Pblocks.creativeHostSelfTest=<template world>` starts the dev server
// with SelfTest: hosted servers from that template, checks, then exits 0 or 1. Needs the Minecraft EULA
// accepted in run/eula.txt, and run/world a copy of the template (otherwise the template servers select
// other datapacks than the host and load their own, which the test reports as a failure).
providers.gradleProperty("blocks.creativeHostSelfTest").orNull?.let { template ->
    loom.runs.named("server") { vmArg("-Dblocks168.creative-host.selftest=$template") }
}
