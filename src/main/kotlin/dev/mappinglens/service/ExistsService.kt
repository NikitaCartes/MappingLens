package dev.mappinglens.service

import dev.mappinglens.config.AppConfig
import dev.mappinglens.model.ExistsResponse
import dev.mappinglens.model.ExistsResult
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
 */
class ExistsService(private val config: AppConfig) {

    /** Declared class internal names + `owner:name:descriptor` member keys for one (version, namespace). */
    private class Declarations(val classes: Set<String>, val members: Set<String>)

    private val cache = ConcurrentHashMap<Pair<String, String>, Declarations>()

    /** Returns null when the namespace is unsupported or the version's named jar is missing. */
    fun exists(versionId: String, namespace: String, keys: List<String>): ExistsResponse? {
        if (namespace != "yarn" && namespace != "mojmap") return null
        val decls = cache[versionId to namespace]
            ?: buildIndex(versionId, namespace)?.also { cache[versionId to namespace] = it }
            ?: return null
        val results = keys.map { key ->
            val exists = if (key.contains(':')) key in decls.members else key in decls.classes
            ExistsResult(key = key, exists = exists)
        }
        return ExistsResponse(versionId, namespace, results)
    }

    private fun buildIndex(versionId: String, namespace: String): Declarations? {
        val jar = config.sources.remappedJar(versionId, namespace)?.toFile() ?: return null
        val classes = HashSet<String>()
        val members = HashSet<String>()
        ZipFile(jar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                ClassReader(bytes).accept(collector(classes, members), ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
        }
        return Declarations(classes, members)
    }

    private fun collector(classes: MutableSet<String>, members: MutableSet<String>): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9) {
            private lateinit var owner: String
            override fun visit(version: Int, access: Int, name: String, sig: String?, superName: String?, interfaces: Array<String>?) {
                owner = name
                classes += name
            }
            override fun visitMethod(access: Int, name: String, descriptor: String, sig: String?, ex: Array<String>?): MethodVisitor? {
                members += "$owner:$name:$descriptor"
                return null
            }
            override fun visitField(access: Int, name: String, descriptor: String, sig: String?, value: Any?): FieldVisitor? {
                members += "$owner:$name:$descriptor"
                return null
            }
        }
}
