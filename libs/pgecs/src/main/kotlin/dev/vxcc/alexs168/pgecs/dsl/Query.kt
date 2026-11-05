package dev.vxcc.alexs168.pgecs.dsl

import dev.vxcc.alexs168.pgecs.ir.QueryIr

/** What a result method expects back from the [QueryExecutor]. */
enum class ResultMode {
    /** All rows. */
    FETCH,

    /** Exactly one row; the executor may fetch two to detect extras, and throws otherwise. */
    ONE,

    /** The first row, if any (`limit 1`). */
    FIRST,

    /** One row with one `Long` column: the number of rows (`select count(*)`). */
    COUNT,
}

/**
 * Runs a lowered query: the planner and SQL compiler implement this. It finds the current
 * transaction itself (the connection layer's `TxScope`).
 *
 * [params] are the values of [QueryIr.params] in order. Each result row has one value per
 * [QueryIr.select] column, by the column's IR type: `UUID` for entities and `UUID` values, `String`,
 * `Long`, `Double`, `Boolean`, `Instant` for instants, `Duration` for durations, and the JSON text
 * (`String`) for a whole component value or a JSON field; `null` where the value is null.
 */
fun interface QueryExecutor {
    fun execute(query: QueryIr, params: List<Any?>, mode: ResultMode): List<Array<Any?>>
}

/** Thrown by [Query.fetchOne] when the query does not return exactly one row. */
class QueryResultException(message: String) : RuntimeException(message)

/**
 * A built query: its IR, the values of its bind parameters, and how to turn result rows into [R].
 * Execute it with a result method inside `ecs.read { }` or `ecs.tx { }`. Building is cheap, so a
 * query is usually built inline where it is used; its shape hits the plan cache.
 */
class Query<R> internal constructor(
    /** The lowered query (validated), with captured values as parameters. */
    val ir: QueryIr,
    /** Values of [QueryIr.params], in order. */
    val params: List<Any?>,
    private val shape: RowShape,
    private val executor: QueryExecutor?,
) {
    private fun run(mode: ResultMode): List<Array<Any?>> {
        val ex = executor ?: throw IllegalStateException("this query has no executor; build it from an Ecs")
        return ex.execute(ir, params, mode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decode(row: Array<Any?>): R = shape.build(row) as R

    /** All rows. */
    fun fetch(): List<R> = run(ResultMode.FETCH).map(::decode)

    /** Exactly one row; throws [QueryResultException] for none or several. */
    fun fetchOne(): R {
        val rows = run(ResultMode.ONE)
        if (rows.size != 1) throw QueryResultException("expected exactly one row, got ${if (rows.isEmpty()) "none" else "several"}")
        return decode(rows[0])
    }

    /** The first row, or null when there is none. */
    fun first(): R? = run(ResultMode.FIRST).firstOrNull()?.let(::decode)

    /** The number of rows, as `select count(*)`. */
    fun count(): Long {
        val rows = run(ResultMode.COUNT)
        return (rows.singleOrNull()?.singleOrNull() as? Number)?.toLong()
            ?: throw QueryResultException("count returned no number")
    }

    /** This query with another IR of the same shape of columns and parameters (system batches add locks and limits). */
    internal fun withIr(ir: QueryIr): Query<R> = Query(ir, params, shape, executor)

    override fun toString(): String = "Query($ir, params=$params)"
}

/**
 * What `select` returned: a marker that ends the `query { }` block and types it. Only the builder
 * that created it can use it.
 */
class Selection<R> internal constructor(internal val owner: QueryBuilder)
