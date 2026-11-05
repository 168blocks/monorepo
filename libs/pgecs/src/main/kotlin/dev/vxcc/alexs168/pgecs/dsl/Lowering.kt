package dev.vxcc.alexs168.pgecs.dsl

import java.lang.reflect.Type
import java.time.Duration
import java.time.Instant
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializerOrNull
import dev.vxcc.alexs168.pgecs.Capability
import dev.vxcc.alexs168.pgecs.Component
import dev.vxcc.alexs168.pgecs.ComponentType
import dev.vxcc.alexs168.pgecs.EntityId
import dev.vxcc.alexs168.pgecs.Relation
import dev.vxcc.alexs168.pgecs.Tag
import dev.vxcc.alexs168.pgecs.ir.AnyEntity
import dev.vxcc.alexs168.pgecs.ir.ArithOp
import dev.vxcc.alexs168.pgecs.ir.CompareOp
import dev.vxcc.alexs168.pgecs.ir.EntityRef
import dev.vxcc.alexs168.pgecs.ir.EntityVar
import dev.vxcc.alexs168.pgecs.ir.Expr
import dev.vxcc.alexs168.pgecs.ir.Functions
import dev.vxcc.alexs168.pgecs.ir.IrBuilder
import dev.vxcc.alexs168.pgecs.ir.QueryIr
import dev.vxcc.alexs168.pgecs.ir.TermsBuilder
import dev.vxcc.alexs168.pgecs.ir.ValueType
import dev.vxcc.alexs168.pgecs.ir.ValueVar
import dev.vxcc.alexs168.pgecs.ir.and
import dev.vxcc.alexs168.pgecs.ir.or
import dev.vxcc.alexs168.pgecs.lambda.BinOp
import dev.vxcc.alexs168.pgecs.lambda.CmpOp
import dev.vxcc.alexs168.pgecs.lambda.LExpr
import dev.vxcc.alexs168.pgecs.lambda.LambdaInstance
import dev.vxcc.alexs168.pgecs.lambda.render
import dev.vxcc.alexs168.pgecs.serial.PgecsJson
import dev.vxcc.alexs168.pgecs.serial.SerialTypes

