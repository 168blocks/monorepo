package dev.vxcc.b8.mod.creativehost

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** Copies template worlds (a world directory with `level.dat`, usually with its spawn area generated). */
object WorldTemplates {
    /** files of a running or crashed server that a copy must not take along */
    private val skipped = setOf("session.lock")

    /**
     * Copies [template] to [target] unless [target] already exists; true if it copied. The copy goes to a
     * sibling directory first and is moved into place, so a failed copy leaves no half world behind.
     */
    fun copyIfAbsent(template: Path, target: Path): Boolean {
        if (Files.exists(target)) return false
        require(Files.isRegularFile(template.resolve("level.dat"))) { "template $template has no level.dat" }
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling(".${target.fileName}.partial")
        deleteTree(partial)
        Files.walkFileTree(template, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(partial.resolve(template.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.fileName.toString() !in skipped) {
                    Files.copy(file, partial.resolve(template.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                }
                return FileVisitResult.CONTINUE
            }
        })
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        return true
    }

    internal fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
