package blocks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.util.zip.ZipFile

/**
 * Extracts [jars] into [outputDirectory], rewriting every static `native` method into
 * `return (R) Bridge.call("name", new Object[] { args... })`, where `Bridge` is [bridgeClass] (internal name) with a
 * static `long call(String, Object[])`. Methods listed in [noOpMethods] (`owner/Class.method`, returning void) get an
 * empty body. Used to run a JNI library's Java bindings on a wasm build of the library (libs/vodozemac-endive).
 */
@CacheableTask
abstract class RewriteNativeMethods : DefaultTask() {
    @get:Classpath
    abstract val jars: ConfigurableFileCollection

    @get:Input
    abstract val bridgeClass: Property<String>

    @get:Input
    abstract val noOpMethods: SetProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun rewrite() {
        val dir = outputDirectory.get().asFile
        dir.deleteRecursively()
        val bridge = bridgeClass.get()
        val noOps = noOpMethods.get()
        var natives = 0
        for (jar in jars) ZipFile(jar).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val target = dir.resolve(entry.name).also { it.parentFile.mkdirs() }
                if (!entry.name.endsWith(".class")) {
                    target.writeBytes(bytes)
                    continue
                }
                val reader = ClassReader(bytes)
                val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
                val className = reader.className
                reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                    override fun visitMethod(
                        access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?,
                    ): MethodVisitor? {
                        if (access and Opcodes.ACC_NATIVE != 0) {
                            if (access and Opcodes.ACC_STATIC == 0) throw GradleException("$className.$name is not static")
                            natives++
                            val mv = super.visitMethod(access and Opcodes.ACC_NATIVE.inv(), name, descriptor, signature, exceptions)
                            emitBridgeCall(mv, bridge, name, descriptor)
                            return null
                        }
                        if ("$className.$name" in noOps) {
                            if (Type.getReturnType(descriptor) != Type.VOID_TYPE) throw GradleException("$className.$name does not return void")
                            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                            mv.visitCode()
                            mv.visitInsn(Opcodes.RETURN)
                            mv.visitMaxs(0, 0)
                            mv.visitEnd()
                            return null
                        }
                        return super.visitMethod(access, name, descriptor, signature, exceptions)
                    }
                }, 0)
                target.writeBytes(writer.toByteArray())
            }
        }
        if (natives == 0) throw GradleException("no native methods found in ${jars.files}")
        logger.info("rewrote $natives native methods")
    }

    private fun emitBridgeCall(mv: MethodVisitor, owner: String, name: String, descriptor: String) {
        val args = Type.getArgumentTypes(descriptor)
        val ret = Type.getReturnType(descriptor)
        mv.visitCode()
        mv.visitLdcInsn(name)
        mv.visitLdcInsn(args.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        var slot = 0
        args.forEachIndexed { i, t ->
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn(i)
            mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot)
            val box = when (t.sort) {
                Type.LONG -> "java/lang/Long"
                Type.INT -> "java/lang/Integer"
                Type.SHORT -> "java/lang/Short"
                Type.BYTE -> "java/lang/Byte"
                Type.OBJECT, Type.ARRAY -> null
                else -> throw GradleException("$name: unsupported parameter type $t")
            }
            if (box != null) mv.visitMethodInsn(Opcodes.INVOKESTATIC, box, "valueOf", "(${t.descriptor})L$box;", false)
            mv.visitInsn(Opcodes.AASTORE)
            slot += t.size
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "call", "(Ljava/lang/String;[Ljava/lang/Object;)J", false)
        when (ret.sort) {
            Type.VOID -> { mv.visitInsn(Opcodes.POP2); mv.visitInsn(Opcodes.RETURN) }
            Type.LONG -> mv.visitInsn(Opcodes.LRETURN)
            Type.INT, Type.BOOLEAN -> { mv.visitInsn(Opcodes.L2I); mv.visitInsn(Opcodes.IRETURN) }
            Type.SHORT -> { mv.visitInsn(Opcodes.L2I); mv.visitInsn(Opcodes.I2S); mv.visitInsn(Opcodes.IRETURN) }
            Type.BYTE -> { mv.visitInsn(Opcodes.L2I); mv.visitInsn(Opcodes.I2B); mv.visitInsn(Opcodes.IRETURN) }
            else -> throw GradleException("$name: unsupported return type $ret")
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }
}
