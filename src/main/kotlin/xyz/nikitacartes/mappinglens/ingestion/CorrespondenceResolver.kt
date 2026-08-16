package xyz.nikitacartes.mappinglens.ingestion

/**
 * Holds resolved unified mapping for a single class and its members.
 *
 * [presence] records on which side of the Yarn<->Mojmap correspondence the class exists:
 * [PRESENCE_BOTH], [PRESENCE_YARN_ONLY] (Fabric side only — yarn and/or intermediary, no mojmap),
 * or [PRESENCE_MOJMAP_ONLY]. It is what makes the Compare feature able to surface classes that
 * exist in one namespace but not the other.
 */
data class UnifiedClassEntry(
    val obfName: String?,
    val intermediaryName: String?,
    val yarnName: String?,
    val mojmapName: String?,
    val methods: List<UnifiedMemberEntry>,
    val fields: List<UnifiedMemberEntry>,
    val presence: String,
)

data class UnifiedMemberEntry(
    val obfName: String?,
    val obfDesc: String?,
    val intermediaryName: String?,
    val intermediaryDesc: String?,
    val yarnName: String?,
    val mojmapName: String?,
)

/** (official name, official descriptor) — the member join key. */
private typealias MemberKey = Pair<String?, String?>

/**
 * Joins per-namespace mappings (intermediary/yarn/mojmap) by obfuscated/official name into a
 * unified view per class, method and field.
 *
 * This is a **full outer join** over the union of obfuscated keys across all three sources, so a
 * class (or member) that exists in mojmap but not in yarn — and vice versa — is preserved. A prior
 * yarn-left-join silently dropped mojmap-only classes (e.g. 5747 yarn vs 5445 mojmap classes in
 * 1.16.5), which would corrupt the Compare feature.
 *
 * Join keys:
 *  - class:  the official (obfuscated) class name (column 0 in every source).
 *  - member: the (official name, official descriptor) pair. The descriptor is mandatory — a single
 *    obfuscated class can hold many same-named overloads distinguished only by descriptor. Within
 *    one version every source describes the same obf jar, so descriptors are byte-identical and no
 *    descriptor remapping is needed for the join.
 */
object CorrespondenceResolver {

    const val PRESENCE_BOTH = "both"
    const val PRESENCE_YARN_ONLY = "yarn_only"
    const val PRESENCE_MOJMAP_ONLY = "mojmap_only"

    fun resolve(
        intermediary: ParsedMappings?,
        yarn: ParsedMappings?,
        mojmap: ParsedMappings?,
    ): List<UnifiedClassEntry> {
        // Column indices of the interesting namespace within each source.
        val yarnIntermIdx = yarn?.let { it.namespaces.indexOf("intermediary").let { i -> if (i >= 0) i else 1 } } ?: -1
        val yarnNamedIdx = yarn?.let { namedIndex(it.namespaces, "yarn") } ?: -1
        val intermIdx = intermediary?.let { namedIndex(it.namespaces, "intermediary") } ?: -1
        val mojNamedIdx = mojmap?.let { namedIndex(it.namespaces, "mojmap", "mojang") } ?: -1

        // obf -> class for each source.
        val yarnByObf = indexClassesByObf(yarn)
        val intermByObf = indexClassesByObf(intermediary)
        val mojByObf = indexClassesByObf(mojmap)

        // Union of obf keys, preserving a stable order: yarn first, then intermediary-only, then mojmap-only.
        val obfKeys = LinkedHashSet<String>()
        obfKeys += yarnByObf.keys
        obfKeys += intermByObf.keys
        obfKeys += mojByObf.keys

        return obfKeys.map { obf ->
            val yarnCls = yarnByObf[obf]
            val intermCls = intermByObf[obf]
            val mojCls = mojByObf[obf]

            val intermediaryName = yarnCls?.names?.getOrNull(yarnIntermIdx)
                ?: intermCls?.names?.getOrNull(intermIdx)
            val yarnName = yarnCls?.names?.getOrNull(yarnNamedIdx)
            val mojmapName = if (mojNamedIdx >= 0) mojCls?.names?.getOrNull(mojNamedIdx) else null

            val inFabricSide = yarnCls != null || intermCls != null
            val presence = when {
                inFabricSide && mojCls != null -> PRESENCE_BOTH
                mojCls != null -> PRESENCE_MOJMAP_ONLY
                else -> PRESENCE_YARN_ONLY
            }

            UnifiedClassEntry(
                obfName = obf,
                intermediaryName = intermediaryName,
                yarnName = yarnName,
                mojmapName = mojmapName,
                methods = joinMembers(
                    yarnCls?.methods, intermCls?.methods, mojCls?.methods,
                    yarnIntermIdx, yarnNamedIdx, intermIdx, mojNamedIdx,
                ),
                fields = joinMembers(
                    yarnCls?.fields, intermCls?.fields, mojCls?.fields,
                    yarnIntermIdx, yarnNamedIdx, intermIdx, mojNamedIdx,
                ),
                presence = presence,
            )
        }
    }

