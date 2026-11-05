package net.blocks168.staticguard.scan

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * `static-guard scan [--instance-type a/b/C]... [--no-nested] [--tsv out.tsv] [--fields out.txt]
 *  [--min-severity LOW] [--details N] <jar|dir|class>...`
 *
 * Prints a summary (findings per origin and kind) and the first N findings per kind. `--tsv` writes
 * every finding; `--fields` writes the distinct fields of the MEDIUM and HIGH findings, one per line,
 * in the format the agent's `fields=` option reads.
 */
fun main(args: Array<String>) {
    val instanceTypes = mutableSetOf<String>()
    var nested = true
    var tsv: Path? = null
    var fieldsOut: Path? = null
    var minSeverity = Severity.LOW
    var details = 15
    val paths = mutableListOf<Path>()
    val it = args.iterator()
    while (it.hasNext()) {
        when (val a = it.next()) {
            "--instance-type" -> instanceTypes += it.next().replace('.', '/')
            "--no-nested" -> nested = false
            "--tsv" -> tsv = Path.of(it.next())
            "--fields" -> fieldsOut = Path.of(it.next())
            "--min-severity" -> minSeverity = Severity.valueOf(it.next())
            "--details" -> details = it.next().toInt()
            "-h", "--help" -> { println("usage: see Main.kt"); exitProcess(0) }
            else -> paths.add(Path.of(a))
        }
    }
    val scanner = StaticScanner(instanceTypes, nested)
    paths.forEach(scanner::scan)
    val all = scanner.result()
    val shown = all.filter { it.kind.severity <= minSeverity }
    print(Report.text(shown, scanner.classesScanned, details))
    tsv?.writeText(Report.tsv(all))
    fieldsOut?.writeText(Report.fields(all))
}

object Report {
    fun text(findings: List<Finding>, classes: Int, details: Int): String = buildString {
        appendLine("scanned $classes classes, ${findings.size} findings")
        val kinds = Kind.entries.filter { k -> findings.any { it.kind == k } }
        val byOrigin = findings.groupBy { it.origin.substringBefore("!/") }
        appendLine()
        appendLine(("origin".padEnd(48)) + kinds.joinToString("") { abbrev(it).padStart(8) })
        for ((origin, fs) in byOrigin.toSortedMap()) {
            val counts = fs.groupingBy { it.kind }.eachCount()
            appendLine(origin.take(47).padEnd(48) + kinds.joinToString("") { (counts[it] ?: 0).toString().padStart(8) })
        }
        appendLine()
        kinds.forEach { appendLine("  ${abbrev(it).padEnd(7)} ${it.name} (${it.severity}): ${it.description}") }
        for (k in kinds) {
            val fs = findings.filter { it.kind == k }
            appendLine()
            appendLine("== ${k.name} (${fs.size})")
            fs.take(details).forEach { f ->
                appendLine("  ${f.field}  @ ${f.site}${if (f.mixin) " [mixin]" else ""}  (${f.origin.substringAfterLast("!/")})")
            }
            if (fs.size > details) appendLine("  ... ${fs.size - details} more")
        }
    }

    fun tsv(findings: List<Finding>): String = buildString {
        appendLine("kind\tseverity\tfield\tsite\tmixin\torigin")
        findings.forEach { appendLine("${it.kind}\t${it.kind.severity}\t${it.field}\t${it.site}\t${it.mixin}\t${it.origin}") }
    }

    /** `owner.name` of the fields behind MEDIUM/HIGH findings, for the agent's watch list. */
    fun fields(findings: List<Finding>): String =
        findings.filter { it.kind.severity <= Severity.MEDIUM && it.kind != Kind.JVM_GLOBAL }
            .map { it.field.substringBefore(':') }.distinct().sorted().joinToString("\n", postfix = "\n")

    private fun abbrev(k: Kind) = when (k) {
        Kind.INSTANCE_STATIC -> "inst"
        Kind.JVM_GLOBAL -> "jvm"
        Kind.STATIC_WRITE -> "write"
        Kind.STATIC_CONTAINER_MUTATION -> "mutate"
        Kind.STATIC_ARRAY_STORE -> "arrstore"
        Kind.LAZY_INIT -> "lazy"
        Kind.STATIC_CACHE -> "cache"
        Kind.THREAD_LOCAL -> "tlocal"
        Kind.MUTABLE_TYPE -> "muttype"
    }
}
