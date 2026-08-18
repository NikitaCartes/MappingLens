package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.model.ReferenceGroup
import xyz.nikitacartes.mappinglens.model.ReferenceItem
import xyz.nikitacartes.mappinglens.model.ReferenceResponse
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

    /**
     * One group per (version, target). Returns null when the namespace is unsupported or no
     * requested version has a named jar; a version that has one but knows nothing of a target gives
     * an empty group, which is the honest answer.
     *
     * Several targets against several versions in one call is the shape the work has: checking that
     * a mixin still holds means asking the same handful of targets of every version being collapsed,
     * and the per-version index is built once and reused across every target of that version.
     */
    fun references(versions: List<String>, targets: List<String>, namespace: String): ReferenceResponse? {
        if (namespace != "yarn" && namespace != "mojmap") return null
        val available = versions.filter { config.sources.remappedJar(it, namespace) != null }
        if (available.isEmpty()) return null
        val groups = available.flatMap { version ->
            val index = cache.computeIfAbsent(version to namespace) { (v, ns) -> buildIndex(v, ns) ?: emptyMap() }
            targets.map { target -> ReferenceGroup(version, target, items(index[target].orEmpty())) }
        }
        return ReferenceResponse(namespace, groups)
    }

    private fun items(referrers: Set<Referrer>): List<ReferenceItem> = referrers
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
