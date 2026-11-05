package dev.vxcc.b8.mod.creativehost

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class WorldTemplatesTest {
    @TempDir
    lateinit var tmp: Path

    private fun template(): Path = tmp.resolve("template").also {
        it.resolve("region").createDirectories()
        it.resolve("level.dat").writeText("level")
        it.resolve("region/r.0.0.mca").writeText("chunks")
        it.resolve("session.lock").writeText("lock")
    }

    @Test
    fun `copies the world without its lock`() {
        val target = tmp.resolve("servers/a/world")
        assertTrue(WorldTemplates.copyIfAbsent(template(), target))
        assertEquals("chunks", target.resolve("region/r.0.0.mca").readText())
        assertFalse(target.resolve("session.lock").exists())
        assertFalse(Files.list(target.parent).anyMatch { it.fileName.toString().endsWith(".partial") })
    }

    @Test
    fun `keeps an existing world`() {
        val target = tmp.resolve("world").also { it.createDirectories(); it.resolve("level.dat").writeText("mine") }
        assertFalse(WorldTemplates.copyIfAbsent(template(), target))
        assertEquals("mine", target.resolve("level.dat").readText())
    }

    @Test
    fun `refuses a directory without level dat`() {
        val empty = tmp.resolve("empty").createDirectories()
        assertFailsWith<IllegalArgumentException> { WorldTemplates.copyIfAbsent(empty, tmp.resolve("w")) }
    }
}