    /**
     * Full outer join of one class's members across the three sources, keyed by
     * (official name, official descriptor). Works uniformly for methods and fields since both
     * carry name+descriptor lists.
     */
    private fun joinMembers(
        yarnMembers: List<HasNamesDescs>?,
        intermMembers: List<HasNamesDescs>?,
        mojMembers: List<HasNamesDescs>?,
        yarnIntermIdx: Int,
        yarnNamedIdx: Int,
        intermIdx: Int,
        mojNamedIdx: Int,
    ): List<UnifiedMemberEntry> {
        val yarnByKey = indexMembersByObf(yarnMembers)
        val intermByKey = indexMembersByObf(intermMembers)
        val mojByKey = indexMembersByObf(mojMembers)

        val keys = LinkedHashSet<MemberKey>()
        keys += yarnByKey.keys
        keys += intermByKey.keys
        keys += mojByKey.keys

        return keys.map { key ->
            val ym = yarnByKey[key]
            val im = intermByKey[key]
            val mm = mojByKey[key]
            UnifiedMemberEntry(
                obfName = key.first,
                obfDesc = key.second,
                intermediaryName = ym?.names?.getOrNull(yarnIntermIdx) ?: im?.names?.getOrNull(intermIdx),
                intermediaryDesc = ym?.descs?.getOrNull(yarnIntermIdx) ?: im?.descs?.getOrNull(intermIdx),
                yarnName = ym?.names?.getOrNull(yarnNamedIdx),
                mojmapName = if (mojNamedIdx >= 0) mm?.names?.getOrNull(mojNamedIdx) else null,
            )
        }
    }

    private fun indexClassesByObf(mappings: ParsedMappings?): Map<String, ParsedClass> {
        if (mappings == null) return emptyMap()
        val byObf = LinkedHashMap<String, ParsedClass>()
        for (cls in mappings.classes) {
            val obf = cls.names.getOrNull(0) ?: continue
            byObf.putIfAbsent(obf, cls)
        }
        return byObf
    }

    private fun indexMembersByObf(members: List<HasNamesDescs>?): Map<MemberKey, HasNamesDescs> {
        if (members == null) return emptyMap()
        val byKey = LinkedHashMap<MemberKey, HasNamesDescs>()
        for (m in members) {
            val key: MemberKey = m.names.getOrNull(0) to m.descs.getOrNull(0)
            byKey.putIfAbsent(key, m)
        }
        return byKey
    }

    /**
     * Index of the "named" destination namespace. Falls back to the last namespace when none of the
     * known aliases match (older yarn/mojmap files name it simply "named").
     */
    private fun namedIndex(namespaces: List<String>, vararg aliases: String): Int {
        val explicit = namespaces.indexOfFirst { it == "named" || it in aliases }
        return if (explicit >= 0) explicit else namespaces.size - 1
    }
}

/** Common shape of [ParsedMethod]/[ParsedField] so member joins are written once. */
internal interface HasNamesDescs {
    val names: List<String?>
    val descs: List<String?>
}
