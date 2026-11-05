package dev.vxcc.alexs168.pgecs.query.opt

import dev.vxcc.alexs168.pgecs.eqsat.EgglogPool
import dev.vxcc.alexs168.pgecs.query.plan.Block
import dev.vxcc.alexs168.pgecs.query.plan.CmpOp
import dev.vxcc.alexs168.pgecs.query.plan.Column
import dev.vxcc.alexs168.pgecs.query.plan.Expr
import dev.vxcc.alexs168.pgecs.query.plan.LogicalQuery
import dev.vxcc.alexs168.pgecs.query.plan.Pred

/**
 * Optimises the predicates of a plan with equality saturation: egglog, run in the JVM by
 * `libs/pgecs-eqsat`, with the core rules in `core.egg` (next to this class).
 *
 * The `where` predicates of the root block and of every nested `exists` block are encoded as one
 * egglog term, with a fact for each mandatory scan (`matched`) and for which block encloses which
 * (`anc`). The rules then simplify booleans, push negation into comparisons, drop probes that a scan
 * already guarantees, merge probes of one entity over several components into one, and find
 * contradictions; the cheapest equivalent term is extracted and decoded back. Scalar expressions are
 * opaque ids: two equal expressions get the same id, so equal terms are equal in the e-graph.
 *
 * Bounded: the rules run at most [iterations] times (each a full pass over all rules), never to
 * saturation. Time limits are the caller's ([PlanOptimizer]).
 *
 * Thread-safe: the [pool] hands each call its own egglog instance.
 */
class EqsatOptimizer(private val pool: EgglogPool, val iterations: Int = DEFAULT_ITERATIONS) {
    init {
        require(iterations > 0)
    }

    fun optimize(q: LogicalQuery): LogicalQuery {
        val enc = Encoding()
        val term = enc.block(q.root, emptyList())
        val program = buildString {
            append("(let q ").append(term).append(")\n")
            append(enc.facts)
            append("(run-schedule (repeat ").append(iterations).append(" (run core)))\n")
            append("(extract q)\n")
        }
        val out = pool.query(program).trim()
        val decoded = Decoding(enc).pred(Sexp.parse(out))
        return q.copy(root = enc.rebuildRoot(q.root, decoded))
    }

    companion object {
        const val DEFAULT_ITERATIONS = 8

        /** The datatypes, relations and rules every pooled instance loads once. */
        val PRELUDE: String by lazy {
            EqsatOptimizer::class.java.getResource("core.egg")!!.readText()
        }
    }

    /** The encoding of one query: ids of expressions, opaque predicates and blocks, and the facts. */
    private class Encoding {
        val exprs = LinkedHashMap<Expr, Int>()
        val exprList = mutableListOf<Expr>()
        val atoms = mutableListOf<Pred>()
        val blocks = HashMap<Int, Block>()
        val facts = StringBuilder()

        fun id(e: Expr): Int = exprs.getOrPut(e) { exprList += e; exprList.size - 1 }

        fun ent(e: Expr?): String = if (e == null) "(AnyE)" else "(E ${id(e)})"

        /** Encodes [b]'s `where` and records its facts; [enclosing] are the ids of enclosing blocks. */
        fun block(b: Block, enclosing: List<Int>): String {
            blocks[b.id] = b
            facts.append("(anc ${b.id} ${b.id})\n")
            enclosing.forEach { facts.append("(anc ${b.id} $it)\n") }
            for (s in b.scans) {
                val rel = s.rel ?: continue
                val subject = s.subject ?: continue
                val target = s.target ?: Expr.Col(s.alias, Column.TARGET)
                facts.append("(matched ${b.id} ${ent(subject)} ${str(rel)} ${ent(target)})\n")
            }
            return pred(b.where, b.id, enclosing + b.id)
        }

        fun pred(p: Pred, blockId: Int, chain: List<Int>): String = when (p) {
            Pred.True -> "(T)"
            Pred.False -> "(F)"
            is Pred.Has -> "(Has $blockId ${ent(p.subject)} ${rels(p.rels)} ${ent(p.target)})"
            is Pred.Exists -> "(Sub ${p.block.id} ${block(p.block, chain)})"
            is Pred.Cmp -> "(Cmp ${str(p.op.sql)} ${id(p.left)} ${id(p.right)})"
            is Pred.IsNull -> "(IsNull ${id(p.expr)})"
            is Pred.And -> binary("And", p.items.map { pred(it, blockId, chain) })
            is Pred.Or -> binary("Or", p.items.map { pred(it, blockId, chain) })
            is Pred.Not -> "(Not ${pred(p.pred, blockId, chain)})"
            else -> "(Atom ${atoms.size.also { atoms += p }})"
        }

