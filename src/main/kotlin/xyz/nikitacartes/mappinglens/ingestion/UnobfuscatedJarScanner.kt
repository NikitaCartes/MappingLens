package xyz.nikitacartes.mappinglens.ingestion

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Extracts class/method/field names directly from a deobfuscated Minecraft jar (Mojang's
 * unobfuscated releases starting with 26.x). Names are treated as the mojmap namespace; obfuscated
 * and yarn are left null.
 *
 * When [scan] is given the version's intermediary mappings, intermediary names are filled in from
 * them. The jar stays the source of truth for what exists and for the descriptors — the mappings
 * only name what they know, which is why they decorate the scan instead of replacing it.
 */
object UnobfuscatedJarScanner {
    fun scan(jar: Path, intermediary: ParsedMappings? = null): List<UnifiedClassEntry> {
        val names = intermediary?.let(::IntermediaryNames)
        val entries = ArrayList<UnifiedClassEntry>()
        ZipFile(jar.toFile()).use { zip ->
            val it = zip.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                if (e.isDirectory || !e.name.endsWith(".class")) continue
                if (e.name == "module-info.class" || e.name.endsWith("/package-info.class")) continue
                zip.getInputStream(e).use { stream ->
                    val reader = ClassReader(stream)
                    val collector = ClassCollector(names)
                    reader.accept(collector, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    collector.build()?.let(entries::add)
                }
            }
        }
        return entries
    }

    /**
     * Intermediary names of one unobfuscated version, keyed by the names the jar itself carries.
     * The `official` namespace of these files is the unobfuscated name, so the join needs no
     * remapping: the class name and the (name, descriptor) pair are already the jar's own.
     */
    private class IntermediaryNames(parsed: ParsedMappings) {
        private val idx = parsed.namespaces.indexOf("intermediary").takeIf { it > 0 }
            ?: (parsed.namespaces.size - 1)
        private val classes = HashMap<String, String>()
        // ponytail: one map for methods and fields — a method descriptor starts with '(' and a
        // field descriptor never does, so the two key spaces cannot collide.
        private val members = HashMap<Triple<String, String, String>, Pair<String?, String?>>()

        init {
            for (cls in parsed.classes) {
                val official = cls.names.getOrNull(0) ?: continue
                cls.names.getOrNull(idx)?.let { classes[official] = it }
                for (m in cls.methods.asSequence() + cls.fields.asSequence()) {
                    val name = m.names.getOrNull(0) ?: continue
                    val desc = m.descs.getOrNull(0) ?: continue
                    members[Triple(official, name, desc)] = m.names.getOrNull(idx) to m.descs.getOrNull(idx)
                }
            }
        }

        fun forClass(name: String): String? = classes[name]

        fun forMember(owner: String, name: String, desc: String): Pair<String?, String?> =
            members[Triple(owner, name, desc)] ?: NONE

        private companion object {
            val NONE: Pair<String?, String?> = null to null
        }
    }

    private class ClassCollector(private val intermediary: IntermediaryNames?) : ClassVisitor(Opcodes.ASM9) {
        private var className: String? = null
        private val methods = ArrayList<UnifiedMemberEntry>()
        private val fields = ArrayList<UnifiedMemberEntry>()

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            className = name
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            methods += member(name, descriptor)
            return null
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            fields += member(name, descriptor)
            return null
        }

        private fun member(name: String, descriptor: String): UnifiedMemberEntry {
            val owner = className
            val (imName, imDesc) = if (owner != null && intermediary != null) {
                intermediary.forMember(owner, name, descriptor)
            } else {
                null to null
            }
            return UnifiedMemberEntry(
                obfName = null, obfDesc = descriptor,
                intermediaryName = imName, intermediaryDesc = imDesc,
                yarnName = null,
                mojmapName = name,
            )
        }

        fun build(): UnifiedClassEntry? {
            val n = className ?: return null
            return UnifiedClassEntry(
                obfName = null,
                intermediaryName = intermediary?.forClass(n),
                yarnName = null,
                mojmapName = n,
                methods = methods,
                fields = fields,
                presence = CorrespondenceResolver.PRESENCE_MOJMAP_ONLY,
            )
        }
    }
}
