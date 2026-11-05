plugins {
    id("blocks.fabric-mod")
}

description = "Creative host: starts extra MinecraftServers in a running server's JVM, fast (issue #6, #88)"

loom {
    accessWidenerPath = file("src/main/resources/blocks168-creative-host.accesswidener")
}

// `gradlew :mods:creative-host:runServer -Pblocks.creativeHostSelfTest=<template world>` starts the dev server
// with SelfTest: hosted servers from that template, checks, then exits 0 or 1 (needs the Minecraft EULA
// accepted in run/eula.txt, and cohost-fixes for the stops).
providers.gradleProperty("blocks.creativeHostSelfTest").orNull?.let { template ->
    loom.runs.named("server") { vmArg("-Dblocks168.creative-host.selftest=$template") }
}