        private fun binary(op: String, items: List<String>): String =
            items.dropLast(1).foldRight(items.last()) { item, acc -> "($op $item $acc)" }

        private fun rels(rels: List<String>): String = rels.foldRight("(RNil)") { r, acc -> "(RCons ${str(r)} $acc)" }

        private fun str(s: String): String {
            require('"' !in s && '\\' !in s) { "unexpected quote in $s" }
            return "\"$s\""
        }

        /** [root] with its (optimised) `where` replaced. */
        fun rebuildRoot(root: Block, where: Pred): Block = root.copy(where = where)
    }

    private class Decoding(private val enc: Encoding) {
        /** A constructor application; nullary ones may print without parentheses. */
        private fun app(s: Sexp): Sexp.List = when (s) {
            is Sexp.List -> s
            is Sexp.Atom -> Sexp.List(listOf(s))
            is Sexp.Str -> error("unexpected string $s")
        }

        fun pred(s: Sexp): Pred {
            val l = app(s)
            val args = l.items.drop(1)
            return when (val head = (l.items.first() as Sexp.Atom).text) {
                "T" -> Pred.True
                "F" -> Pred.False
                "Has" -> Pred.Has(ent(args[1]), rels(args[2]).distinct(), ent(args[3]))
                "Sub" -> {
                    val b = enc.blocks.getValue(int(args[0]))
                    val where = pred(args[1])
                    if (where == Pred.False) Pred.False else Pred.Exists(b.copy(where = where))
                }
                "Cmp" -> Pred.Cmp(op(string(args[0])), expr(args[1]), expr(args[2]))
                "IsNull" -> Pred.IsNull(expr(args[0]))
                "Atom" -> enc.atoms[int(args[0])]
                "And" -> Pred.and(args.map(::pred))
                "Or" -> Pred.or(args.map(::pred))
                "Not" -> when (val inner = pred(args[0])) {
                    Pred.True -> Pred.False
                    Pred.False -> Pred.True
                    else -> Pred.Not(inner)
                }
                else -> error("unexpected egglog constructor $head in $s")
            }
        }

        private fun ent(s: Sexp): Expr? {
            val l = app(s)
            return when ((l.items[0] as Sexp.Atom).text) {
                "AnyE" -> null
                "E" -> expr(l.items[1])
                else -> error("unexpected entity $s")
            }
        }

        private fun rels(s: Sexp): List<String> {
            val out = mutableListOf<String>()
            var cur = app(s)
            while ((cur.items[0] as Sexp.Atom).text == "RCons") {
                out += string(cur.items[1])
                cur = app(cur.items[2])
            }
            return out
        }

        private fun expr(s: Sexp): Expr = enc.exprList[int(s)]

        private fun int(s: Sexp): Int = (s as Sexp.Atom).text.toInt()

        private fun string(s: Sexp): String = (s as Sexp.Str).value

        private fun op(sql: String): CmpOp = CmpOp.entries.first { it.sql == sql }
    }
}

/** The s-expressions egglog prints. */
internal sealed interface Sexp {
    data class Atom(val text: String) : Sexp
    data class Str(val value: String) : Sexp
    data class List(val items: kotlin.collections.List<Sexp>) : Sexp

    companion object {
        fun parse(text: String): Sexp {
            var i = 0
            fun skip() {
                while (i < text.length && text[i].isWhitespace()) i++
            }
            fun read(): Sexp {
                skip()
                val c = text[i]
                return when {
                    c == '(' -> {
                        i++
                        val items = mutableListOf<Sexp>()
                        while (true) {
                            skip()
                            if (text[i] == ')') {
                                i++
                                break
                            }
                            items += read()
                        }
                        List(items)
                    }
                    c == '"' -> {
                        i++
                        val sb = StringBuilder()
                        while (text[i] != '"') {
                            if (text[i] == '\\') i++
                            sb.append(text[i++])
                        }
                        i++
                        Str(sb.toString())
                    }
                    else -> {
                        val start = i
                        while (i < text.length && !text[i].isWhitespace() && text[i] != '(' && text[i] != ')') i++
                        Atom(text.substring(start, i))
                    }
                }
            }
            return read()
        }
    }
}
