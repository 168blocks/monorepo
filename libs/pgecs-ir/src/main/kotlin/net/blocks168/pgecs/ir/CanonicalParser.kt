package net.blocks168.pgecs.ir

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Canonical text that [Canonical.parse] cannot read. */
class CanonicalParseException(message: String) : IllegalArgumentException(message)

/** Reads [Canonical.render]'s output back; see [Canonical.parse]. */
internal class CanonicalParser private constructor(private val text: String) {

    private sealed interface Node
    private data class Atom(val value: String) : Node
    private data class Str(val value: String) : Node
    private data class ListNode(val items: List<Node>) : Node

    private var pos = 0
    private lateinit var variables: List<Variable>

    companion object {
        fun parse(text: String): QueryIr = CanonicalParser(text).query()
    }

    private fun fail(msg: String): Nothing = throw CanonicalParseException("$msg in canonical query text")

    // ---- s-expressions

    private fun skipSpace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun node(): Node {
        skipSpace()
        if (pos >= text.length) fail("unexpected end")
        return when (text[pos]) {
            '(' -> {
                pos++
                val items = mutableListOf<Node>()
                while (true) {
                    skipSpace()
                    if (pos >= text.length) fail("unclosed (")
                    if (text[pos] == ')') { pos++; break }
                    items += node()
                }
                ListNode(items)
            }
            ')' -> fail("unexpected ) at $pos")
            '"' -> Str(string())
            else -> {
                val start = pos
                while (pos < text.length && !text[pos].isWhitespace() && text[pos] != '(' && text[pos] != ')' && text[pos] != '"') pos++
                Atom(text.substring(start, pos))
            }
        }
    }

