package dev.mappinglens.ingestion

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Extracts class/method/field names directly from a deobfuscated Minecraft jar (Mojang's
 * unobfuscated releases starting with 26.x). Names are treated as the mojmap namespace;
 * obfuscated/intermediary/yarn are left null.
 */
object UnobfuscatedJarScanner {
    fun scan(jar: Path): List<UnifiedClassEntry> {
        val entries = ArrayList<UnifiedClassEntry>()
        ZipFile(jar.toFile()).use { zip ->
            val it = zip.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                if (e.isDirectory || !e.name.endsWith(".class")) continue
                if (e.name == "module-info.class" || e.name.endsWith("/package-info.class")) continue
                zip.getInputStream(e).use { stream ->
                    val reader = ClassReader(stream)
                    val collector = ClassCollector()
                    reader.accept(collector, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    collector.build()?.let(entries::add)
                }
            }
        }
        return entries
    }

    private class ClassCollector : ClassVisitor(Opcodes.ASM9) {
        private var className: String? = null
        private val methods = ArrayList<UnifiedMemberEntry>()
        private val fields = ArrayList<UnifiedMemberEntry>()

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            className = name
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            methods += UnifiedMemberEntry(
                obfName = null, obfDesc = descriptor,
                intermediaryName = null, intermediaryDesc = null,
                yarnName = null,
                mojmapName = name,
            )
            return null
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            fields += UnifiedMemberEntry(
                obfName = null, obfDesc = descriptor,
                intermediaryName = null, intermediaryDesc = null,
                yarnName = null,
                mojmapName = name,
            )
            return null
        }

        fun build(): UnifiedClassEntry? {
            val n = className ?: return null
            return UnifiedClassEntry(
                obfName = null,
                intermediaryName = null,
                yarnName = null,
                mojmapName = n,
                methods = methods,
                fields = fields,
            )
        }
    }
}
