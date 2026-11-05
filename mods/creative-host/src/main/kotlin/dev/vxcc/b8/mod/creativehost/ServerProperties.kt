package dev.vxcc.b8.mod.creativehost

import java.nio.file.Files
import java.nio.file.Path

/** Writes a hosted server's `server.properties`: the host's file with entries replaced or added. */
object ServerProperties {
    /**
     * [base] (the host's `server.properties` text) with each key of [overrides] set, keeping the order,
     * comments and other entries of [base]; keys [base] lacks are appended.
     */
    fun merge(base: String, overrides: Map<String, String>): String {
        val pending = LinkedHashMap(overrides)
        val lines = base.lines().dropLastWhile { it.isEmpty() }.map { line ->
            val key = line.substringBefore('=').trim()
            if (!line.trimStart().startsWith('#') && line.contains('=') && key in pending) "$key=${pending.remove(key)}" else line
        }
        return (lines + pending.map { (k, v) -> "$k=$v" }).joinToString("\n", postfix = "\n")
    }

    fun write(target: Path, hostProperties: Path, overrides: Map<String, String>) {
        val base = if (Files.exists(hostProperties)) Files.readString(hostProperties) else ""
        Files.createDirectories(target.parent)
        Files.writeString(target, merge(base, overrides))
    }
}