    private fun string(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= text.length) fail("unterminated escape")
                    when (val e = text[pos++]) {
                        '"', '\\' -> sb.append(e)
                        'u' -> {
                            if (text.getOrNull(pos) != '{') fail("bad \\u escape")
                            val end = text.indexOf('}', pos)
                            if (end < 0) fail("bad \\u escape")
                            sb.append(text.substring(pos + 1, end).toInt(16).toChar())
                            pos = end + 1
                        }
                        else -> fail("bad escape \\$e")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    // ---- helpers

    private fun Node.list(head: String? = null): List<Node> {
        val items = (this as? ListNode)?.items ?: fail("expected a list, got $this")
        if (head != null && (items.firstOrNull() as? Atom)?.value != head) fail("expected ($head ...), got $this")
        return if (head != null) items.drop(1) else items
    }

    private fun Node.atom(): String = (this as? Atom)?.value ?: fail("expected an atom, got $this")

    private fun Node.str(): String = (this as? Str)?.value ?: fail("expected a string, got $this")

    private fun Node.int(): Int = atom().toIntOrNull() ?: fail("expected an integer, got $this")

    private fun Node.isBlank(): Boolean = this is Atom && value == "_"

    private fun Node.head(): String? = ((this as? ListNode)?.items?.firstOrNull() as? Atom)?.value

    private fun type(n: Node): ValueType {
        val s = n.atom()
        val nullable = s.endsWith("?")
        val kind = ValueType.Kind.entries.firstOrNull { it.name.lowercase() == s.removeSuffix("?") } ?: fail("unknown type $s")
        return ValueType(kind, nullable)
    }

    private fun <E : Enum<E>> enumOf(values: Array<E>, n: Node): E {
        val s = n.atom()
        return values.firstOrNull { it.name.lowercase() == s } ?: fail("unknown ${values.first()::class.java.simpleName} $s")
    }

    private inline fun <T> wrap(what: String, f: () -> T): T = try {
        f()
    } catch (e: CanonicalParseException) {
        throw e
    } catch (e: IllegalArgumentException) {
        fail("invalid $what: ${e.message}")
    }

    private fun variable(n: Node): Variable = variables.getOrNull(n.int()) ?: fail("undeclared variable ${n.atom()}")

    // ---- query

    private fun query(): QueryIr {
        val items = node().list("query")
        skipSpace()
        if (pos != text.length) fail("trailing text")
        val version = items.getOrNull(0)?.atom()
        if (version != "v${Canonical.VERSION}") {
            fail("version $version, this library reads v${Canonical.VERSION}")
        }
        val sections = items.drop(1)
        if (sections.size != 10) fail("expected 10 sections, got ${sections.size}")
        variables = sections[0].list("vars").map { v ->
            when (v.head()) {
                "entity" -> v.list("entity").let { wrap("variable") { EntityVar(it[0].int(), it[1].atom()) } }
                "value" -> v.list("value").let { wrap("variable") { ValueVar(it[0].int(), it[1].atom(), type(it[2])) } }
                else -> fail("unknown variable $v")
            }
        }
        val params = sections[1].list("params").map(::type)
        val terms = sections[2].list("terms").map(::term)
        val select = sections[3].list("select").map { p ->
            val (name, e) = p.list().also { if (it.size != 2) fail("bad projection $p") }
            wrap("projection") { Projection(name.str(), expr(e)) }
        }
        val distinct = when (sections[4].list("distinct").singleOrNull()?.atom()) {
            "true" -> true
            "false" -> false
            else -> fail("bad distinct")
        }
        val groupBy = sections[5].list("group-by").map(::expr)
        val orderBy = sections[6].list("order-by").map { k ->
            val parts = k.list()
            if (parts.size != 3) fail("bad sort key $k")
            val descending = when (parts[1].atom()) {
                "asc" -> false
                "desc" -> true
                else -> fail("bad direction ${parts[1]}")
            }
            val nulls = when (parts[2].atom()) {
                "nulls-first" -> NullOrder.FIRST
                "nulls-last" -> NullOrder.LAST
                else -> fail("bad null order ${parts[2]}")
            }
            SortKey(expr(parts[0]), descending, nulls)
        }
        val limit = sections[7].list("limit").single().let { if (it.isBlank()) null else expr(it) }
        val offset = sections[8].list("offset").single().let { if (it.isBlank()) null else expr(it) }
        val lockParts = sections[9].list("lock")
        val lock = if (lockParts.singleOrNull()?.isBlank() == true) {
            null
        } else {
            if (lockParts.size != 4) fail("bad lock")
            val subject = variable(lockParts[0].list("var").single()) as? EntityVar ?: fail("lock subject is not an entity variable")
            val component = component(lockParts[1]) as? ComponentRef ?: fail("lock on a wildcard relation")
            RowLock(subject, component, enumOf(LockMode.entries.toTypedArray(), lockParts[2]), enumOf(LockWait.entries.toTypedArray(), lockParts[3]))
        }
        return QueryIr(variables, params, terms, select, distinct, groupBy, orderBy, limit, offset, lock)
    }

    private fun component(n: Node): ComponentSlot = when (n.head()) {
        "component" -> {
            val p = n.list("component")
            wrap("component") {
                when (p.size) {
                    3 -> ComponentRef(p[0].str(), enumOf(ComponentKind.entries.toTypedArray(), p[1]), null, SourceId(p[2].atom()))
                    4 -> ComponentRef(p[0].str(), enumOf(ComponentKind.entries.toTypedArray(), p[1]), type(p[2]), SourceId(p[3].atom()))
                    else -> fail("bad component $n")
                }
            }
        }
        "any-relation" -> wrap("wildcard relation") { AnyRelation(SourceId(n.list("any-relation").single().atom())) }
        else -> fail("expected a component, got $n")
    }

    private fun entity(n: Node): EntityRef? = when {
        n.isBlank() -> null
        n.head() == "var" -> variable(n.list("var").single()) as? EntityVar ?: fail("not an entity variable: $n")
        n.head() == "any" -> AnyEntity
        n.head() == "type-entity" -> wrap("type entity") { TypeEntity(n.list("type-entity").single().str()) }
        n.head() == "param" || n.head() == "literal" -> expr(n) as EntityRef
        else -> fail("expected an entity reference, got $n")
    }

    private fun term(n: Node): Term = when (n.head()) {
        "match" -> {
            val p = n.list("match")
            if (p.size != 4) fail("bad match $n")
            val value = if (p[3].isBlank()) null else variable(p[3].list("var").single()) as? ValueVar ?: fail("not a value variable: $n")
            Term.Match(entity(p[0]) ?: fail("match without subject"), component(p[1]), entity(p[2]), value)
        }
        "filter" -> Term.Filter(expr(n.list("filter").single()))
        "optional" -> wrap("optional") { Term.Optional(n.list("optional").map(::term)) }
        else -> fail("expected a term, got $n")
    }

    private fun expr(n: Node): Expr {
        val head = n.head() ?: fail("expected an expression, got $n")
        val p = n.list(head)
        fun two(): Pair<Expr, Expr> = if (p.size == 2) expr(p[0]) to expr(p[1]) else fail("expected 2 operands: $n")
        return wrap("expression") {
            CompareOp.entries.firstOrNull { it.name.lowercase() == head }?.let { op ->
                return@wrap two().let { (l, r) -> Expr.Compare(op, l, r) }
            }
            ArithOp.entries.firstOrNull { it.name.lowercase() == head }?.let { op ->
                return@wrap two().let { (l, r) -> Expr.Arith(op, l, r) }
            }
            AggregateFn.entries.firstOrNull { it.name.lowercase() == head }?.let { fn ->
                val distinct = p.firstOrNull()?.let { it is Atom && it.value == "distinct" } == true
                val arg = (if (distinct) p.drop(1) else p).singleOrNull() ?: fail("bad aggregate $n")
                return@wrap Expr.Aggregate(fn, if (arg.isBlank()) null else expr(arg), distinct)
            }
            when (head) {
                "var" -> Expr.Var(variable(p.single()))
                "field" -> {
                    if (p.size < 3) fail("bad field $n")
                    Expr.Field(
                        variable(p[0]) as? ValueVar ?: fail("field of an entity variable: $n"),
                        p.subList(1, p.size - 1).map { it.str() },
                        type(p.last()),
                    )
                }
                "param" -> Expr.Param(p[0].int(), type(p[1]))
                "arg" -> Expr.Arg(p[0].int(), type(p[1]))
                "literal" -> literal(type(p[0]), p[1])
                "and" -> Expr.And(p.map(::expr))
                "or" -> Expr.Or(p.map(::expr))
                "not" -> Expr.Not(expr(p.single()))
                "is-null" -> Expr.IsNull(expr(p.single()))
                "coalesce" -> Expr.Coalesce(p.map(::expr))
                "negate" -> Expr.Negate(expr(p.single()))
                "call" -> Expr.Call(FunctionId(p[0].atom()), p.drop(2).map(::expr), type(p[1]))
                "exists" -> Expr.Exists(p.map(::term))
                else -> fail("unknown expression $head")
            }
        }
    }

    private fun literal(type: ValueType, n: Node): Expr.Literal {
        val value: Any = when (type.kind) {
            ValueType.Kind.TEXT, ValueType.Kind.JSON -> n.str()
            ValueType.Kind.FLOAT -> java.lang.Double.longBitsToDouble(n.atom().toULong(16).toLong())
            ValueType.Kind.BOOL -> when (n.str()) {
                "true" -> true
                "false" -> false
                else -> fail("bad boolean $n")
            }
            ValueType.Kind.INT -> n.str().toLong()
            ValueType.Kind.UUID -> UUID.fromString(n.str())
            ValueType.Kind.INSTANT -> Instant.parse(n.str())
            ValueType.Kind.DURATION -> Duration.parse(n.str())
        }
        return Expr.Literal(value, type)
    }
}
