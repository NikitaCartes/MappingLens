package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.model.ExistsResponse
import xyz.nikitacartes.mappinglens.model.ExistsResult
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * "Does this member still exist?" — batch existence check for class/member keys against a version's
 * pre-remapped named jar (scanned via ASM, cached per (version, namespace) like [ReferenceService]).
 * Keys are the mcsrc convention: a class internal name `owner`, or a member `owner:name:descriptor`.
 * The named jar carries the namespace's own descriptors, so a mojmap descriptor matches a mojmap jar
 * exactly — no descriptor remapping needed. Used to validate mixin/shadow targets when updating a mod.
 *
 * A key that misses also reports the nearest declaration, so one call says what changed instead of
 * only that something did. See [ExistsResult.closest].
 */
class ExistsService(private val config: AppConfig) {

    /** What one (version, namespace) declares, indexed for the three questions [exists] asks. */
    private class Declarations(
        val classes: Set<String>,
        /** `owner:name:descriptor` for every declared method and field. */
        val members: Set<String>,
        /** Class internal name -> its direct supertypes, superclass first. */
        val supertypes: Map<String, List<String>>,
        /** `owner:name` -> the descriptors declared under that name. */
        val overloads: Map<String, List<String>>,
    )

    private val cache = ConcurrentHashMap<Pair<String, String>, Declarations>()

    /** Returns null when the namespace is unsupported or the version's named jar is missing. */
    fun exists(versionId: String, namespace: String, keys: List<String>): ExistsResponse? {
        if (namespace != "yarn" && namespace != "mojmap") return null
        val decls = cache[versionId to namespace]
            ?: buildIndex(versionId, namespace)?.also { cache[versionId to namespace] = it }
            ?: return null
        val results = keys.map { key ->
            val exists = if (key.contains(':')) key in decls.members else key in decls.classes
            if (exists) return@map ExistsResult(key = key, exists = true)
            // A key without a descriptor cannot match `owner:name:descriptor` however real the member
            // is, so it is answered with the declarations it names rather than with a bare `false`.
            val candidates = decls.overloads[key].orEmpty().sorted().map { "$key:$it" }
            if (candidates.isNotEmpty()) return@map ExistsResult(key = key, exists = false, candidates = candidates)
            val (closest, reason) = nearest(key, decls) ?: (null to null)
            ExistsResult(key = key, exists = false, closest = closest, reason = reason)
        }
        return ExistsResponse(versionId, namespace, results)
    }

    /**
     * The nearest declaration to a member key that missed, with the reason it differs. Three probes,
     * in the order a mod author cares about: an inherited declaration still resolves at runtime, so
     * the mixin is fine and the key only names the wrong owner. A changed descriptor does not, and
     * is the edit to make. A declaration of the other kind under the same name is neither, and says
     * so. A class key, an unknown owner, or an unknown name gives null.
     */
    private fun nearest(key: String, decls: Declarations): Pair<String, String>? {
        val owner = key.substringBefore(':')
        val name = key.substringAfter(':', "").substringBefore(':')
        val descriptor = key.substringAfter(':', "").substringAfter(':', "")
        if (name.isEmpty() || descriptor.isEmpty() || owner !in decls.classes) return null

        supertypesOf(owner, decls).firstOrNull { "$it:$name:$descriptor" in decls.members }
            ?.let { return "$it:$name:$descriptor" to "inherited" }
        // A method descriptor opens with '(' and a field descriptor does not, so the kind needs no
        // column of its own. A candidate of the other kind is reported under a reason of its own:
        // `overloads` holds fields and methods alike, and a client that pastes `closest` into a
        // mixin on a bare `descriptor` would otherwise write an @Inject into a field.
        val candidates = decls.overloads["$owner:$name"].orEmpty()
        val method = descriptor.startsWith("(")
        candidates.firstOrNull { it.startsWith("(") == method }
            ?.let { return "$owner:$name:$it" to "descriptor" }
        return candidates.firstOrNull()?.let { "$owner:$name:$it" to "kind" }
    }

    /** Every supertype of [owner], nearest first. Breadth-first, so a direct parent beats a distant one. */
    private fun supertypesOf(owner: String, decls: Declarations): List<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(decls.supertypes[owner].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            queue.addAll(decls.supertypes[next].orEmpty())
        }
        return seen.toList()
    }

    private fun buildIndex(versionId: String, namespace: String): Declarations? {
        val jar = config.sources.remappedJar(versionId, namespace)?.toFile() ?: return null
        val classes = HashSet<String>()
        val members = HashSet<String>()
        val supertypes = HashMap<String, List<String>>()
        val overloads = HashMap<String, MutableList<String>>()
        ZipFile(jar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                ClassReader(bytes).accept(
                    collector(classes, members, supertypes, overloads),
                    ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                )
            }
        }
        return Declarations(classes, members, supertypes, overloads)
    }

    private fun collector(
        classes: MutableSet<String>,
        members: MutableSet<String>,
        supertypes: MutableMap<String, List<String>>,
        overloads: MutableMap<String, MutableList<String>>,
    ): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9) {
            private lateinit var owner: String
            override fun visit(version: Int, access: Int, name: String, sig: String?, superName: String?, interfaces: Array<String>?) {
                owner = name
                classes += name
                val parents = listOfNotNull(superName) + interfaces.orEmpty()
                if (parents.isNotEmpty()) supertypes[name] = parents
            }
            override fun visitMethod(access: Int, name: String, descriptor: String, sig: String?, ex: Array<String>?): MethodVisitor? {
                record(name, descriptor)
                return null
            }
            override fun visitField(access: Int, name: String, descriptor: String, sig: String?, value: Any?): FieldVisitor? {
                record(name, descriptor)
                return null
            }
            private fun record(name: String, descriptor: String) {
                members += "$owner:$name:$descriptor"
                overloads.getOrPut("$owner:$name") { ArrayList(1) } += descriptor
            }
        }
}
