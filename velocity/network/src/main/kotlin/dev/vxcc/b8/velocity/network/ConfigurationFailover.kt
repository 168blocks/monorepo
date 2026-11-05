package dev.vxcc.b8.velocity.network

import com.velocitypowered.api.event.player.KickedFromServerEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * No failover while a server connection is still being set up (#56). When a backend fails a player
 * during the configuration phase, Velocity would redirect them to the fallback server while the client
 * is still configuring for the failed one: the client then gets a second set of registries and crashes
 * ("Adding duplicate key ResourceKey[minecraft:worldgen/biome / ...]", "Network Protocol Error").
 *
 * Velocity's API does not tell whether the client already is in the configuration phase, so every
 * redirect of a connection attempt becomes a disconnect with a readable message; the player simply
 * reconnects (and lands in the lobby). Kicks of players already playing on a server still fail over.
 */
object ConfigurationFailover {
    fun decide(
        kickedDuringServerConnect: Boolean,
        result: KickedFromServerEvent.ServerKickResult,
        server: String,
        reason: Component?,
    ): KickedFromServerEvent.ServerKickResult? {
        if (!kickedDuringServerConnect || result !is KickedFromServerEvent.RedirectPlayer) return null
        val message = Component.text("Could not connect you to $server", NamedTextColor.RED)
            .append(if (reason != null) Component.text(": ", NamedTextColor.RED).append(reason) else Component.empty())
            .append(Component.newline())
            .append(Component.text("Please reconnect.", NamedTextColor.GRAY))
        return KickedFromServerEvent.DisconnectPlayer.create(message)
    }
}
