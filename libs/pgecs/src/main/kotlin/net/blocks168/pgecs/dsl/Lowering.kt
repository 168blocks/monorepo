package net.blocks168.pgecs.dsl

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializerOrNull
import net.blocks168.pgecs.Capability
import net.blocks168.pgecs.Component
import net.blocks168.pgecs.ComponentType
import net.blocks168.pgecs.EntityId
import net.blocks168.pgecs.Relation
import net.blocks168.pgecs.Tag
import net.blocks168.pgecs.ir.AnyEntity
import net.blocks168.pgecs.ir.ArithOp
import net.blocks168.pgecs.ir.CompareOp
import net.blocks168.pgecs.ir.EntityRef
import net.blocks168.pgecs.ir.EntityVar
import net.blocks168.pgecs.ir.Expr
import net.blocks168.pgecs.ir.Functions
import net.blocks168.pgecs.ir.IrBuilder
import net.blocks168.pgecs.ir.TermsBuilder
import net.blocks168.pgecs.ir.ValueType
import net.blocks168.pgecs.ir.ValueVar
import net.blocks168.pgecs.ir.and
import net.blocks168.pgecs.ir.or
import net.blocks168.pgecs.lambda.BinOp
import net.blocks168.pgecs.lambda.CmpOp
import net.blocks168.pgecs.lambda.LExpr
import net.blocks168.pgecs.lambda.LambdaInstance
import net.blocks168.pgecs.lambda.render
import net.blocks168.pgecs.serial.PgecsJson
import net.blocks168.pgecs.serial.SerialTypes

/**
 * Lowers what a [QueryBuilder] recorded into the first-level IR (`net.blocks168.pgecs.ir`), and
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
        data class Ctor(val build: (List<Any?>) -> Any?, val args: List<LV>, val argTypes: List<String>) : LV
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
        is LExpr.New -> {
            val owner = e.owner
            LV.Ctor(constructorOf(owner, e.descriptor, ctx), e.args.map { lower(it, ctx) }, e.args.map { it.type })
        }
        is LExpr.Compare -> compare(e.op, lower(e.left, ctx), lower(e.right, ctx), ctx)
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
        is LExpr.Concat -> fail(ctx, "string concatenation is not translatable")
        is LExpr.GetField -> fail(ctx, "field ${e.name} is read directly; only getters are translatable")
    }

    /** `x ?: d` arrives as `if (x == null) d else x`; anything else is not translatable. */
    private fun conditional(e: LExpr.Cond, ctx: Ctx): LV {
        val c = asBool(lower(e.condition, ctx), ctx)
        val t = asExpr(lower(e.then, ctx), ctx)
        val f = asExpr(lower(e.otherwise, ctx), ctx)
        return when {
            c is Expr.IsNull -> LV.E(Expr.Coalesce(listOf(f, t)))
            c is Expr.Not && c.operand is Expr.IsNull -> LV.E(Expr.Coalesce(listOf(t, f)))
            else -> fail(ctx, "if/else is only translatable as ?: (a null check)")
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
    }

    /** A JVM value as a parameter (captured) or literal (constant). */
    private fun objExpr(o: LV.Obj, ctx: Ctx): Expr {
        val v = o.value
        if (v is QueryVariable) {
            if (v.wildcard) fail(ctx, "the wildcard has no value")
            return Expr.Var(entityVars[v] ?: fail(ctx, "variable $v belongs to another query"))
        }
        val (type, value) = when (v) {
            is EntityId -> ValueType.UUID to v.id
            is Enum<*> -> ValueType.TEXT to v.name
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
            return LV.Ctor({ Pair(it[0], it[1]) }, args, c.args.map { it.type })
        }
        // acc.id / p.id: an entity's UUID
        if (r != null && r.value is EntityId && c.name == "getId" && args.isEmpty()) {
            return LV.E(objExpr(r, ctx))
        }
        if (recv is LV.E && args.isEmpty() && recv.descriptor != null && SerialTypes.isObject(recv.descriptor)) {
            return field(recv, c, ctx)
        }
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
            val other = textArg(args[1], ctx) ?: return null
            return LV.E(Functions.call(fn, s, other))
        }
        return null
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
                    is EntityId -> shapeOf(LV.Obj(col, captured = true), "Lnet/blocks168/pgecs/EntityId;", ctx)
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
        const val ENTITY_ID = "Lnet/blocks168/pgecs/EntityId;"

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
