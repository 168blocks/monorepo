package net.blocks168.pgecs.optimizer

/**
 * Times the join-reordering workload: `./dev ./gradlew :libs:pgecs-optimizer:benchmark [-Pruns=1200]`.
 * Reports the warm-up curve and the steady state (median of the second half), on a fresh e-graph per
 * query and with the rules kept in a session (push/pop per query).
 */
fun main(args: Array<String>) {
    val runs = args.firstOrNull()?.toInt() ?: 1200
    val program = Egglog::class.java.getResource("/join-reorder.egg")!!.readText()
    val prelude = program.substringBefore("(let q")
    val body = "(let q" + program.substringAfter("(let q")
    val query = "(push)\n$body\n(pop)"

    var t = System.nanoTime()
    val egglog = Egglog.create()
    println("java ${System.getProperty("java.version")}, instantiate ${ms(System.nanoTime() - t)} ms")
    t = System.nanoTime()
    egglog.run(program)
    println("first query ${ms(System.nanoTime() - t)} ms")

    val fresh = LongArray(runs) { timed { egglog.run(program) } }
    println("fresh e-graph   warm-up ${curve(fresh)}")
    println("fresh e-graph   steady state median ${ms(steady(fresh))} ms")
    println("wasm memory after fresh runs ${egglog.memoryBytes shr 20} MiB")
    egglog.runInSession(prelude)
    val session = LongArray(runs) { timed { egglog.runInSession(query) } }
    println("session e-graph steady state median ${ms(steady(session))} ms")
    println("wasm memory after session runs ${egglog.memoryBytes shr 20} MiB")
    val pool = EgglogPool(1, prelude)
    val pooled = LongArray(runs) { timed { pool.query(body) } }
    println("pool (session rebuilt every ${pool.rebuildEvery}) steady state median ${ms(steady(pooled))} ms")
    pool.withSession { println("wasm memory of the pool's instance ${it.memoryBytes shr 20} MiB") }
}

private inline fun timed(block: () -> Unit): Long {
    val s = System.nanoTime()
    block()
    return System.nanoTime() - s
}

private fun ms(ns: Long) = "%.1f".format(ns / 1e6)

private fun median(ns: List<Long>) = ns.sorted()[ns.size / 2]

private fun steady(ns: LongArray) = median(ns.drop(ns.size / 2))

private fun curve(ns: LongArray) = listOf(0, 10, 25, 50, 100, 200, 400, 800, ns.size)
    .filter { it <= ns.size }.distinct().zipWithNext()
    .joinToString(" ") { (a, b) -> "$a-$b: ${ms(median(ns.slice(a until b)))}" }
