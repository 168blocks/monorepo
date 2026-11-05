package net.blocks168.e2e

import kotlinx.coroutines.runBlocking
import net.blocks168.e2e.agent.ServerAgent
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The lobby world (deploy/fabric/lobby): a flat grass layer at y=-64 with the spawn on top of it at
 * 0/-63/0, its chunks kept loaded (#26: players spawned inside the ground otherwise).
 */
@Tag("network")
class LobbyTest {
    @Test
    fun spawnStandsOnAFreeFlatFloor() = runBlocking {
        assumeTrue(System.getenv("BLOCKS_ACCEPT_EULA") == "true", "Minecraft EULA not accepted (BLOCKS_ACCEPT_EULA)")
        ServerAgent.fromEnv("lobby").use { lobby ->
            lobby.awaitReady(5.minutes)
            suspend fun check(command: String) = lobby.command(command).also { assertTrue(it.success, "$command: $it") }

            // floor under the spawn and across the walking area around it, two blocks of air above
            for ((x, z) in listOf(0 to 0, 24 to 0, -24 to 0, 0 to 24, 0 to -24, 24 to 24, -24 to -24)) {
                check("execute if block $x -64 $z minecraft:grass_block")
                check("execute if block $x -63 $z minecraft:air")
                check("execute if block $x -62 $z minecraft:air")
            }
            // nothing else generated: the 16 blocks above the floor around the spawn are the same as
            // (empty) air 16 blocks higher, a free walking area
            check("execute if blocks -16 -63 -16 16 -48 16 -16 -47 -16 all")
            check("execute if block 0 -47 0 minecraft:air")
            // the spawn chunk stays loaded (adventure-mode spawns do not load it, #26)
            check("forceload query 0 0")
            assertContains(check("gamerule respawn_radius").text, "0")
            assertContains(check("gamerule advance_time").text, "false")
        }
    }
}
