package dev.vxcc.alexs168.pgecs.lambda

import java.io.Serializable

// The functional interfaces pgecs translates. They are Serializable so that the compiler keeps a
// readable implementation method (javac and kotlinc then emit a `writeReplace` that names it); a
// plain Kotlin function type would compile to a hidden class with no recoverable bytecode.

/** A predicate on one value, as in `EmailComponent.where { it.address == addr }`. */
fun interface QueryPredicate<T> : Serializable {
    fun test(value: T): Boolean
}

/** A function of one value, as in `unique { it.address.lowercase() }`. */
fun interface QueryFunction<T, R> : Serializable {
    fun apply(value: T): R
}

/** A function of nothing but its captures, as in `select { acc to mail[EmailComponent].address }`. */
fun interface QuerySupplier<R> : Serializable {
    fun get(): R
}
