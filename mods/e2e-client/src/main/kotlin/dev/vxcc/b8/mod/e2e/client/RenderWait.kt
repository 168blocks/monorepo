package dev.vxcc.b8.mod.e2e.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.chunk.CompiledSectionMesh
import net.minecraft.core.SectionPos
import net.minecraft.util.Util

/**
 * Waits until the world around the player is really on screen, for screenshots: not a fixed time after
 * joining, and not just "the compile queue is empty" (vanilla's `hasRenderedAllSections`, which is also
 * true before the first chunk arrived).
 *
 * Ready when, around the player's chunk within `radius` chunks (a circle; default one less than the
 * effective render distance, so the server always sends them):
 *
 * * every chunk is loaded on the client,
 * * the section compile queue is empty and every visible section there is compiled and faded in,
 * * at least `minSections` visible sections have geometry,
 *
 * and these statistics then stayed the same for `frames` rendered frames (counted by
 * `LevelRendererMixin`), so the frame that a screenshot reads shows them.
 */
class RenderWait(req: JsonObject) {
    private val radius = (req["radius"] as? JsonPrimitive)?.int
    private val minSections = (req["minSections"] as? JsonPrimitive)?.int ?: 1
    private val stableFrames = (req["frames"] as? JsonPrimitive)?.int ?: 5
    private var lastKey: List<Any>? = null
    private var since = 0L

    /** On the render thread: the statistics once ready, otherwise null. */
    fun poll(mc: Minecraft): JsonObject? {
        val stats = stats(mc, radius ?: defaultRadius(mc))
        val frame = E2eClient.frames.get()
        val key = stats.key()
        if (key != lastKey) {
            lastKey = key
            since = frame
        }
        if (!stats.ready(minSections) || frame - since < stableFrames) return null
        return stats.json()
    }

    data class Stats(
        val inWorld: Boolean,
        val radius: Int,
        val camera: List<Int>,
        val chunksMissing: Int,
        val chunksLoaded: Int,
        val compileQueue: Int,
        val visibleSections: Int,
        val renderedSections: Int,
        val uncompiledNear: Int,
        val fadingNear: Int,
        val waitingForChunks: Int,
    ) {
        fun ready(minSections: Int) = inWorld && chunksMissing == 0 && compileQueue == 0 && uncompiledNear == 0 &&
            fadingNear == 0 && renderedSections >= minSections

        fun key(): List<Any> = listOf(inWorld, camera, chunksMissing, compileQueue, visibleSections, renderedSections, uncompiledNear, fadingNear)

        fun json() = buildJsonObject {
            put("radius", radius)
            put("chunksMissing", chunksMissing)
            put("chunksLoaded", chunksLoaded)
            put("compileQueue", compileQueue)
            put("visibleSections", visibleSections)
            put("renderedSections", renderedSections)
            put("uncompiledNear", uncompiledNear)
            put("fadingNear", fadingNear)
            put("waitingForChunks", waitingForChunks)
        }
    }

    companion object {
        fun defaultRadius(mc: Minecraft) = (mc.options.effectiveRenderDistance - 1).coerceAtLeast(1)

        fun stats(mc: Minecraft, radius: Int): Stats {
            val level = mc.level
            val player = mc.player
            if (level == null || player == null || mc.gui.screen() != null) {
                return Stats(false, radius, emptyList(), -1, 0, 0, 0, 0, 0, 0, 0)
            }
            val cx = player.chunkPosition().x()
            val cz = player.chunkPosition().z()
            val r2 = radius * radius
            val chunks = level.chunkSource
            var missing = 0
            for (dx in -radius..radius) for (dz in -radius..radius) {
                if (dx * dx + dz * dz <= r2 && !chunks.hasChunk(cx + dx, cz + dz)) missing++
            }
            val renderer = mc.levelRenderer
            val now = Util.getMillis()
            val fade = Util.toMillis(mc.options.chunkSectionFadeInTime().get())
            var rendered = 0
            var uncompiled = 0
            var fading = 0
            val visible = renderer.visibleSections()
            for (section in visible) {
                val mesh = section.sectionMesh.get()
                if (mesh.hasRenderableLayers()) rendered++
                val node = section.sectionNode
                val dx = SectionPos.x(node) - cx
                val dz = SectionPos.z(node) - cz
                if (dx * dx + dz * dz > r2) continue
                if (mesh === CompiledSectionMesh.UNCOMPILED) uncompiled++
                else if (mesh.hasRenderableLayers() && section.getVisibility(now, fade) < 1f) fading++
            }
            return Stats(
                inWorld = true,
                radius = radius,
                camera = listOf(cx, SectionPos.blockToSectionCoord(player.blockY), cz),
                chunksMissing = missing,
                chunksLoaded = chunks.loadedChunksCount,
                compileQueue = renderer.sectionRenderDispatcher()?.compileQueueSize ?: 0,
                visibleSections = visible.size,
                renderedSections = rendered,
                uncompiledNear = uncompiled,
                fadingNear = fading,
                waitingForChunks = renderer.expectedChunks().size,
            )
        }
    }
}
