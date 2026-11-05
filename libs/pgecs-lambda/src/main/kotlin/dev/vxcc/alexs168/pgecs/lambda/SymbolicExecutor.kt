package dev.vxcc.alexs168.pgecs.lambda

import java.lang.classfile.CodeElement
import java.lang.classfile.CodeModel
import java.lang.classfile.Instruction
import java.lang.classfile.Label
import java.lang.classfile.Opcode
import java.lang.classfile.TypeKind
import java.lang.classfile.instruction.BranchInstruction
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.ConvertInstruction
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.IncrementInstruction
import java.lang.classfile.instruction.InvokeDynamicInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.classfile.instruction.LabelTarget
import java.lang.classfile.instruction.LineNumber
import java.lang.classfile.instruction.LoadInstruction
import java.lang.classfile.instruction.NewObjectInstruction
import java.lang.classfile.instruction.NopInstruction
import java.lang.classfile.instruction.OperatorInstruction
import java.lang.classfile.instruction.ReturnInstruction
import java.lang.classfile.instruction.StackInstruction
import java.lang.classfile.instruction.StoreInstruction
import java.lang.classfile.instruction.ThrowInstruction
import java.lang.classfile.instruction.TypeCheckInstruction
import java.lang.constant.DirectMethodHandleDesc
import java.lang.constant.MethodTypeDesc

/**
 * Executes one method's bytecode symbolically and returns its result as an [LExpr].
 *
 * Every conditional branch forks the whole continuation, so the method becomes a decision tree with
 * one leaf per path. The tree is then folded back into an expression: boolean methods become
 * `&&`/`||`/`!` formulas over the branch conditions, others become [LExpr.Cond]. Paths that throw
 * (Kotlin's `!!`, failed checks) are dropped. Loops, switches, arrays, `instanceof`, monitors and
 * calls without a result are rejected, since none of them has a meaning in a query.
 */
