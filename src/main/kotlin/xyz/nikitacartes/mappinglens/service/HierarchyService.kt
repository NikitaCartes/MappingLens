package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.lruCache
import xyz.nikitacartes.mappinglens.db.ClassDecl
import xyz.nikitacartes.mappinglens.db.DeclarationLookup
import xyz.nikitacartes.mappinglens.model.HierarchyEdge
import xyz.nikitacartes.mappinglens.model.HierarchyNode
import xyz.nikitacartes.mappinglens.model.HierarchyResponse
import org.objectweb.asm.Opcodes

/**
 * Class inheritance hierarchy (supertypes + subtypes) for a version/namespace, read a class at a time
 * out of the prebuilt declaration index and scanned out of the pre-remapped named jar for a version
 * that index does not cover. The subtype edges are stored with each class, so walking down costs a
 * lookup for each class reached instead of a pass over the whole jar.
 */
class HierarchyService(private val config: AppConfig) {

    /** Whole-jar scans of the versions the declaration index does not cover. */
    private val fallback = lruCache<Pair<String, String>, Map<String, ClassDecl>>(config.cache.declarations)

    fun hierarchy(versionId: String, className: String, namespace: String): HierarchyResponse? {
        if (namespace != "yarn" && namespace != "mojmap") return null
        DeclarationLookup(config, versionId, namespace, fallback).use { decls ->
            if (!decls.available) return null
            return computeHierarchy(decls::get, versionId, namespace, className)
        }
    }

    companion object {
        private fun supertypes(decl: ClassDecl): List<String> =
            decl.supertypes.filter { it != "java/lang/Object" }

        /**
         * Graph of `root`'s ancestors (walked up via super+interfaces) and descendants (walked down
         * via the stored subtype edges). Pure over [lookup] so it is unit-testable without a jar.
         * Returns null if `root` is absent.
         */
        fun computeHierarchy(
            lookup: (String) -> ClassDecl?,
            versionId: String,
            namespace: String,
            root: String,
        ): HierarchyResponse? {
            if (lookup(root) == null) return null

            val nodes = LinkedHashSet<String>()
            val edges = LinkedHashSet<Pair<String, String>>()

            fun addUp(name: String) {
                if (!nodes.add(name)) return
                val decl = lookup(name) ?: return
                for (parent in supertypes(decl)) {
                    if (lookup(parent) != null) {
                        edges.add(parent to name)
                        addUp(parent)
                    }
                }
            }

            fun addDown(name: String) {
                if (!nodes.add(name)) return
                for (child in lookup(name)?.subtypes.orEmpty()) {
                    edges.add(name to child)
                    addDown(child)
                }
            }

            addUp(root)
            // Re-seed the root's subtypes: addUp already marked root visited, so addDown(root) would no-op.
            for (child in lookup(root)?.subtypes.orEmpty()) {
                edges.add(root to child)
                addDown(child)
            }

            val nodeDtos = nodes.map { name ->
                val decl = lookup(name)
                HierarchyNode(
                    name = name,
                    simpleName = name.substringAfterLast('/'),
                    isInterface = decl != null && decl.access and Opcodes.ACC_INTERFACE != 0,
                    isAbstract = decl != null && decl.access and Opcodes.ACC_ABSTRACT != 0,
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
