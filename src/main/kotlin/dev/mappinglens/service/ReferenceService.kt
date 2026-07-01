package dev.mappinglens.service

import dev.mappinglens.config.AppConfig
import dev.mappinglens.model.ReferenceItem
import dev.mappinglens.model.ReferenceResponse
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * "Find all references": a reverse index from a class/member to the enclosing methods (and class
 * headers) that reference it, built by scanning the version's pre-remapped named jar via ASM. Only
 * references whose target is another Minecraft class in the same jar are kept (JDK calls are dropped),
 * and the whole index is cached per (version, namespace) — the index is immutable for the server's
 * lifetime, so the first query pays the scan and the rest are map lookups.
 *
 * Reference keys match the mcsrc convention so the frontend can build them from a token:
 *   class  -> `owner`
 *   method -> `owner:name:descriptor`
 *   field  -> `owner:name:descriptor`
 */
class ReferenceService(private val config: AppConfig) {

    /** Where a reference occurs: an enclosing method, or a class header when [member] is null. */
    data class Referrer(val owner: String, val member: String?, val descriptor: String?, val kind: String)

    private val cache = ConcurrentHashMap<Pair<String, String>, Map<String, Set<Referrer>>>()

    fun references(versionId: String, target: String, namespace: String): ReferenceResponse? {
        if (namespace != "yarn" && namespace != "mojmap") return null
        val index = cache.computeIfAbsent(versionId to namespace) { (v, ns) -> buildIndex(v, ns) ?: emptyMap() }
        val referrers = index[target] ?: emptySet()
        val items = referrers
            .sortedWith(compareBy({ it.owner }, { it.member ?: "" }))
            .map { r ->
                ReferenceItem(
                    owner = r.owner,
                    ownerSimple = r.owner.substringAfterLast('/'),
                    member = r.member,
                    descriptor = r.descriptor,
                    kind = r.kind,
                )
            }
        return ReferenceResponse(versionId, namespace, target, items)
    }

    private fun buildIndex(versionId: String, namespace: String): Map<String, Set<Referrer>>? {
        val jar = config.sources.remappedJar(versionId, namespace)?.toFile() ?: return null
        val classBytes = ArrayList<ByteArray>()
        ZipFile(jar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                classBytes.add(zip.getInputStream(entry).use { it.readBytes() })
            }
        }
        return scan(classBytes)
    }

    companion object {
        /**
         * Reverse index over a set of class files. Pure over the bytes (no jar/config), so it is
         * unit-testable. Pass 1 collects the set of class names present (targets outside it are
         * JDK/library refs and dropped); pass 2 records only references whose owner is one of them.
         */
        fun scan(classBytes: List<ByteArray>): Map<String, Set<Referrer>> {
            val mcClasses = HashSet<String>()
            for (bytes in classBytes) mcClasses.add(ClassReader(bytes).className)

            val index = HashMap<String, MutableSet<Referrer>>()
            val record = { targetKey: String, referrer: Referrer -> index.getOrPut(targetKey) { HashSet() }.add(referrer); Unit }
            for (bytes in classBytes) {
                ClassReader(bytes).accept(ReferenceCollector(mcClasses, record), ClassReader.SKIP_FRAMES)
            }
            return index
        }
    }

    private class ReferenceCollector(
        private val mcClasses: Set<String>,
        private val record: (String, Referrer) -> Unit,
    ) : ClassVisitor(Opcodes.ASM9) {
        private lateinit var className: String

        override fun visit(version: Int, access: Int, name: String, sig: String?, superName: String?, interfaces: Array<String>?) {
            className = name
            // Class-header references (extends / implements), attributed to the class itself.
            (listOfNotNull(superName) + (interfaces?.toList() ?: emptyList()))
                .filter { it in mcClasses && it != name }
                .forEach { record(it, Referrer(name, null, null, "class")) }
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, sig: String?, exceptions: Array<String>?): MethodVisitor {
            val referrer = Referrer(className, name, descriptor, "method")
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(op: Int, owner: String, mName: String, mDesc: String, itf: Boolean) {
                    if (owner in mcClasses && owner != className) {
                        record("$owner:$mName:$mDesc", referrer)
                        record(owner, referrer) // also a class reference
                    }
                }

                override fun visitFieldInsn(op: Int, owner: String, fName: String, fDesc: String) {
                    if (owner in mcClasses && owner != className) {
                        record("$owner:$fName:$fDesc", referrer)
                        record(owner, referrer)
                    }
                }

                override fun visitTypeInsn(op: Int, type: String) {
                    if (type in mcClasses && type != className) record(type, referrer)
                }
            }
        }
    }
}
