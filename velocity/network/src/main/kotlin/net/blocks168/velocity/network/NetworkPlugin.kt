package net.blocks168.velocity.network

import com.google.inject.Inject
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.blocks168.pgecs.PgEcsConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.slf4j.Logger
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The network plugin every edge Velocity loads (design: https://claude.ai/artifact/WDswfCSv1YSxbGT7HWgtzd):
 *
 * - **online players** across edges in PostgreSQL ([NetworkStore], schema [NetworkSchema]): one row per
 *   UUID, refreshed every [HEARTBEAT_SECONDS]; rows of a dead edge expire. Pings show the network-wide
 *   player count.
 * - **cross-edge actions** over LISTEN/NOTIFY ([NetworkMessage]): a login kicks the player's older session
 *   on any edge; `/network kick|ban|send|broadcast|staff|drain|undrain` reach the edge that has the player.
 * - **drain**: the edge stops taking logins and transfers its players (transfer packets) to another edge.
 * - **maintenance**: without the database (no tunnel to the core, schema missing) the edge answers pings
 *   with "maintenance" and refuses logins.
 *
 * Configuration (environment): `PGECS_URL`, `PGECS_USER` (blocks_edge), `BLOCKS_EDGE_NAME` (edge-1),
 * `BLOCKS_EDGE_ADDRESS` (host:port players connect to, where drained players are sent).
 */
class NetworkPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    private val edge = System.getenv("BLOCKS_EDGE_NAME") ?: "edge"
    private val address = System.getenv("BLOCKS_EDGE_ADDRESS") ?: "127.0.0.1:25565"
    private var store: NetworkStore? = null
    private var listener: NetworkListener? = null

    /** session id of every player logged in through this edge */
    private val sessions = ConcurrentHashMap<Player, UUID>()

    @Volatile private var lastDatabaseOk = 0L
    @Volatile private var schemaReady = false
    @Volatile private var networkCount = 0
    @Volatile var draining = false; private set

    /** Database reachable recently and listening for cross-edge messages */
    val healthy: Boolean
        get() = store != null && schemaReady && listener?.listening == true &&
            System.nanoTime() - lastDatabaseOk < TimeUnit.SECONDS.toNanos(HEALTHY_SECONDS)

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        val url = System.getenv("PGECS_URL") ?: run {
            logger.warn("PGECS_URL not set: network plugin off (no registry, no maintenance mode)")
            return
        }
        // fail fast instead of hanging when the tunnel is gone
        val sep = if ('?' in url) '&' else '?'
        val config = PgEcsConfig(url + "${sep}connectTimeout=5&socketTimeout=10&tcpKeepAlive=true", System.getenv("PGECS_USER") ?: "blocks_edge")
        store = NetworkStore(config, edge)
        listener = NetworkListener(config, ::handle) { msg, e -> if (e == null) logger.warn(msg) else logger.warn(msg, e) }.start()
        proxy.scheduler.buildTask(this, Runnable { probe() }).repeat(PROBE_SECONDS, TimeUnit.SECONDS).schedule()
        proxy.scheduler.buildTask(this, Runnable { heartbeat() }).delay(1, TimeUnit.SECONDS).repeat(HEARTBEAT_SECONDS, TimeUnit.SECONDS).schedule()
        proxy.commandManager.register(proxy.commandManager.metaBuilder("network").aliases("net").plugin(this).build(), NetworkCommand())
        logger.info("network plugin: edge {} at {}", edge, address)
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        listener?.close()
        store?.let { s -> sessions.forEach { (p, session) -> runCatching { s.unregister(p.uniqueId, session) } } }
        store?.close()
    }

    // --- database state ---

    private var wasHealthy = false
    private val probing = java.util.concurrent.atomic.AtomicBoolean()
    private val beating = java.util.concurrent.atomic.AtomicBoolean()

    /** at most one run of [block] at a time (a dead tunnel makes queries wait for their timeout) */
    private inline fun once(flag: java.util.concurrent.atomic.AtomicBoolean, block: () -> Unit) {
        if (!flag.compareAndSet(false, true)) return
        try { block() } finally { flag.set(false) }
    }

    private fun probe() = once(probing) {
        val s = store ?: return
        try {
            if (!schemaReady) {
                val v = s.schemaVersion()
                schemaReady = v >= NetworkSchema.VERSION
                if (!schemaReady) logger.warn("network schema at version {}, need {} (network-schema not run yet?)", v, NetworkSchema.VERSION)
            }
            if (schemaReady) {
                networkCount = s.onlineCount()
                lastDatabaseOk = System.nanoTime()
            }
        } catch (e: Exception) {
            logger.warn("network database unreachable: {}", e.toString())
        }
        val now = healthy
        if (now != wasHealthy) logger.warn(if (now) "network: database reachable, accepting logins" else "network: MAINTENANCE (no database), refusing logins")
        wasHealthy = now
    }

    private fun heartbeat() = once(beating) {
        val s = store ?: return
        if (!schemaReady) return
        val current = sessions.entries.map { (p, session) ->
            NetworkStore.Session(p.uniqueId, p.username, session, p.currentServer.map { it.serverInfo.name }.orElse(null))
        }
        try {
            for (old in s.heartbeat(address, draining, current)) {
                sessions.entries.firstOrNull { it.value == old.session }?.key?.disconnect(text("You logged in from another location", NamedTextColor.RED))
            }
        } catch (e: Exception) {
            logger.warn("network heartbeat failed: {}", e.toString())
        }
    }

    // --- logins ---

    @Subscribe(order = PostOrder.FIRST)
    fun onPreLogin(event: PreLoginEvent): EventTask? {
        if (store == null) return null
        when {
            !healthy -> event.result = PreLoginEvent.PreLoginComponentResult.denied(MAINTENANCE)
            draining -> event.result = PreLoginEvent.PreLoginComponentResult.denied(DRAINING)
            else -> {
                // the newest login wins on this edge too: Velocity's kick-existing-players only works in
                // online mode, otherwise it refuses the new login while the old one is connected
                val old = proxy.getPlayer(event.username).orElse(null) ?: return null
                return EventTask.async {
                    old.disconnect(text("You logged in from another location", NamedTextColor.RED))
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (proxy.getPlayer(event.username).isPresent && System.nanoTime() < end) Thread.sleep(50)
                }
            }
        }
        return null
    }

    @Subscribe(order = PostOrder.LAST)
    fun onLogin(event: LoginEvent): EventTask? {
        val s = store ?: return null
        if (!event.result.isAllowed) return null
        return EventTask.async {
            val player = event.player
            try {
                val ban = s.ban(player.username, player.uniqueId)
                if (ban != null) {
                    event.result = ResultedEvent.ComponentResult.denied(text("You are banned from 168blocks: ${ban.reason}", NamedTextColor.RED))
                    return@async
                }
                val session = UUID.randomUUID()
                s.register(player.uniqueId, player.username, session)
                sessions[player] = session
            } catch (e: Exception) {
                logger.warn("login of {}: database failed: {}", player.username, e.toString())
                event.result = ResultedEvent.ComponentResult.denied(MAINTENANCE)
            }
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val s = store ?: return
        val session = sessions.remove(event.player) ?: return
        async { s.unregister(event.player.uniqueId, session) }
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        val s = store ?: return
        val session = sessions[event.player] ?: return
        async { s.setServer(event.player.uniqueId, session, event.server.serverInfo.name) }
    }

    @Subscribe(order = PostOrder.LAST)
    fun onPing(event: ProxyPingEvent) {
        if (store == null) return
        val ping = event.ping.asBuilder()
        if (healthy) {
            val count = maxOf(networkCount, proxy.playerCount)
            ping.onlinePlayers(count)
                .description(text(MOTD_MARKER, NamedTextColor.GOLD).append(text(" — $count online", NamedTextColor.GRAY)))
        } else {
            ping.onlinePlayers(proxy.playerCount)
                .description(text(MOTD_MARKER, NamedTextColor.GOLD).append(text(" — maintenance", NamedTextColor.RED)))
        }
        event.ping = ping.build()
    }

    // --- cross-edge messages ---

    private fun handle(message: NetworkMessage) {
        when (message) {
            is NetworkMessage.Kick -> for ((player, session) in sessions) {
                val match = (message.uuid != null && player.uniqueId == message.uuid) ||
                    (message.name != null && player.username.equals(message.name, ignoreCase = true))
                if (match && session != message.exceptSession) {
                    logger.info("kicking {} ({})", player.username, message.reason)
                    player.disconnect(text(message.reason, NamedTextColor.RED))
                }
            }
            is NetworkMessage.Send -> proxy.getPlayer(message.name).ifPresent { player ->
                proxy.getServer(message.server).ifPresent { player.createConnectionRequest(it).fireAndForget() }
            }
            is NetworkMessage.Broadcast -> {
                val line = if (message.staffOnly) {
                    text("[staff] ${message.from}: ", NamedTextColor.AQUA).append(text(message.message, NamedTextColor.WHITE))
                } else {
                    text("[168blocks] ", NamedTextColor.GOLD).append(text(message.message, NamedTextColor.YELLOW))
                }
                for (p in proxy.allPlayers) if (!message.staffOnly || p.hasPermission(STAFF)) p.sendMessage(line)
                logger.info("{}broadcast from {}: {}", if (message.staffOnly) "staff " else "", message.from, message.message)
            }
            is NetworkMessage.Drain -> if (message.edge == edge) setDraining(message.draining)
        }
    }

    private fun setDraining(on: Boolean) {
        draining = on
        logger.warn(if (on) "network: DRAINING, refusing logins and transferring players" else "network: drain ended, accepting logins")
        val s = store ?: return
        runCatching { s.setDraining(on) }
        if (!on) return
        val targets = runCatching { s.drainTargets() }.getOrElse { emptyList() }
        if (targets.isEmpty()) {
            logger.warn("drain: no other edge accepts players; keeping them here")
            return
        }
        for ((i, player) in proxy.allPlayers.withIndex()) {
            val target = targets[i % targets.size]
            val (host, port) = target.address.substringBeforeLast(':') to target.address.substringAfterLast(':').toInt()
            logger.info("drain: transferring {} to {} ({})", player.username, target.name, target.address)
            player.transferToHost(InetSocketAddress.createUnresolved(host, port))
        }
    }

    private fun async(block: () -> Unit) {
        proxy.scheduler.buildTask(this, Runnable {
            runCatching(block).onFailure { logger.warn("network database: {}", it.toString()) }
        }).schedule()
    }

    // --- /network ---

    private inner class NetworkCommand : SimpleCommand {
        override fun hasPermission(invocation: SimpleCommand.Invocation) = invocation.source().hasPermission(STAFF)

        override fun suggest(invocation: SimpleCommand.Invocation): List<String> =
            if (invocation.arguments().size <= 1) SUBCOMMANDS.filter { it.startsWith(invocation.arguments().firstOrNull() ?: "") } else emptyList()

        override fun execute(invocation: SimpleCommand.Invocation) {
            val source = invocation.source()
            val args = invocation.arguments().toList()
            val s = store ?: return source.reply("network plugin is off (no PGECS_URL)")
            async {
                try {
                    run(source, s, args)
                } catch (e: Exception) {
                    source.reply("failed: $e")
                }
            }
        }

        private fun run(source: CommandSource, s: NetworkStore, args: List<String>) {
            val from = (source as? Player)?.username ?: "console"
            fun rest(from: Int) = args.drop(from).joinToString(" ")
            when (args.firstOrNull()) {
                "players" -> {
                    val players = s.onlinePlayers()
                    source.reply("${players.size} online: " + players.joinToString(", ") { "${it.name} (${it.edge}/${it.server ?: "-"})" })
                }
                "send" -> {
                    if (args.size != 3) return source.reply("usage: /network send <player> <server>")
                    s.publish(NetworkMessage.Send(args[1], args[2]))
                    source.reply("sending ${args[1]} to ${args[2]}")
                }
                "kick" -> {
                    if (args.size < 2) return source.reply("usage: /network kick <player> [reason]")
                    s.publish(NetworkMessage.Kick(null, args[1], reason = rest(2).ifBlank { "Kicked by staff" }))
                    source.reply("kicked ${args[1]}")
                }
                "ban" -> {
                    if (args.size < 2) return source.reply("usage: /network ban <player> [reason]")
                    val reason = rest(2).ifBlank { "Banned" }
                    s.addBan(args[1], s.findOnline(args[1])?.uuid, reason, from)
                    s.publish(NetworkMessage.Kick(null, args[1], reason = "You are banned from 168blocks: $reason"))
                    source.reply("banned ${args[1]}")
                }
                "unban" -> {
                    if (args.size != 2) return source.reply("usage: /network unban <player>")
                    source.reply(if (s.removeBan(args[1])) "unbanned ${args[1]}" else "${args[1]} was not banned")
                }
                "broadcast", "staff" -> {
                    if (args.size < 2) return source.reply("usage: /network ${args[0]} <message>")
                    s.publish(NetworkMessage.Broadcast(rest(1), staffOnly = args[0] == "staff", from = from))
                }
                "drain", "undrain" -> {
                    val target = args.getOrNull(1) ?: edge
                    s.publish(NetworkMessage.Drain(target, draining = args[0] == "drain"))
                    source.reply("${args[0]} $target")
                }
                else -> source.reply("/network players | send <player> <server> | kick <player> [reason] | ban <player> [reason] | unban <player> | broadcast <message> | staff <message> | drain [edge] | undrain [edge]")
            }
        }
    }

    private fun CommandSource.reply(message: String) {
        sendMessage(text(message, NamedTextColor.GRAY))
        if (this !is Player) logger.info("/network: {}", message)
    }

    companion object {
        /** permission for /network and staff broadcasts */
        const val STAFF = "blocks168.network.staff"
        const val MOTD_MARKER = "168blocks"
        const val HEARTBEAT_SECONDS = 10L
        const val PROBE_SECONDS = 2L
        /** without a successful database round trip for this long the edge goes into maintenance */
        const val HEALTHY_SECONDS = 8L
        private val SUBCOMMANDS = listOf("players", "send", "kick", "ban", "unban", "broadcast", "staff", "drain", "undrain")
        private fun text(s: String, color: NamedTextColor) = Component.text(s, color)
        val MAINTENANCE: Component = text("168blocks is under maintenance, please try again in a few minutes.", NamedTextColor.RED)
        val DRAINING: Component = text("This edge is being restarted, please reconnect.", NamedTextColor.YELLOW)
    }
}