/**
 * Lowers what a [QueryBuilder] recorded into the first-level IR (`dev.vxcc.alexs168.pgecs.ir`), and
 * decides how result rows become Kotlin values.
 *
 * - Variables are declared in order; a value variable is bound for every value component a term
 *   matches (`mail_email`), so lambdas and `select` can refer to it.
 * - Decompiled lambdas ([LExpr]) become IR expressions: getters on component types become JSON
 *   fields (respecting `@SerialName`), whitelisted `String` and `Instant` methods become built-in
 *   functions or comparisons, captured values become bind parameters, constants become literals
 *   (lifted to parameters by the planner's `parameterize`).
 * - Anything else is an error now, naming the call and the lambda's source.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class Lowering(private val q: QueryBuilder) {

    private val b = IrBuilder()
    private val params = ArrayList<Any?>()
    private val entityVars = IdentityHashMap<QueryVariable, EntityVar>()
    private val names = HashSet<String>()

    /** A component value bound by a match: its variable and how to read and decode it. */
    private class Binding(val variable: ValueVar, val component: Component<*>, val optional: Boolean)

    /** Bindings visible at the current point: the innermost scope last (a `not { }` group is a scope). */
    private val scopes = ArrayDeque<HashMap<Pair<Any, String>, Binding>>().apply { addLast(HashMap()) }

    fun <R> lower(executor: QueryExecutor?): Query<R> {
        for (v in q.variables) entityVars[v] = b.entity(uniqueName(v.name))
        lowerTerms(q.terms, b, optional = false)
        val shape = lowerSelect()
        for ((key, descending) in q.orderBy) {
            val e = asExpr(lowerLambda(key, emptyList()), key)
            b.orderBy(e, descending)
        }
        q.limit?.let { b.limit(param(ValueType.INT, it)) }
        q.offset?.let { b.offset(param(ValueType.INT, it)) }
        if (q.distinct) b.distinct()
        val ir = try {
            b.build()
        } catch (e: IllegalArgumentException) {
            throw QueryBuildException("invalid query: ${e.message}", e)
        }
        return Query(ir, params.toList(), shape, executor)
    }

    /**
     * `select f(e[component])` over every entity [subject] with [component]: how `index { }` and
     * `unique { }` lambdas reach the SQL compiler, which renders the indexed expression exactly as it
     * renders the same expression in a query. Captured values are refused (an index is fixed).
     */
    fun valueFunction(subject: QueryVariable, component: Component<*>, f: LambdaInstance): QueryIr {
        for (v in q.variables) entityVars[v] = b.entity(uniqueName(v.name))
        val binding = bind(b, subject, component, optional = false)
        val e = asExpr(lowerLambda(f, listOf(valueOf(binding))), f)
        if (params.isNotEmpty()) {
            throw QueryBuildException("${component.name}: index and unique lambdas cannot use captured values (${f.decompiled.location})")
        }
        b.select("indexed", e)
        return try {
            b.build()
        } catch (e: IllegalArgumentException) {
            throw QueryBuildException("invalid index expression: ${e.message}", e)
        }
    }

    // ---- names and parameters ----

    private val columnNames = HashSet<String>()

    private fun uniqueName(base: String, taken: MutableSet<String> = names): String {
        val clean = base.replace(Regex("[^A-Za-z0-9_]"), "_").let { if (it.first().isDigit()) "_$it" else it }
        var n = clean
        var i = 2
        while (!taken.add(n)) n = "${clean}_${i++}"
        return n
    }

    private fun param(type: ValueType, value: Any?): Expr.Param {
        val p = b.param(type)
        params += value
        return p
    }

    // ---- terms ----

    private fun subjectKey(e: EntityId): Any = if (e is QueryVariable) e else e.id

    private fun entityRef(e: EntityId): EntityRef = when {
        e is QueryVariable && e.wildcard -> AnyEntity
        e is QueryVariable -> entityVars[e] ?: throw QueryBuildException("variable $e belongs to another query")
        else -> param(ValueType.UUID, e.id)
    }

    private fun lookup(subject: EntityId, c: Component<*>): Binding? {
        val key = subjectKey(subject) to c.name
        for (s in scopes.reversed()) s[key]?.let { return it }
        return null
    }

    /** Binds [c] on [subject] in [tb]; reuses an existing top-level binding of the same pair. */
    private fun bind(tb: TermsBuilder, subject: EntityId, c: Component<*>, optional: Boolean): Binding {
        val key = subjectKey(subject) to c.name
        val scope = scopes.last()
        scope[key]?.let { return it }
        val name = uniqueName(((subject as? QueryVariable)?.takeIf { !it.wildcard }?.name ?: "e") + "_" + c.shortName)
        val v = b.value(name, c.ref.valueType!!)
        tb.match(entityRef(subject), c.ref, null, v)
        return Binding(v, c, optional).also { scope[key] = it }
    }

    private fun lowerTerms(terms: List<DTerm>, tb: TermsBuilder, optional: Boolean) {
        for (t in terms) when (t) {
            is DTerm.Match -> lowerMatch(t, tb, optional)
            is DTerm.Group -> when (t.kind) {
                DTerm.GroupKind.NOT -> {
                    scopes.addLast(HashMap())
                    try {
                        val exists = tb.exists { lowerTerms(t.terms, this, optional) }
                        tb.filter(Expr.Not(exists))
                    } finally {
                        scopes.removeLast()
                    }
                }
                DTerm.GroupKind.OPTIONAL -> tb.optional { lowerTerms(t.terms, this, optional = true) }
            }
            is DTerm.AnyOf -> {
                val subject = entityRef(t.subject)
                val alternatives = t.types.map { type ->
                    if (type !is Tag && type !is Component<*>) {
                        throw QueryBuildException("${type.name}: only tags and components can be combined with or")
                    }
                    tb.exists { match(subject, type.ref) }
                }
                val e = or(alternatives)
                tb.filter(if (t.negated) Expr.Not(e) else e)
            }
            is DTerm.Filter -> tb.filter(asBool(lowerLambda(t.condition, emptyList()), t.condition))
        }
    }

    private fun lowerMatch(t: DTerm.Match, tb: TermsBuilder, optional: Boolean) {
        when (val type: ComponentType = t.type) {
            is Relation -> {
                if (t.where.isNotEmpty()) throw QueryBuildException("relation ${type.name} has no value to filter on")
                val target = t.target ?: throw QueryBuildException("relation ${type.name} needs a target")
                tb.match(entityRef(t.subject), type.ref, entityRef(target))
            }
            is Tag -> {
                if (t.where.isNotEmpty()) throw QueryBuildException("tag ${type.name} has no value to filter on")
                tb.match(entityRef(t.subject), type.ref)
            }
            is Capability<*> -> throw QueryBuildException(
                "capability ${type.name} is not a component; match (pgecs:can, capability) instead",
            )
            is Component<*> -> {
                val binding = bind(tb, t.subject, type, optional)
                for (w in t.where) {
                    val arg = valueOf(binding)
                    tb.filter(asBool(lowerLambda(w, listOf(arg)), w))
                }
            }
        }
    }

    // ---- lambda expressions ----

    /** A lowered sub-expression: an IR expression, or something that only makes sense around one. */
    private sealed interface LV {
        /**
         * An IR expression. [descriptor] is set for component values and their fields (to resolve
         * further fields), [serializer] to decode a JSON result, [loader] to load the value classes.
         */
        data class E(
            val expr: Expr,
            val descriptor: SerialDescriptor? = null,
            val serializer: KSerializer<*>? = null,
            val loader: ClassLoader? = null,
            val maybeNull: Boolean = expr.type.nullable,
        ) : LV

        data object Null : LV

        /** A JVM object: a captured value (a parameter), a constant, the builder, a component handle. */
        data class Obj(val value: Any, val captured: Boolean) : LV

        /** A constructor or `to`, only valid as the result of `select`. */
        data class Ctor(val build: (List<Any?>) -> Any?, val args: List<LV>, val argTypes: List<String>, val key: String) : LV

        /** A collection or array literal written in the lambda: `listOf("a", b)`, `setOf(...)`. */
        data class Coll(val elements: List<LV>) : LV

        /** A regular expression, [pattern] a PostgreSQL ARE (`Regex("...")`, `Pattern.compile`). */
        data class Rx(val pattern: LV, val ignoreCase: Boolean) : LV

        /** `pattern.matcher(subject)`, waiting for `matches()` or `find()`. */
        data class Matcher(val regex: Rx, val subject: LV) : LV
    }

    /** Where a lowering happens: the lambda (null for builder calls outside lambdas) and its arguments. */
    private class Ctx(val lambda: LambdaInstance?, val args: List<LV>)

    private fun lowerLambda(l: LambdaInstance, args: List<LV>): LV = lower(l.body, Ctx(l, args))

    private fun fail(ctx: Ctx, message: String): Nothing {
        val l = ctx.lambda ?: throw QueryBuildException(message)
        throw QueryBuildException("$message, in ${l.body.render()} (${l.decompiled.location})")
    }

    private fun valueOf(binding: Binding): LV.E {
        val c = binding.component
        return LV.E(
            Expr.Var(binding.variable),
            descriptor = c.serializer.descriptor,
            serializer = c.serializer,
            loader = c.javaClass.classLoader,
            maybeNull = binding.optional,
        )
    }

    private fun lower(e: LExpr, ctx: Ctx): LV = when (e) {
        is LExpr.Arg -> ctx.args.getOrNull(e.index) ?: fail(ctx, "lambda parameter ${e.index} is not bound")
        is LExpr.Captured -> when (val v = ctx.lambda!!.captured[e.index]) {
            null -> LV.Null
            else -> LV.Obj(v, captured = true)
        }
        is LExpr.Const -> when (val v = e.value) {
            null -> LV.Null
            is Boolean -> LV.E(Expr.Literal(v, ValueType.BOOL))
            is Int -> if (e.type == "Z") LV.E(Expr.Literal(v != 0, ValueType.BOOL)) else LV.Obj(v, captured = false)
            else -> LV.Obj(v, captured = false)
        }
        is LExpr.Invoke -> call(e, ctx)
        is LExpr.New -> if (e.owner == "kotlin/text/Regex") newRegex(e, ctx) else {
            val owner = e.owner
            LV.Ctor(constructorOf(owner, e.descriptor, ctx), e.args.map { lower(it, ctx) }, e.args.map { it.type }, owner + e.descriptor)
        }
        is LExpr.ArrayOf -> LV.Coll(e.elements.map { lower(it, ctx) })
        is LExpr.CollectionOf -> LV.Coll(e.elements.map { lower(it, ctx) })
        is LExpr.Compare -> regexFound(e, ctx) ?: compare(e.op, lower(e.left, ctx), lower(e.right, ctx), ctx)
        is LExpr.And -> LV.E(and(asBool(lower(e.left, ctx), ctx), asBool(lower(e.right, ctx), ctx)))
        is LExpr.Or -> LV.E(or(asBool(lower(e.left, ctx), ctx), asBool(lower(e.right, ctx), ctx)))
        is LExpr.Not -> LV.E(Expr.Not(asBool(lower(e.operand, ctx), ctx)))
        is LExpr.Binary -> {
            val op = when (e.op) {
                BinOp.ADD -> ArithOp.PLUS
                BinOp.SUB -> ArithOp.MINUS
                BinOp.MUL -> ArithOp.TIMES
                BinOp.DIV -> ArithOp.DIV
                BinOp.REM -> ArithOp.REM
                else -> fail(ctx, "bit operation ${e.op.symbol} is not translatable")
            }
            val l = asExpr(lower(e.left, ctx), ctx)
            val r = asExpr(lower(e.right, ctx), ctx)
            LV.E(
                try {
                    Expr.Arith(op, l, r)
                } catch (x: IllegalArgumentException) {
                    fail(ctx, x.message ?: "invalid arithmetic")
                },
            )
        }
        is LExpr.Neg -> LV.E(Expr.Negate(asExpr(lower(e.operand, ctx), ctx)))
        is LExpr.Convert -> lower(e.operand, ctx)
        is LExpr.Cond -> conditional(e, ctx)
        is LExpr.Concat -> {
            val parts = e.parts.map { p ->
                val x = asExpr(lower(p, ctx), ctx)
                if (x.type.kind != ValueType.Kind.TEXT) fail(ctx, "string templates of ${x.type} values are not translatable")
                if (x.type.nullable) fail(ctx, "string template of a value that may be null (Kotlin would print \"null\")")
                x
            }
            LV.E(parts.reduce { a, b -> Functions.call(Functions.CONCAT, a, b) })
        }
        is LExpr.GetField -> fail(ctx, "field ${e.name} is read directly; only getters are translatable")
    }

    /** `x ?: d` arrives as `if (x == null) d else x` and becomes `Coalesce`; other ifs become `If`. */
    private fun conditional(e: LExpr.Cond, ctx: Ctx): LV =
        choose(asBool(lower(e.condition, ctx), ctx), lower(e.then, ctx), lower(e.otherwise, ctx), ctx)

    /**
     * `if (c) then else otherwise`. When both sides construct the same type (`a to when (x) {...}`
     * compiles to one `to` per branch), the condition moves into the arguments that differ.
     */
    private fun choose(c: Expr, then: LV, otherwise: LV, ctx: Ctx): LV {
        if (then is LV.Ctor && otherwise is LV.Ctor && then.key == otherwise.key && then.args.size == otherwise.args.size) {
            val args = then.args.indices.map { i ->
                val a = then.args[i]
                val b = otherwise.args[i]
                if (a == b) a else choose(c, a, b, ctx)
            }
            return then.copy(args = args)
        }
        if (then == otherwise) return then
        val t = asExpr(then, ctx)
        val f = asExpr(otherwise, ctx)
        return when {
            c is Expr.IsNull -> LV.E(Expr.Coalesce(listOf(f, t)))
            c is Expr.Not && c.operand is Expr.IsNull -> LV.E(Expr.Coalesce(listOf(t, f)))
            else -> try {
                LV.E(Expr.If(c, t, f))
            } catch (x: IllegalArgumentException) {
                fail(ctx, x.message ?: "invalid if")
            }
        }
    }

    private fun compare(op: CmpOp, l: LV, r: LV, ctx: Ctx): LV {
        if (l == LV.Null || r == LV.Null) {
            if (l == LV.Null && r == LV.Null) return LV.E(Expr.Literal(op == CmpOp.EQ, ValueType.BOOL))
            val other = asExpr(if (l == LV.Null) r else l, ctx)
            return when (op) {
                CmpOp.EQ -> LV.E(Expr.IsNull(other))
                CmpOp.NE -> LV.E(Expr.Not(Expr.IsNull(other)))
                else -> fail(ctx, "ordering comparison with null")
            }
        }
        val irOp = CompareOp.valueOf(op.name)
        return LV.E(Expr.Compare(irOp, asExpr(l, ctx), asExpr(r, ctx)))
    }

    private fun asBool(lv: LV, ctx: Ctx): Expr {
        val e = asExpr(lv, ctx)
        if (e.type.kind != ValueType.Kind.BOOL) fail(ctx, "expected a condition, got ${e.type}")
        return e
    }

    private fun asBool(lv: LV, l: LambdaInstance): Expr = asBool(lv, Ctx(l, emptyList()))

    private fun asExpr(lv: LV, l: LambdaInstance): Expr = asExpr(lv, Ctx(l, emptyList()))

    private fun asExpr(lv: LV, ctx: Ctx): Expr = when (lv) {
        is LV.E -> lv.expr
        LV.Null -> fail(ctx, "null is only translatable in == / != comparisons")
        is LV.Ctor -> fail(ctx, "objects can only be constructed as the result of select")
        is LV.Obj -> objExpr(lv, ctx)
        is LV.Coll -> fail(ctx, "a collection literal is only translatable as the receiver of contains, size, get, first, last or isEmpty")
        is LV.Rx, is LV.Matcher -> fail(ctx, "a regular expression is only translatable in matches, containsMatchIn, find or matchEntire")
    }

    /** How kotlinx.serialization stores [e]: its `@SerialName`, else its name. */
    private fun serialName(e: Enum<*>): String =
        e.declaringJavaClass.getField(e.name).getAnnotation(kotlinx.serialization.SerialName::class.java)?.value ?: e.name

    /** A JVM value as a parameter (captured) or literal (constant). */
    private fun objExpr(o: LV.Obj, ctx: Ctx): Expr {
        val v = o.value
        if (v is QueryVariable) {
            if (v.wildcard) fail(ctx, "the wildcard has no value")
            return Expr.Var(entityVars[v] ?: fail(ctx, "variable $v belongs to another query"))
        }
        val (type, value) = when (v) {
            is EntityId -> ValueType.UUID to v.id
            is Enum<*> -> ValueType.TEXT to serialName(v)
            is Char -> ValueType.TEXT to v.toString()
            is String -> ValueType.TEXT to v
            is Boolean -> ValueType.BOOL to v
            is Int, is Long, is Short, is Byte -> ValueType.INT to (v as Number).toLong()
            is Double, is Float -> ValueType.FLOAT to (v as Number).toDouble()
            is UUID -> ValueType.UUID to v
            is Instant -> ValueType.INSTANT to v
            is Duration -> ValueType.DURATION to v
            else -> fail(ctx, "a ${v.javaClass.name} cannot be used as a query value")
        }
        return if (o.captured) param(type, value) else Expr.Literal(value, type)
    }

    // ---- calls ----

    private fun call(c: LExpr.Invoke, ctx: Ctx): LV {
        val recv = c.receiver?.let { lower(it, ctx) }
        val args = c.args.map { lower(it, ctx) }
        val r = recv as? LV.Obj

        // Reading a delegated local (`val acc by entity`) from a lambda calls a synthetic accessor
        // with the captured delegate.
        if (recv == null && args.size == 1 && (args[0] as? LV.Obj)?.value is VariableDelegate) {
            return LV.Obj(((args[0] as LV.Obj).value as VariableDelegate).variable, captured = true)
        }

        // The builder's markers: mail[EmailComponent], get(mail, EMAIL), now()
        if (r != null && r.value is QueryBuilder) {
            return when (c.name) {
                "get", "getInfix" -> {
                    val entity = (args.getOrNull(0) as? LV.Obj)?.value as? EntityId
                        ?: fail(ctx, "get needs an entity variable")
                    val component = (args.getOrNull(1) as? LV.Obj)?.value as? Component<*>
                        ?: fail(ctx, "get needs a component handle")
                    valueOf(lookup(entity, component) ?: bind(b, entity, component, optional = false))
                }
                "now" -> LV.E(Functions.call(Functions.NOW))
                else -> fail(ctx, "${c.name} is not a query function")
            }
        }
        if (c.owner == "kotlin/TuplesKt" && c.name == "to" && args.size == 2) {
            return LV.Ctor({ Pair(it[0], it[1]) }, args, c.args.map { it.type }, "kotlin/Pair")
        }
        // acc.id / p.id: an entity's UUID
        if (r != null && r.value is EntityId && c.name == "getId" && args.isEmpty()) {
            return LV.E(objExpr(r, ctx))
        }
        if (recv is LV.E && args.isEmpty() && recv.descriptor != null && SerialTypes.isObject(recv.descriptor)) {
            return field(recv, c, ctx)
        }
        collection(c, recv, args, ctx)?.let { return it }
        regex(c, recv, args, ctx)?.let { return it }
        text(c, recv, args, ctx)?.let { return it }
        time(c, recv, args, ctx)?.let { return it }
        fail(ctx, "call to ${c.qualifiedName} is not translatable")
    }

    private fun propertyName(getter: String): String = when {
        getter.startsWith("get") && getter.length > 3 && getter[3].isUpperCase() ->
            getter[3].lowercaseChar() + getter.substring(4)
        else -> getter // isVerified, Java record accessors
    }

    private fun field(recv: LV.E, c: LExpr.Invoke, ctx: Ctx): LV {
        val desc = recv.descriptor!!
        val prop = propertyName(c.name)
        val ownerClass = loadClass(c.owner, recv.loader)
        var index = desc.getElementIndex(prop)
        if (index == CompositeDecoder.UNKNOWN_NAME) {
            val serialName = ownerClass?.let { serialNameOf(it, c.name, prop) }
            if (serialName != null) index = desc.getElementIndex(serialName)
        }
        if (index == CompositeDecoder.UNKNOWN_NAME) {
            fail(ctx, "${desc.serialName} has no serialized property '$prop'")
        }
        val key = desc.getElementName(index)
        val elem = desc.getElementDescriptor(index)
        var type = SerialTypes.valueType(elem)
        if (recv.expr.type.nullable) type = type.asNullable()
        val expr = when (val base = recv.expr) {
            is Expr.Var -> Expr.Field(base.variable as? ValueVar ?: fail(ctx, "an entity has no fields"), listOf(key), type)
            is Expr.Field -> Expr.Field(base.variable, base.path + key, type)
            else -> fail(ctx, "fields are only translatable on component values")
        }
        val javaType: Type? = ownerClass?.let { cls -> runCatching { cls.getMethod(c.name).genericReturnType }.getOrNull() }
        val serializer = javaType?.let { serializerOrNull(it) }
        return LV.E(expr, elem, serializer, recv.loader, maybeNull = recv.maybeNull || type.nullable)
    }

    /** `@SerialName` on a Kotlin property lives on its synthetic `get<Name>$annotations` method. */
    private fun serialNameOf(cls: Class<*>, getter: String, prop: String): String? {
        val candidates = listOf("$getter\$annotations", "$prop\$annotations")
        for (m in cls.declaredMethods) {
            if (m.name in candidates) m.getAnnotation(SerialName::class.java)?.let { return it.value }
        }
        return cls.declaredFields.firstOrNull { it.name == prop }?.getAnnotation(SerialName::class.java)?.value
    }

    private fun loadClass(internalName: String, loader: ClassLoader?): Class<*>? = runCatching {
        Class.forName(internalName.replace('/', '.'), false, loader ?: Thread.currentThread().contextClassLoader)
    }.getOrNull()

    private fun textArg(lv: LV, ctx: Ctx): Expr? = when (lv) {
        is LV.E -> lv.expr.takeIf { it.type.kind == ValueType.Kind.TEXT }
        is LV.Obj -> if (lv.value is String || lv.value is Char) objExpr(lv, ctx) else null
        else -> null
    }

    /** A `Char` argument (`contains(' ')`): the compiler passes a constant as an int, a captured one as a Char. */
    private fun charArg(lv: LV, ctx: Ctx): Expr? = when {
        lv is LV.E && lv.expr is Expr.Literal && (lv.expr as Expr.Literal).value is Number ->
            Expr.Literal(((lv.expr as Expr.Literal).value as Number).toInt().toChar().toString(), ValueType.TEXT)
        lv is LV.Obj && lv.value is Number && !lv.captured -> Expr.Literal((lv.value as Number).toInt().toChar().toString(), ValueType.TEXT)
        lv is LV.Obj && lv.value is Char -> objExpr(lv, ctx)
        else -> textArg(lv, ctx)
    }

    private fun isFalse(lv: LV): Boolean =
        lv is LV.E && lv.expr == Expr.Literal(false, ValueType.BOOL)

    /** Whitelisted String functions (Java methods and Kotlin's StringsKt). */
    private fun text(c: LExpr.Invoke, recv: LV?, args: List<LV>, ctx: Ctx): LV? {
        if (c.owner == "java/lang/String" && recv != null) {
            val s = textArg(recv, ctx) ?: return null
            return LV.E(
                when (c.name) {
                    "toLowerCase" -> Functions.call(Functions.LOWERCASE, s)
                    "toUpperCase" -> Functions.call(Functions.UPPERCASE, s)
                    "trim", "strip" -> Functions.call(Functions.TRIM, s)
                    "length" -> Functions.call(Functions.LENGTH, s)
                    "isEmpty" -> Expr.Compare(CompareOp.EQ, Functions.call(Functions.LENGTH, s), Expr.Literal(0L, ValueType.INT))
                    "toString", "intern" -> s
                    "startsWith" -> if (args.size == 1) Functions.call(Functions.STARTS_WITH, s, textArg(args[0], ctx) ?: return null) else return null
                    "endsWith" -> Functions.call(Functions.ENDS_WITH, s, textArg(args[0], ctx) ?: return null)
                    "contains" -> Functions.call(Functions.CONTAINS, s, textArg(args[0], ctx) ?: return null)
                    else -> return null
                },
            )
        }
        if (c.owner == "java/lang/Object" && c.name == "toString" && recv != null) {
            return textArg(recv, ctx)?.let { LV.E(it) }
        }
        if (c.owner == "kotlin/text/StringsKt" && recv == null && args.isNotEmpty()) {
            val s = textArg(args[0], ctx) ?: return null
            val fn = when (c.name) {
                "trim" -> return LV.E(Functions.call(Functions.TRIM, s))
                "startsWith", "startsWith\$default" -> Functions.STARTS_WITH
                "endsWith", "endsWith\$default" -> Functions.ENDS_WITH
                "contains", "contains\$default" -> Functions.CONTAINS
                else -> return null
            }
            // (s, other, ignoreCase[, mask, marker]): only the case-sensitive form translates
            if (args.size < 3 || !isFalse(args[2])) fail(ctx, "${c.name.removeSuffix("\$default")} with ignoreCase is not translatable")
            val other = (if (c.descriptor.startsWith("(Ljava/lang/CharSequence;C")) charArg(args[1], ctx) else textArg(args[1], ctx))
                ?: return null
            return LV.E(Functions.call(fn, s, other))
        }
        return null
    }

    // ---- collections ----

    private enum class CollOp { CONTAINS, CONTAINS_ALL, SIZE, IS_EMPTY, GET, GET_OR_NULL, FIRST, FIRST_OR_NULL, LAST, LAST_OR_NULL }

    private val collectionOwners = setOf(
        "java/util/List", "java/util/Set", "java/util/Collection", "java/util/ArrayList", "java/util/LinkedHashSet",
        "java/util/HashSet", "java/lang/Iterable",
    )

    /**
     * `contains`, `size`, `isEmpty`, `get`, `first`, `last` (and the `OrNull` forms), `containsAll`
     * on a collection literal, a captured collection or array, or a `List`/`Set` field of a component.
     */
    private fun collection(c: LExpr.Invoke, recv: LV?, args: List<LV>, ctx: Ctx): LV? {
        val (op, target, rest) = when {
            recv != null && c.owner in collectionOwners -> when (c.name) {
                "contains" -> CollOp.CONTAINS
                "containsAll" -> CollOp.CONTAINS_ALL
                "size" -> CollOp.SIZE
                "isEmpty" -> CollOp.IS_EMPTY
                "get" -> CollOp.GET
                else -> return null
            }.let { Triple(it, recv, args) }
            recv == null && args.isNotEmpty() && (c.owner == "kotlin/collections/CollectionsKt" || c.owner == "kotlin/collections/ArraysKt") ->
                when (c.name) {
                    "contains" -> CollOp.CONTAINS
                    "first" -> CollOp.FIRST
                    "firstOrNull" -> CollOp.FIRST_OR_NULL
                    "last" -> CollOp.LAST
                    "lastOrNull" -> CollOp.LAST_OR_NULL
                    "getOrNull" -> CollOp.GET_OR_NULL
                    "elementAt" -> CollOp.GET
                    else -> return null
                }.let { Triple(it, args[0], args.drop(1)) }
            else -> return null
        }
        return when (target) {
            is LV.Coll -> literalCollection(op, target.elements, rest, ctx)
            is LV.Obj -> {
                val items = jvmItems(target.value) ?: return null
                capturedCollection(op, items, target.captured, rest, ctx)
            }
            is LV.E -> {
                val d = target.descriptor
                if (target.expr.type.kind != ValueType.Kind.JSON || d == null || d.kind != StructureKind.LIST) {
                    fail(ctx, "${c.name} is only translatable on List and Set fields, collection literals and captured collections")
                }
                jsonList(op, target, d.getElementDescriptor(0), rest, ctx)
            }
            else -> null
        }
    }

    private fun jvmItems(v: Any): List<Any?>? = when (v) {
        is Collection<*> -> v.toList()
        is Array<*> -> v.toList()
        else -> if (v.javaClass.isArray) List(java.lang.reflect.Array.getLength(v)) { java.lang.reflect.Array.get(v, it) } else null
    }

    private fun constIndex(lv: LV, ctx: Ctx): Int? = when {
        lv is LV.Obj && !lv.captured && lv.value is Number -> (lv.value as Number).toInt()
        lv is LV.E && lv.expr is Expr.Literal && (lv.expr as Expr.Literal).value is Long -> ((lv.expr as Expr.Literal).value as Long).toInt()
        else -> null
    }

    private fun bool(v: Boolean, captured: Boolean): LV.E = LV.E(if (captured) param(ValueType.BOOL, v) else Expr.Literal(v, ValueType.BOOL))

    /** Kotlin's `contains` over the literal's elements: `x == a || x == b`. */
    private fun literalCollection(op: CollOp, elements: List<LV>, rest: List<LV>, ctx: Ctx): LV {
        fun at(i: Int, orNull: Boolean): LV = elements.getOrNull(i)
            ?: if (orNull) LV.Null else fail(ctx, "index $i is out of range of a collection literal of ${elements.size}")
        return when (op) {
            CollOp.CONTAINS -> LV.E(
                elements.map { asBool(compare(CmpOp.EQ, rest[0], it, ctx), ctx) }
                    .fold(Expr.Literal(false, ValueType.BOOL) as Expr) { acc, e -> if (acc == Expr.Literal(false, ValueType.BOOL)) e else or(acc, e) },
            )
            CollOp.CONTAINS_ALL -> {
                val other = rest[0] as? LV.Coll ?: fail(ctx, "containsAll of a collection literal needs a collection literal")
                LV.E(other.elements.map { asBool(literalCollection(CollOp.CONTAINS, elements, listOf(it), ctx), ctx) }
                    .fold(Expr.Literal(true, ValueType.BOOL) as Expr) { acc, e -> if (acc == Expr.Literal(true, ValueType.BOOL)) e else and(acc, e) })
            }
            CollOp.SIZE -> LV.E(Expr.Literal(elements.size.toLong(), ValueType.INT))
            CollOp.IS_EMPTY -> LV.E(Expr.Literal(elements.isEmpty(), ValueType.BOOL))
            CollOp.GET, CollOp.GET_OR_NULL -> at(constIndex(rest[0], ctx) ?: fail(ctx, "get on a collection literal needs a constant index"), op == CollOp.GET_OR_NULL)
            CollOp.FIRST, CollOp.FIRST_OR_NULL -> at(0, op == CollOp.FIRST_OR_NULL)
            CollOp.LAST, CollOp.LAST_OR_NULL -> at(elements.size - 1, op == CollOp.LAST_OR_NULL)
        }
    }

    /** A captured collection: `x in names` is `x = any(?)` with the elements as one list parameter. */
    private fun capturedCollection(op: CollOp, items: List<Any?>, captured: Boolean, rest: List<LV>, ctx: Ctx): LV {
        fun at(i: Int, orNull: Boolean): LV = when {
            i in items.indices -> items[i]?.let { LV.Obj(it, captured) } ?: LV.Null
            orNull -> LV.Null
            else -> fail(ctx, "index $i is out of range of a captured collection of ${items.size}")
        }
        return when (op) {
            CollOp.CONTAINS -> when (val x = rest[0]) {
                is LV.Obj -> if (x.value is QueryVariable) {
                    capturedCollection(op, items, captured, listOf(LV.E(objExpr(x, ctx))), ctx)
                } else bool(items.contains(x.value), captured || x.captured)
                LV.Null -> bool(items.contains(null), captured)
                else -> {
                    val value = asExpr(x, ctx)
                    val kind = value.type.kind
                    if (kind == ValueType.Kind.JSON) fail(ctx, "contains of objects or lists is not translatable")
                    val normalized = items.filterNotNull().map { normalizeItem(it, kind, ctx) }
                    val test = Functions.callTyped(Functions.IN, ValueType.BOOL, value, param(ValueType.listOf(kind), normalized))
                    if (items.contains(null) && value.type.nullable) LV.E(or(Expr.IsNull(value), test)) else LV.E(test)
                }
            }
            CollOp.CONTAINS_ALL -> when (val other = rest[0]) {
                is LV.Coll -> LV.E(other.elements.map { asBool(capturedCollection(CollOp.CONTAINS, items, captured, listOf(it), ctx), ctx) }
                    .fold(Expr.Literal(true, ValueType.BOOL) as Expr) { acc, e -> if (acc == Expr.Literal(true, ValueType.BOOL)) e else and(acc, e) })
                is LV.Obj -> bool(items.containsAll(jvmItems(other.value) ?: fail(ctx, "containsAll needs a collection")), captured || other.captured)
                else -> fail(ctx, "containsAll of a captured collection needs a collection literal or a captured collection")
            }
            CollOp.SIZE -> LV.E(if (captured) param(ValueType.INT, items.size.toLong()) else Expr.Literal(items.size.toLong(), ValueType.INT))
            CollOp.IS_EMPTY -> bool(items.isEmpty(), captured)
            CollOp.GET, CollOp.GET_OR_NULL -> at(constIndex(rest[0], ctx) ?: fail(ctx, "get on a captured collection needs a constant index"), op == CollOp.GET_OR_NULL)
            CollOp.FIRST, CollOp.FIRST_OR_NULL -> at(0, op == CollOp.FIRST_OR_NULL)
            CollOp.LAST, CollOp.LAST_OR_NULL -> at(items.size - 1, op == CollOp.LAST_OR_NULL)
        }
    }

    /** An element of a captured collection as the IR value of [kind] (what a parameter of that kind holds). */
    private fun normalizeItem(v: Any, kind: ValueType.Kind, ctx: Ctx): Any = when (kind) {
        ValueType.Kind.TEXT -> when (v) {
            is String -> v
            is Char -> v.toString()
            is Enum<*> -> serialName(v)
            else -> null
        }
        ValueType.Kind.INT -> (v as? Number)?.takeIf { it is Long || it is Int || it is Short || it is Byte }?.toLong()
        ValueType.Kind.FLOAT -> (v as? Number)?.toDouble()
        ValueType.Kind.BOOL -> v as? Boolean
        ValueType.Kind.UUID -> when (v) {
            is EntityId -> if (v is QueryVariable) null else v.id
            is UUID -> v
            else -> null
        }
        ValueType.Kind.INSTANT -> v as? Instant
        ValueType.Kind.DURATION -> v as? Duration
        ValueType.Kind.JSON -> null
    } ?: fail(ctx, "a ${v.javaClass.name} in the collection cannot be compared with a $kind value")

    /** A `List`/`Set` field: a JSON array in the component value. */
    private fun jsonList(op: CollOp, list: LV.E, element: SerialDescriptor, rest: List<LV>, ctx: Ctx): LV {
        val elementType = SerialTypes.valueType(element)
        val listExpr = list.expr
        fun nonNull(e: Expr, default: Expr): Expr = if (listExpr.type.nullable) e else Expr.Coalesce(listOf(e, default))
        val size = nonNull(Functions.call(Functions.JSON_SIZE, listExpr), Expr.Literal(0L, ValueType.INT))
        fun get(index: Expr, orNull: Boolean): LV {
            val t = if (orNull) elementType.asNullable() else elementType
            val e = Functions.callTyped(Functions.JSON_GET, t, listExpr, index)
            return LV.E(e, descriptor = element, loader = list.loader, maybeNull = true)
        }
        return when (op) {
            CollOp.CONTAINS -> {
                if (elementType.kind == ValueType.Kind.JSON) fail(ctx, "contains on a list of objects or lists is not translatable")
                val x = rest[0]
                val value = if (x == LV.Null) fail(ctx, "contains(null) on a list field is not translatable") else asExpr(x, ctx)
                if (value.type.kind == ValueType.Kind.JSON) fail(ctx, "contains of objects or lists is not translatable")
                LV.E(nonNull(Functions.call(Functions.JSON_CONTAINS, listExpr, value), Expr.Literal(false, ValueType.BOOL)))
            }
            CollOp.CONTAINS_ALL -> {
                val elems = when (val other = rest[0]) {
                    is LV.Coll -> other.elements
                    is LV.Obj -> (jvmItems(other.value) ?: fail(ctx, "containsAll needs a collection")).map { if (it == null) LV.Null else LV.Obj(it, other.captured) }
                    else -> fail(ctx, "containsAll on a list field needs a collection literal or a captured collection")
                }
                LV.E(elems.map { asBool(jsonList(CollOp.CONTAINS, list, element, listOf(it), ctx), ctx) }
                    .fold(Expr.Literal(true, ValueType.BOOL) as Expr) { acc, e -> if (acc == Expr.Literal(true, ValueType.BOOL)) e else and(acc, e) })
            }
            CollOp.SIZE -> LV.E(size)
            CollOp.IS_EMPTY -> LV.E(Expr.Compare(CompareOp.EQ, size, Expr.Literal(0L, ValueType.INT)))
            CollOp.GET, CollOp.GET_OR_NULL -> get(asExpr(rest[0], ctx).also {
                if (it.type.kind != ValueType.Kind.INT) fail(ctx, "a list index must be an integer")
            }, op == CollOp.GET_OR_NULL)
            CollOp.FIRST, CollOp.FIRST_OR_NULL -> get(Expr.Literal(0L, ValueType.INT), op == CollOp.FIRST_OR_NULL)
            CollOp.LAST, CollOp.LAST_OR_NULL ->
                get(Expr.Arith(ArithOp.MINUS, size, Expr.Literal(1L, ValueType.INT)), op == CollOp.LAST_OR_NULL)
        }
    }

    // ---- regular expressions ----

    /** How much of the text a regex call must match. */
    private enum class RxMode { FULL, PREFIX, FIND }

    /** `Regex(pattern)` and `Regex(pattern, RegexOption.IGNORE_CASE)`; `"...".toRegex()` compiles to the same. */
    private fun newRegex(e: LExpr.New, ctx: Ctx): LV {
        val args = e.args.map { lower(it, ctx) }
        return when (e.descriptor) {
            "(Ljava/lang/String;)V" -> LV.Rx(args[0], ignoreCase = false)
            "(Ljava/lang/String;Lkotlin/text/RegexOption;)V" -> {
                val option = (args[1] as? LV.Obj)?.value as? RegexOption
                    ?: fail(ctx, "the regex option must be a constant RegexOption")
                LV.Rx(args[0], ignoreCase = regexIgnoreCase(setOf(option), ctx))
            }
            else -> fail(ctx, "Regex${e.descriptor} is not translatable")
        }
    }

    private fun regexIgnoreCase(options: Set<RegexOption>, ctx: Ctx): Boolean {
        val other = options - RegexOption.IGNORE_CASE
        if (other.isNotEmpty()) fail(ctx, "regex options $other are not translatable (only IGNORE_CASE)")
        return RegexOption.IGNORE_CASE in options
    }

    /** [lv] as a regex: a [LV.Rx], or a captured or constant `Regex` / `Pattern`. */
    private fun asRegex(lv: LV?, ctx: Ctx): LV.Rx? = when {
        lv is LV.Rx -> lv
        lv is LV.Obj && lv.value is Regex -> LV.Rx(LV.Obj((lv.value as Regex).pattern, lv.captured), regexIgnoreCase((lv.value as Regex).options, ctx))
        lv is LV.Obj && lv.value is java.util.regex.Pattern -> {
            val p = lv.value as java.util.regex.Pattern
            if (p.flags() and java.util.regex.Pattern.CASE_INSENSITIVE.inv() != 0) fail(ctx, "Pattern flags other than CASE_INSENSITIVE are not translatable")
            LV.Rx(LV.Obj(p.pattern(), lv.captured), p.flags() and java.util.regex.Pattern.CASE_INSENSITIVE != 0)
        }
        else -> null
    }

    /**
     * Regex calls, with PostgreSQL's regular expressions (ARE): `Regex.matches` / `String.matches` /
     * `Matcher.matches` match the whole text, `containsMatchIn` / `Matcher.find` anywhere,
     * `Matcher.lookingAt` at the start. The pattern is passed to PostgreSQL as written.
     */
    private fun regex(c: LExpr.Invoke, recv: LV?, args: List<LV>, ctx: Ctx): LV? {
        when (c.owner) {
            "kotlin/text/Regex" -> {
                val rx = asRegex(recv, ctx) ?: return null
                return when (c.name) {
                    "matches" -> regexTest(args[0], rx, RxMode.FULL, ctx)
                    "containsMatchIn" -> regexTest(args[0], rx, RxMode.FIND, ctx)
                    else -> fail(ctx, "Regex.${c.name} is not translatable (matches, containsMatchIn, find(..) != null, matchEntire(..) != null are)")
                }
            }
            "java/util/regex/Pattern" -> {
                if (recv == null) {
                    return when (c.name) {
                        "compile" -> {
                            val flags = if (args.size == 2) constIndex(args[1], ctx) ?: fail(ctx, "Pattern flags must be constant") else 0
                            if (flags and java.util.regex.Pattern.CASE_INSENSITIVE.inv() != 0) fail(ctx, "Pattern flags other than CASE_INSENSITIVE are not translatable")
                            LV.Rx(args[0], flags and java.util.regex.Pattern.CASE_INSENSITIVE != 0)
                        }
                        "matches" -> regexTest(args[1], LV.Rx(args[0], false), RxMode.FULL, ctx)
                        else -> null
                    }
                }
                val rx = asRegex(recv, ctx) ?: return null
                return if (c.name == "matcher") LV.Matcher(rx, args[0]) else null
            }
            "java/util/regex/Matcher" -> {
                val m = recv as? LV.Matcher ?: fail(ctx, "a Matcher is only translatable straight from pattern.matcher(text)")
                return when (c.name) {
                    "matches" -> regexTest(m.subject, m.regex, RxMode.FULL, ctx)
                    "find" -> if (args.isEmpty()) regexTest(m.subject, m.regex, RxMode.FIND, ctx) else null
                    "lookingAt" -> regexTest(m.subject, m.regex, RxMode.PREFIX, ctx)
                    else -> null
                }
            }
            "java/lang/String" -> if (c.name == "matches" && recv != null && args.size == 1) {
                return regexTest(recv, LV.Rx(args[0], false), RxMode.FULL, ctx)
            }
        }
        return null
    }

    /** `rx.find(text) != null` (any match) and `rx.matchEntire(text) != null` (whole text). */
    private fun regexFound(e: LExpr.Compare, ctx: Ctx): LV? {
        if (e.op != CmpOp.EQ && e.op != CmpOp.NE) return null
        val (call, other) = when {
            e.right is LExpr.Const && (e.right as LExpr.Const).value == null -> e.left to e.right
            e.left is LExpr.Const && (e.left as LExpr.Const).value == null -> e.right to e.left
            else -> return null
        }
        if (call !is LExpr.Invoke || call.owner != "kotlin/text/Regex" || other !is LExpr.Const) return null
        val mode = when (call.name) {
            "find", "find\$default" -> RxMode.FIND
            "matchEntire" -> RxMode.FULL
            else -> return null
        }
        // find(text, startIndex = 0): a static `$default` bridge takes the regex first
        val (rxArg, rest) = call.receiver?.let { it to call.args } ?: (call.args[0] to call.args.drop(1))
        if (mode == RxMode.FIND && rest.size >= 2) {
            val start = lower(rest[1], ctx)
            if (constIndex(start, ctx) != 0) fail(ctx, "Regex.find with a start index is not translatable")
        }
        val rx = asRegex(lower(rxArg, ctx), ctx) ?: return null
        val found = asBool(regexTest(lower(rest[0], ctx), rx, mode, ctx), ctx)
        return LV.E(if (e.op == CmpOp.NE) found else Expr.Not(found))
    }

    private fun regexTest(subject: LV, rx: LV.Rx, mode: RxMode, ctx: Ctx): LV {
        val text = textArg(subject, ctx) ?: fail(ctx, "a regex matches text only")
        return LV.E(Functions.call(Functions.REGEX, text, regexPattern(rx, mode, ctx)))
    }

    /**
     * The PostgreSQL pattern for [rx] in [mode]: anchored as `^(?:p)$` for a full match, with the
     * case-insensitive option `(?i)` in front. A known pattern (a constant or captured string) keeps
     * its own leading embedded options (`(?i)...`) in front, where PostgreSQL requires them.
     */
    private fun regexPattern(rx: LV.Rx, mode: RxMode, ctx: Ctx): Expr {
        val p = rx.pattern
        val known = when {
            p is LV.Obj && p.value is String -> p.value as String
            p is LV.E && p.expr is Expr.Literal && (p.expr as Expr.Literal).type.kind == ValueType.Kind.TEXT -> (p.expr as Expr.Literal).value as String
            else -> null
        }
        if (known != null) {
            val options = EMBEDDED_OPTIONS.find(known)
            var flags = options?.groupValues?.get(1).orEmpty()
            val core = if (options != null) known.substring(options.range.last + 1) else known
            if (rx.ignoreCase && 'i' !in flags) flags += "i"
            val anchored = when (mode) {
                RxMode.FULL -> "^(?:$core)$"
                RxMode.PREFIX -> "^(?:$core)"
                RxMode.FIND -> core
            }
            val full = (if (flags.isEmpty()) "" else "(?$flags)") + anchored
            return if (p is LV.Obj && p.captured) param(ValueType.TEXT, full) else Expr.Literal(full, ValueType.TEXT)
        }
        val e = textArg(p, ctx) ?: fail(ctx, "a regex pattern must be text")
        if (e.type.nullable) fail(ctx, "a regex pattern that may be null is not translatable")
        fun lit(s: String) = Expr.Literal(s, ValueType.TEXT)
        fun cat(vararg parts: Expr) = parts.reduce { a, b -> Functions.call(Functions.CONCAT, a, b) }
        val anchored = when (mode) {
            RxMode.FULL -> cat(lit("^(?:"), e, lit(")$"))
            RxMode.PREFIX -> cat(lit("^(?:"), e, lit(")"))
            RxMode.FIND -> e
        }
        return if (rx.ignoreCase) cat(lit("(?i)"), anchored) else anchored
    }

    /** Whitelisted Instant methods. */
    private fun time(c: LExpr.Invoke, recv: LV?, args: List<LV>, ctx: Ctx): LV? {
        if (c.owner != "java/time/Instant" || recv == null) return null
        val t = asExpr(recv, ctx)
        return when (c.name) {
            "isBefore" -> LV.E(Expr.Compare(CompareOp.LT, t, asExpr(args[0], ctx)))
            "isAfter" -> LV.E(Expr.Compare(CompareOp.GT, t, asExpr(args[0], ctx)))
            "plus", "minus" -> if (args.size == 1) {
                LV.E(Expr.Arith(if (c.name == "plus") ArithOp.PLUS else ArithOp.MINUS, t, asExpr(args[0], ctx)))
            } else null
            else -> null
        }
    }

    // ---- select and result rows ----

    private fun lowerSelect(): RowShape = when (val s = q.selection ?: throw QueryBuildException("the query has no select")) {
        is DSelect.Lambda -> {
            val lv = lowerLambda(s.projection, emptyList())
            shapeOf(lv, s.projection.body.type, Ctx(s.projection, emptyList()))
        }
        is DSelect.Columns -> {
            val ctx = Ctx(null, emptyList())
            val parts = s.columns.map { col ->
                when (col) {
                    is EntityId -> shapeOf(LV.Obj(col, captured = true), "Ldev/vxcc/alexs168/pgecs/EntityId;", ctx)
                    is ValueRef<*> -> {
                        val binding = lookup(col.entity, col.component) ?: bind(b, col.entity, col.component, optional = false)
                        shapeOf(valueOf(binding), "Ljava/lang/Object;", ctx)
                    }
                    else -> throw QueryBuildException("cannot select $col")
                }
            }
            if (s.single) parts.single() else RowShape.Construct({ it.toList() }, parts)
        }
    }

    private fun shapeOf(lv: LV, jvmType: String, ctx: Ctx): RowShape = when (lv) {
        is LV.Ctor -> RowShape.Construct(lv.build, lv.args.mapIndexed { i, a -> shapeOf(a, lv.argTypes[i], ctx) })
        LV.Null -> RowShape.Constant(null)
        is LV.Obj -> {
            val v = lv.value
            if (v is EntityId) column(LV.E(objExpr(lv, ctx)), jvmType)
            else RowShape.Constant(v) // a captured value selected as is
        }
        is LV.E -> column(lv, jvmType)
        is LV.Coll, is LV.Rx, is LV.Matcher -> column(LV.E(asExpr(lv, ctx)), jvmType)
    }

    private fun column(lv: LV.E, jvmType: String): RowShape {
        val e = lv.expr
        val name = uniqueName(
            when (e) {
                is Expr.Var -> e.variable.name
                is Expr.Field -> e.variable.name + "_" + e.path.joinToString("_")
                else -> "col"
            },
            columnNames,
        )
        b.select(name, e)
        return RowShape.Column(columnCount++, decoder(lv, jvmType))
    }

    private var columnCount = 0

    private fun decoder(lv: LV.E, jvmType: String): (Any?) -> Any? {
        val e = lv.expr
        val serializer = lv.serializer
        val cls = if (jvmType.startsWith("L")) loadClass(jvmType.substring(1, jvmType.length - 1), lv.loader) else null
        return when (e.type.kind) {
            ValueType.Kind.UUID -> if (e is Expr.Var && e.variable is EntityVar || jvmType == ENTITY_ID) {
                { raw -> raw?.let { EntityId(toUuid(it)) } }
            } else {
                { raw -> raw?.let(::toUuid) }
            }
            ValueType.Kind.JSON -> { raw ->
                when {
                    raw == null -> null
                    serializer != null -> PgecsJson.decodeFromString(serializer, raw as String)
                    else -> PgecsJson.parseToJsonElement(raw as String)
                }
            }
            ValueType.Kind.INT -> when (jvmType) {
                "I", "Ljava/lang/Integer;" -> { raw -> (raw as Number?)?.toInt() }
                "S", "Ljava/lang/Short;" -> { raw -> (raw as Number?)?.toShort() }
                "B", "Ljava/lang/Byte;" -> { raw -> (raw as Number?)?.toByte() }
                else -> { raw -> (raw as Number?)?.toLong() }
            }
            ValueType.Kind.FLOAT -> when (jvmType) {
                "F", "Ljava/lang/Float;" -> { raw -> (raw as Number?)?.toFloat() }
                else -> { raw -> (raw as Number?)?.toDouble() }
            }
            ValueType.Kind.TEXT -> when {
                jvmType == "C" || jvmType == "Ljava/lang/Character;" -> { raw -> (raw as String?)?.single() }
                cls != null && cls.isEnum && serializer != null -> { raw ->
                    raw?.let { PgecsJson.decodeFromJsonElement(serializer, JsonPrimitive(it as String)) }
                }
                else -> { raw -> raw }
            }
            else -> { raw -> raw }
        }
    }

    private fun constructorOf(owner: String, descriptor: String, ctx: Ctx): (List<Any?>) -> Any? {
        if (owner == "kotlin/Pair") return { Pair(it[0], it[1]) }
        if (owner == "kotlin/Triple") return { Triple(it[0], it[1], it[2]) }
        val cls = loadClass(owner, null) ?: fail(ctx, "class ${owner.replace('/', '.')} not found")
        val mt = java.lang.invoke.MethodType.fromMethodDescriptorString(descriptor, cls.classLoader)
        val ctor = runCatching { cls.getDeclaredConstructor(*mt.parameterArray()) }.getOrNull()
            ?: fail(ctx, "constructor ${cls.name}$descriptor not found")
        ctor.trySetAccessible()
        return { ctor.newInstance(*it.toTypedArray()) }
    }

    private companion object {
        const val ENTITY_ID = "Ldev/vxcc/alexs168/pgecs/EntityId;"

        /** Embedded options at the start of a PostgreSQL ARE, `(?i)`. */
        val EMBEDDED_OPTIONS = Regex("^\\(\\?([bceimnpqstwx]+)\\)")

        fun toUuid(raw: Any): UUID = raw as? UUID ?: UUID.fromString(raw.toString())
    }
}

/** How one result row becomes the query's result type. */
internal sealed interface RowShape {
    fun build(row: Array<Any?>): Any?

    class Column(private val index: Int, private val decode: (Any?) -> Any?) : RowShape {
        override fun build(row: Array<Any?>): Any? = decode(row[index])
    }

    class Construct(private val factory: (List<Any?>) -> Any?, private val parts: List<RowShape>) : RowShape {
        override fun build(row: Array<Any?>): Any? = factory(parts.map { it.build(row) })
    }

    class Constant(private val value: Any?) : RowShape {
        override fun build(row: Array<Any?>): Any? = value
    }
}
