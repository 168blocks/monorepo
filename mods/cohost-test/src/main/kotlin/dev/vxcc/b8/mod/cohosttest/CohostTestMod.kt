package dev.vxcc.b8.mod.cohosttest

import dev.vxcc.b8.mod.cohostfixes.CoHost
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.CrashReport
import net.minecraft.server.MinecraftServer
import net.minecraft.server.dedicated.DedicatedServer
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dev-only co-hosting test, run by `:mods:cohost-test:cohostTest` (docs/co-hosting.md). Once the main
 * server is up it starts extra servers in the same JVM and checks that crashing, hanging and stopping one
 * leaves the others (and the JVM) running. Every server thread is bound to its own tenant of the
 * static-guard agent, which runs in strict mode: a static written from two servers' threads throws
 * there. The verdict goes to `cohost-test-result.txt`; then the main server stops, which ends the JVM.
 */
object CohostTestMod : DedicatedServerModInitializer {
    private val log = LoggerFactory.getLogger("cohost-test")
    private val tenants = ConcurrentHashMap<MinecraftServer, Int>()
    private val nextTenant = AtomicInteger(1)
    private val failures = mutableListOf<String>()
    @Volatile private var started = false

    override fun onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            // on the server's own thread: everything it writes from here on is this server's
            val tenant = tenants.computeIfAbsent(server) { nextTenant.getAndIncrement() }
            Agent.bindThread(tenant)
            log.info("server {} is tenant {} on {}", server.serverDirectory, tenant, Thread.currentThread().name)
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (started) return@register
            started = true
            Thread({ run(server) }, "cohost-test").start()
        }
    }

    private fun check(what: String, ok: Boolean) {
        log.info("{} {}", if (ok) "ok  " else "FAIL", what)
        if (!ok) synchronized(failures) { failures += what }
    }

    private fun commands(server: MinecraftServer, vararg cs: String) {
        for (c in cs) server.executeBlocking { server.commands.performPrefixedCommand(server.createCommandSourceStack(), c) }
    }

    private fun gameplay(server: MinecraftServer) = commands(
        server,
        "forceload add -48 -48 48 48", "time set night", "weather thunder",
        "summon minecraft:zombie 0 100 0", "summon minecraft:villager 4 100 4", "summon minecraft:cow 2 100 2",
        "fill -4 90 -4 4 92 4 minecraft:stone", "setblock 0 93 0 minecraft:hopper",
        "place structure minecraft:village_plains 160 0 160", "scoreboard objectives add cohost dummy",
        "gamerule random_tick_speed 100", "save-all flush",
    )

    /** Util's shared background executor still runs tasks (DedicatedServer.stopServer shut it down). */
    private fun executorWorks(): Boolean = runCatching {
        java.util.concurrent.CompletableFuture.supplyAsync({ 42 }, Util.backgroundExecutor()).get(10, TimeUnit.SECONDS) == 42
    }.getOrDefault(false)

    private fun stillTicks(server: MinecraftServer): Boolean {
        val before = server.tickCount
        Thread.sleep(1500)
        return server.isRunning && server.tickCount > before
    }

    private fun waitStopped(server: MinecraftServer, seconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (!server.isRunning && !server.runningThread.isAlive) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun run(main: MinecraftServer) {
        val root = Path.of(System.getProperty("cohost.test.dir", "cohost-extra")).toAbsolutePath()
        val base = mapOf(
            "online-mode" to "false", "server-port" to "0", "level-type" to "minecraft:normal",
            "view-distance" to "4", "simulation-distance" to "4", "spawn-protection" to "0",
            "management-server-enabled" to "false", "enable-query" to "false", "enable-rcon" to "false",
        )
        try {
            gameplay(main)

            // 1. two servers side by side
            val a = ExtraServer.start(root.resolve("a"), base)
            check("extra server A started next to the main server", a.isReady)
            gameplay(a)
            check("main and A both tick", stillTicks(main) && stillTicks(a))
            check("CoHost sees both", CoHost.servers().containsAll(listOf(main, a)))

            // 2. a delayed crash on A crashes A only (delayedCrash was static)
            a.delayCrash(CrashReport.forThrowable(Error("cohost-test: delayed crash on A"), "cohost-test"))
            check("A stops after its delayed crash", waitStopped(a, 60))
            check("main survives A's crash", stillTicks(main))

            // 3. after A stopped, the shared executors still work (stopServer shut them down)
            check("Util.backgroundExecutor still runs tasks", executorWorks())
            val b = ExtraServer.start(root.resolve("b"), base + ("max-tick-time" to "3000"))
            check("extra server B starts after A stopped (worldgen on the shared executors)", b.isReady)
            gameplay(b)

            // 4. B's tick hangs: its watchdog stops B instead of exiting the JVM
            b.execute { Thread.sleep(9000) }
            check("B's watchdog stops B", waitStopped(b, 90))
            check("main survives B's watchdog", stillTicks(main))

            // 5. a clean stop of C does not exit the JVM (C2ME) or stop the executors
            val c = ExtraServer.start(root.resolve("c"), base)
            gameplay(c)
            c.halt(true)
            check("C stopped cleanly", !c.isRunning)
            check("main survives C's stop", stillTicks(main))
            check("Util.backgroundExecutor still runs tasks after C's stop", executorWorks())

            val violations = Agent.violations()
            violations.forEach { log.info("static-guard: {}", it) }
            check("no static written by two servers (static-guard CROSS_WRITE)", violations.none { it.startsWith("CROSS_WRITE") })
            check("static-guard agent is active", Agent.active)
        } catch (t: Throwable) {
            log.error("cohost test aborted", t)
            check("test ran to the end: $t", false)
        }
        val verdict = if (failures.isEmpty()) "PASS" else "FAIL\n" + failures.joinToString("\n")
        Files.writeString(Path.of("cohost-test-result.txt"), verdict + "\n")
        log.info("cohost test: {}", verdict)
        main.halt(false)
    }
}
