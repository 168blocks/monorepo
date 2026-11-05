package net.blocks168.pgecs.ir

/**
 * A term of a query: the edges of the term graph. A query (and the body of [Expr.Exists] and
 * [Optional]) is a conjunction of terms. There are deliberately few kinds: negation, `or` and
 * nested "has a ... that ..." conditions are [Filter]s over [Expr.Exists].
 *
 * | DSL | Term |
 * | --- | --- |
 * | `acc has Account` | `Match(acc, Account)` |
 * | `mail childOf acc` | `Match(mail, ChildOf, target = acc)` |
 * | `ChildOf(mail, any)` | `Match(mail, ChildOf, target = AnyEntity)` |
 * | `mail has Email.where { p(it) }` | `Match(mail, Email, value = m)`, `Filter(p(m))` |
 * | `acc has !LobbyDenied` | `Filter(Not(Exists([Match(acc, LobbyDenied)])))` |
 * | `acc has (A or B)` | `Filter(Or(Exists([Match(acc, A)]), Exists([Match(acc, B)])))` |
 * | `acc maybe Mass` | `Optional([Match(acc, Mass, value = m)])` |
 */
sealed interface Term {

    /**
     * [subject] has [component] (with [target], for a pair). When [value] is given, it binds the
     * component's value to that variable.
     *
     * Every variable that appears here is bound by this term: rows are produced per combination of
     * matching subjects, targets and values, as flecs iterates its matches. [AnyEntity] never
     * produces rows of its own.
     *
     * The term's source is [component]'s source ([ComponentSlot.source]).
     */
    data class Match(
        val subject: EntityRef,
        val component: ComponentSlot,
        val target: EntityRef? = null,
        val value: ValueVar? = null,
    ) : Term {
        val source: SourceId get() = component.source

        override fun toString(): String = buildString {
            append(component)
            append('(').append(subject)
            if (target != null) append(", ").append(target)
            append(')')
            if (value != null) append(" -> ").append(value)
        }
    }

    /** Only rows for which [predicate] (a non-null [ValueType.BOOL]) is true. */
    data class Filter(val predicate: Expr) : Term {
        override fun toString(): String = "filter $predicate"
    }

    /**
     * Flecs' `?` term, generalised to a group: [terms] are matched if they can be, as a SQL
     * `left join`. Variables first bound inside are null outside the group when it does not match;
     * a [Filter] inside restricts when the group matches, not which rows the query returns.
     */
    data class Optional(val terms: List<Term>) : Term {
        init {
            require(terms.isNotEmpty()) { "Optional without terms" }
        }

        override fun toString(): String = terms.joinToString(", ", "optional(", ")")
    }
}
