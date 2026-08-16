package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.model.HierarchyEdge
import xyz.nikitacartes.mappinglens.model.HierarchyNode
import xyz.nikitacartes.mappinglens.model.HierarchyResponse
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Class inheritance hierarchy (supertypes + subtypes) for a version/namespace, read from the
 * pre-remapped named `.class` jar via ASM. Only the class header is parsed (super/interfaces/access),
 * so a whole-jar scan is cheap; the resulting graph is cached per (version, namespace) — the index is
 * immutable for the server's lifetime, mirroring BytecodeService's name-map cache.
 */
class HierarchyService(private val config: AppConfig) {

    private val cache = ConcurrentHashMap<Pair<String, String>, Map<String, ClassInfo>>()

    fun hierarchy(versionId: String, className: String, namespace: String): HierarchyResponse? {
        if (namespace != "yarn" && namespace != "mojmap") return null
        val classes = cache.computeIfAbsent(versionId to namespace) { (v, ns) -> loadClasses(v, ns) }
        return computeHierarchy(classes, versionId, namespace, className)
    }

    private fun loadClasses(versionId: String, namespace: String): Map<String, ClassInfo> {
        val jar = config.sources.remappedJar(versionId, namespace) ?: return emptyMap()
        val classes = HashMap<String, ClassInfo>()
        ZipFile(jar.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                val reader = zip.getInputStream(entry).use { ClassReader(it.readBytes()) }
                classes[reader.className] = ClassInfo(reader.superName, reader.interfaces.toList(), reader.access)
            }
        }
        return classes
    }

    companion object {
        /** One class's header, in the requested namespace's names. */
        data class ClassInfo(val superName: String?, val interfaces: List<String>, val access: Int)

        private fun supertypes(info: ClassInfo): List<String> =
            (listOfNotNull(info.superName) + info.interfaces).filter { it != "java/lang/Object" }

        /**
         * Graph of `root`'s ancestors (walked up via super+interfaces) and descendants (walked down via
         * the reverse adjacency). Pure over the class map so it is unit-testable without a jar. Returns
         * null if `root` is absent.
         */
        fun computeHierarchy(
            classes: Map<String, ClassInfo>,
            versionId: String,
            namespace: String,
            root: String,
        ): HierarchyResponse? {
            if (!classes.containsKey(root)) return null

            val childrenOf = HashMap<String, MutableList<String>>()
            for ((name, info) in classes) {
                for (parent in supertypes(info)) {
                    if (classes.containsKey(parent)) childrenOf.getOrPut(parent) { mutableListOf() }.add(name)
                }
            }

            val nodes = LinkedHashSet<String>()
            val edges = LinkedHashSet<Pair<String, String>>()

            fun addUp(name: String) {
                if (!nodes.add(name)) return
                val info = classes[name] ?: return
                for (parent in supertypes(info)) {
                    if (classes.containsKey(parent)) {
                        edges.add(parent to name)
                        addUp(parent)
                    }
                }
            }

            fun addDown(name: String) {
                if (!nodes.add(name)) return
                for (child in childrenOf[name].orEmpty()) {
                    edges.add(name to child)
                    addDown(child)
                }
            }

            addUp(root)
            // Re-seed the root's subtypes: addUp already marked root visited, so addDown(root) would no-op.
            for (child in childrenOf[root].orEmpty()) {
                edges.add(root to child)
                addDown(child)
            }

            val nodeDtos = nodes.map { name ->
                val info = classes[name]
                HierarchyNode(
                    name = name,
                    simpleName = name.substringAfterLast('/'),
                    isInterface = info != null && info.access and Opcodes.ACC_INTERFACE != 0,
                    isAbstract = info != null && info.access and Opcodes.ACC_ABSTRACT != 0,
                )
            }
            return HierarchyResponse(
                version = versionId,
                namespace = namespace,
                root = root,
                nodes = nodeDtos,
                edges = edges.map { HierarchyEdge(it.first, it.second) },
            )
        }
    }
}