internal class SymbolicExecutor(
    private val location: String,
    private val code: CodeModel,
    private val returnType: String,
    private val statics: StaticFieldReader,
    /** Reads `this.<name>` of a class-compiled lambda. */
    private val thisField: ((name: String, descriptor: String) -> LExpr)? = null,
    private val maxPaths: Int = 1024,
) {
    /** Stack and local values: an expression, or one of the internal markers. */
    private sealed interface V {
        data class E(val e: LExpr) : V
        /** Result of `new`, before its constructor ran. Identity matters (dup'ed copies). */
        class Uninit(val owner: String) : V
        /** The lambda object of a class-compiled lambda. */
        data object This : V
        /** Result of `lcmp`/`fcmpl`/`dcmpg`..., only meaningful to a following `if<cond>`. */
        data class Cmp3(val left: LExpr, val right: LExpr) : V
    }

    private sealed interface Tree {
        data class Leaf(val e: LExpr) : Tree
        data class Branch(val cond: LExpr, val taken: Tree, val fallThrough: Tree) : Tree
        data object Unreachable : Tree
    }

    private class State(val stack: ArrayList<V>, val locals: HashMap<Int, V>) {
        fun copy() = State(ArrayList(stack), HashMap(locals))
        fun push(v: V) { stack.add(v) }
        fun pop(): V = stack.removeLast()
    }

    private val elements: List<CodeElement> = code.elementList()
    private val labels: Map<Label, Int> = buildMap {
        elements.forEachIndexed { i, e -> if (e is LabelTarget) put(e.label(), i) }
    }
    private var paths = 0
    private var line = -1

    fun run(initialLocals: Map<Int, LExpr>, thisSlot: Boolean): LExpr {
        val locals = HashMap<Int, V>()
        if (thisSlot) locals[0] = V.This
        initialLocals.forEach { (slot, e) -> locals[slot] = V.E(e) }
        val tree = exec(0, State(ArrayList(), locals))
        return fold(tree) ?: fail("every path of the lambda throws")
    }

    private fun fail(message: String): Nothing =
        throw UntranslatableLambdaException(if (line >= 0) "$message ($location:$line)" else "$message ($location)")

    // ---- execution ----

    private fun exec(start: Int, s: State): Tree {
        var pc = start
        while (pc < elements.size) {
            val el = elements[pc]
            if (el is LineNumber) line = el.line()
            if (el !is Instruction) { pc++; continue }
            when (el) {
                is NopInstruction -> {}
                is LoadInstruction -> s.push(s.locals[el.slot()] ?: fail("read of unset local ${el.slot()}"))
                is StoreInstruction -> s.locals[el.slot()] = s.pop()
                is IncrementInstruction -> {
                    val v = expr(s.locals[el.slot()] ?: fail("read of unset local ${el.slot()}"))
                    s.locals[el.slot()] = V.E(LExpr.Binary(BinOp.ADD, v, LExpr.Const(el.constant(), "I"), "I"))
                }
                is ConstantInstruction -> s.push(V.E(constant(el)))
                is StackInstruction -> stackOp(el.opcode(), s)
                is OperatorInstruction -> operator(el, s)
                is ConvertInstruction -> {
                    val v = expr(s.pop())
                    s.push(V.E(LExpr.Convert(v, el.toType().upperBound().descriptorString())))
                }
                is TypeCheckInstruction -> {
                    if (el.opcode() == Opcode.INSTANCEOF) fail("instanceof is not translatable")
                    val v = expr(s.pop())
                    val target = el.type().asSymbol().descriptorString()
                    s.push(V.E(retype(v, target)))
                }
                is FieldInstruction -> field(el, s)
                is InvokeInstruction -> if (!invoke(el, s)) {
                    paths++
                    return Tree.Unreachable
                }
                is InvokeDynamicInstruction -> s.push(V.E(invokeDynamic(el, s)))
                is NewObjectInstruction -> s.push(V.Uninit(el.className().asInternalName()))
                is BranchInstruction -> {
                    val target = labels[el.target()] ?: fail("branch to unknown label")
                    if (target <= pc) fail("loops are not translatable")
                    if (el.opcode() == Opcode.GOTO || el.opcode() == Opcode.GOTO_W) {
                        pc = target
                        continue
                    }
                    val cond = branchCondition(el.opcode(), s)
                    return when (cond) {
                        LExpr.TRUE -> exec(target, s)
                        LExpr.FALSE -> exec(pc + 1, s)
                        else -> Tree.Branch(cond, exec(target, s.copy()), exec(pc + 1, s))
                    }
                }
                is ReturnInstruction -> {
                    if (el.typeKind() == TypeKind.VOID) fail("lambda returns nothing")
                    if (++paths > maxPaths) fail("too many paths (more than $maxPaths)")
                    return Tree.Leaf(coerce(expr(s.pop()), returnType))
                }
                is ThrowInstruction -> {
                    paths++
                    return Tree.Unreachable
                }
                else -> fail("${el.opcode().name.lowercase()} is not translatable")
            }
            pc++
        }
        fail("fell off the end of the method")
    }

    private fun expr(v: V): LExpr = when (v) {
        is V.E -> v.e
        is V.Uninit -> fail("use of an object before its constructor ran")
        V.This -> fail("the lambda object itself is not translatable")
        is V.Cmp3 -> fail("a three-way comparison result is only translatable inside a condition")
    }

    private fun constant(el: ConstantInstruction): LExpr {
        if (el.opcode() == Opcode.ACONST_NULL) return LExpr.Const(null, LExpr.NULL_TYPE)
        return when (val v: Any = el.constantValue()) {
            is Int -> LExpr.Const(v, "I")
            is Long -> LExpr.Const(v, "J")
            is Float -> LExpr.Const(v, "F")
            is Double -> LExpr.Const(v, "D")
            is String -> LExpr.Const(v, "Ljava/lang/String;")
            else -> fail("constant $v is not translatable")
        }
    }

    private fun isWide(v: V) = v is V.E && (v.e.type == "J" || v.e.type == "D")

    private fun stackOp(op: Opcode, s: State) {
        when (op) {
            Opcode.POP -> s.pop()
            Opcode.POP2 -> { if (isWide(s.pop())) Unit else s.pop() }
            Opcode.DUP -> s.push(s.stack.last())
            Opcode.DUP_X1 -> { val a = s.pop(); val b = s.pop(); s.push(a); s.push(b); s.push(a) }
            Opcode.DUP2 -> {
                val a = s.pop()
                if (isWide(a)) { s.push(a); s.push(a) } else {
                    val b = s.pop(); s.push(b); s.push(a); s.push(b); s.push(a)
                }
            }
            Opcode.SWAP -> { val a = s.pop(); val b = s.pop(); s.push(a); s.push(b) }
            else -> fail("${op.name.lowercase()} is not translatable")
        }
    }

    private fun operator(el: OperatorInstruction, s: State) {
        val type = el.typeKind().upperBound().descriptorString()
        val op = el.opcode()
        when (op) {
            Opcode.LCMP, Opcode.FCMPL, Opcode.FCMPG, Opcode.DCMPL, Opcode.DCMPG -> {
                val r = expr(s.pop()); val l = expr(s.pop())
                s.push(V.Cmp3(l, r))
                return
            }
            Opcode.INEG, Opcode.LNEG, Opcode.FNEG, Opcode.DNEG -> {
                s.push(V.E(LExpr.Neg(expr(s.pop()), type)))
                return
            }
            Opcode.ARRAYLENGTH -> fail("arrays are not translatable")
            else -> {}
        }
        val bin = when (op.name.substring(1)) {
            "ADD" -> BinOp.ADD; "SUB" -> BinOp.SUB; "MUL" -> BinOp.MUL; "DIV" -> BinOp.DIV; "REM" -> BinOp.REM
            "AND" -> BinOp.AND; "OR" -> BinOp.OR; "XOR" -> BinOp.XOR
            "SHL" -> BinOp.SHL; "SHR" -> BinOp.SHR; "USHR" -> BinOp.USHR
            else -> fail("${op.name.lowercase()} is not translatable")
        }
        val r = expr(s.pop()); val l = expr(s.pop())
        // Booleans combined with & | ^ (Kotlin `and`, `or`, `xor` on Boolean) stay boolean.
        if (l.type == "Z" && r.type == "Z") {
            when (bin) {
                BinOp.AND -> { s.push(V.E(LExpr.And(l, r))); return }
                BinOp.OR -> { s.push(V.E(LExpr.Or(l, r))); return }
                BinOp.XOR -> { s.push(V.E(LExpr.Compare(CmpOp.NE, l, r))); return }
                else -> {}
            }
        }
        s.push(V.E(LExpr.Binary(bin, l, r, type)))
    }

    private fun field(el: FieldInstruction, s: State) {
        val owner = el.owner().asInternalName()
        val name = el.name().stringValue()
        val desc = el.type().stringValue()
        when (el.opcode()) {
            Opcode.GETSTATIC -> s.push(V.E(statics.read(owner, name, desc) ?: fail("$owner.$name is not a static final field")))
            Opcode.GETFIELD -> {
                val r = s.pop()
                if (r == V.This) {
                    val read = thisField ?: fail("field read on the lambda object")
                    s.push(V.E(read(name, desc)))
                } else {
                    s.push(V.E(LExpr.GetField(expr(r), owner, name, desc)))
                }
            }
            else -> fail("writing fields is not translatable")
        }
    }

    /** Returns false when the call always throws, ending the path. */
    private fun invoke(el: InvokeInstruction, s: State): Boolean {
        val owner = el.owner().asInternalName()
        val name = el.name().stringValue()
        val mt = el.typeSymbol()
        val args = ArrayList<V>()
        repeat(mt.parameterCount()) { args.add(s.pop()) }
        args.reverse()
        val static = el.opcode() == Opcode.INVOKESTATIC
        val receiver = if (static) null else s.pop()

        if (name == "<init>") {
            val u = receiver as? V.Uninit ?: fail("constructor call on an existing object")
            val created = V.E(LExpr.New(owner, mt.descriptorString(), args.mapIndexed { i, a -> coerce(expr(a), mt.parameterType(i).descriptorString()) }))
            for (i in s.stack.indices) if (s.stack[i] === u) s.stack[i] = created
            for (k in s.locals.keys) if (s.locals[k] === u) s.locals[k] = created
            return true
        }
        if (owner == KOTLIN_INTRINSICS && name.startsWith("throw")) return false
        val ret = mt.returnType().descriptorString()
        if (ret == "V") {
            if (owner == KOTLIN_INTRINSICS && name.startsWith("check")) return true // null checks: no value
            fail("call to ${owner.replace('/', '.')}.$name has no result")
        }
        val kind = when (el.opcode()) {
            Opcode.INVOKESTATIC -> LExpr.Invoke.Kind.STATIC
            Opcode.INVOKEINTERFACE -> LExpr.Invoke.Kind.INTERFACE
            Opcode.INVOKESPECIAL -> LExpr.Invoke.Kind.SPECIAL
            else -> LExpr.Invoke.Kind.VIRTUAL
        }
        val argExprs = args.mapIndexed { i, a -> coerce(expr(a), mt.parameterType(i).descriptorString()) }
        s.push(V.E(Simplify.call(LExpr.Invoke(kind, owner, name, mt.descriptorString(), receiver?.let(::expr), argExprs, ret))))
        return true
    }

    private fun invokeDynamic(el: InvokeDynamicInstruction, s: State): LExpr {
        val bsm: DirectMethodHandleDesc = el.bootstrapMethod()
        val mt: MethodTypeDesc = el.typeSymbol()
        val args = ArrayList<LExpr>()
        repeat(mt.parameterCount()) { args.add(expr(s.pop())) }
        args.reverse()
        if (bsm.owner().descriptorString() != "Ljava/lang/invoke/StringConcatFactory;") {
            fail("nested lambdas and method references are not translatable")
        }
        if (bsm.methodName() == "makeConcat") return LExpr.Concat(args)
        val bsmArgs = el.bootstrapArgs()
        val recipe = bsmArgs[0] as String
        var nextArg = 0
        var nextConst = 1
        val parts = ArrayList<LExpr>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) parts.add(LExpr.Const(literal.toString(), "Ljava/lang/String;"))
            literal.clear()
        }
        for (ch in recipe) when (ch) {
            '\u0001' -> { flush(); parts.add(args[nextArg++]) }
            '\u0002' -> literal.append(bsmArgs[nextConst++].toString())
            else -> literal.append(ch)
        }
        flush()
        return LExpr.Concat(parts)
    }

    private fun branchCondition(op: Opcode, s: State): LExpr {
        fun zero() = LExpr.Const(0, "I")
        fun cmpOf(suffix: String) = when (suffix) {
            "EQ" -> CmpOp.EQ; "NE" -> CmpOp.NE; "LT" -> CmpOp.LT
            "GE" -> CmpOp.GE; "GT" -> CmpOp.GT; "LE" -> CmpOp.LE
            else -> fail("unknown branch $op")
        }
        return when (op) {
            Opcode.IFNULL -> Simplify.compare(CmpOp.EQ, expr(s.pop()), LExpr.Const(null, LExpr.NULL_TYPE))
            Opcode.IFNONNULL -> Simplify.compare(CmpOp.NE, expr(s.pop()), LExpr.Const(null, LExpr.NULL_TYPE))
            Opcode.IFEQ, Opcode.IFNE, Opcode.IFLT, Opcode.IFGE, Opcode.IFGT, Opcode.IFLE -> {
                val cmp = cmpOf(op.name.substring(2))
                when (val v = s.pop()) {
                    is V.Cmp3 -> Simplify.compare(cmp, v.left, v.right)
                    else -> Simplify.compare(cmp, expr(v), zero())
                }
            }
            Opcode.IF_ICMPEQ, Opcode.IF_ICMPNE, Opcode.IF_ICMPLT, Opcode.IF_ICMPGE, Opcode.IF_ICMPGT, Opcode.IF_ICMPLE,
            Opcode.IF_ACMPEQ, Opcode.IF_ACMPNE -> {
                val r = expr(s.pop()); val l = expr(s.pop())
                Simplify.compare(cmpOf(op.name.substring(7)), l, r)
            }
            else -> fail("${op.name.lowercase()} is not translatable")
        }
    }

    // ---- folding the path tree back into an expression ----

    private fun fold(t: Tree): LExpr? = when (t) {
        is Tree.Leaf -> t.e
        Tree.Unreachable -> null
        is Tree.Branch -> {
            val a = fold(t.taken)
            val b = fold(t.fallThrough)
            when {
                a == null -> b
                b == null -> a
                a == b -> a
                returnType == "Z" -> Simplify.choose(t.cond, a, b)
                else -> LExpr.Cond(t.cond, a, b, returnType)
            }
        }
    }

    companion object {
        const val KOTLIN_INTRINSICS = "kotlin/jvm/internal/Intrinsics"

        /** Makes `iconst_0/1` booleans where a boolean is expected. */
        fun coerce(e: LExpr, expected: String): LExpr =
            if (expected == "Z" && e is LExpr.Const && e.type == "I") {
                if (e.value == 0) LExpr.FALSE else LExpr.TRUE
            } else e

        /** A `checkcast` only changes the static type. */
        fun retype(e: LExpr, type: String): LExpr = when (e) {
            is LExpr.Arg -> e.copy(type = type)
            is LExpr.Captured -> e.copy(type = type)
            is LExpr.Invoke -> e.copy(type = type)
            is LExpr.GetField -> e.copy(type = type)
            is LExpr.Const -> if (e.value == null) e.copy(type = type) else e
            else -> if (e.type == type) e else LExpr.Convert(e, type)
        }
    }
}
