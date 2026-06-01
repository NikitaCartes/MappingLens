package dev.mappinglens.ingestion

/**
 * Holds resolved unified mapping for a single class and its members.
 */
data class UnifiedClassEntry(
    val obfName: String?,
    val intermediaryName: String?,
    val yarnName: String?,
    val mojmapName: String?,
    val methods: List<UnifiedMemberEntry>,
    val fields: List<UnifiedMemberEntry>,
)

data class UnifiedMemberEntry(
    val obfName: String?,
    val obfDesc: String?,
    val intermediaryName: String?,
    val intermediaryDesc: String?,
    val yarnName: String?,
    val mojmapName: String?,
)

/**
 * Joins per-namespace mappings (intermediary/yarn/mojmap) by obfuscated/official name to produce
 * a unified view per class, method, field.
 *
 * Strategy:
 *  - Yarn .tiny is full chain: official ↔ intermediary ↔ named (yarn). We can take it as base.
 *  - If yarn is missing but intermediary present, base on intermediary (only obf↔intermediary).
 *  - mojmap .tiny: official ↔ named (mojmap). Joined by obfuscated (official) name.
 */
object CorrespondenceResolver {

    fun resolve(
        intermediary: ParsedMappings?,
        yarn: ParsedMappings?,
        mojmap: ParsedMappings?,
    ): List<UnifiedClassEntry> {
        // Build mojmap obf -> mojName lookup
        val mojClassByObf = HashMap<String, ParsedClass>()
        val mojNamespaces = mojmap?.namespaces.orEmpty()
        val mojNamedIdx = mojNamespaces.indexOfFirst { it == "named" || it == "mojmap" || it == "mojang" }
            .let { if (it >= 0) it else if (mojNamespaces.size >= 2) 1 else -1 }
        if (mojmap != null && mojNamedIdx >= 0) {
            for (cls in mojmap.classes) {
                val obf = cls.names.getOrNull(0) ?: continue
                mojClassByObf[obf] = cls
            }
        }

        val intermediaryByObf = HashMap<String, ParsedClass>()
        val intermNamespaces = intermediary?.namespaces.orEmpty()
        val intermIdx = intermNamespaces.indexOfFirst { it == "intermediary" }
            .let { if (it >= 0) it else if (intermNamespaces.size >= 2) 1 else -1 }
        if (intermediary != null && intermIdx >= 0) {
            for (cls in intermediary.classes) {
                val obf = cls.names.getOrNull(0) ?: continue
                intermediaryByObf[obf] = cls
            }
        }

        // Choose primary source: prefer yarn (full chain), fall back to intermediary, fall back to mojmap-only.
        return when {
            yarn != null -> joinFromYarn(yarn, intermediary, intermIdx, mojmap, mojNamedIdx, intermediaryByObf, mojClassByObf)
            intermediary != null -> joinFromIntermediaryOnly(intermediary, intermIdx, mojmap, mojNamedIdx, mojClassByObf)
            mojmap != null -> joinFromMojOnly(mojmap, mojNamedIdx)
            else -> emptyList()
        }
    }

    private fun joinFromYarn(
        yarn: ParsedMappings,
        intermediary: ParsedMappings?,
        intermIdx: Int,
        mojmap: ParsedMappings?,
        mojNamedIdx: Int,
        intermediaryByObf: Map<String, ParsedClass>,
        mojClassByObf: Map<String, ParsedClass>,
    ): List<UnifiedClassEntry> {
        val ns = yarn.namespaces
        val officialIdx = 0
        val yarnIntermIdx = ns.indexOf("intermediary").let { if (it >= 0) it else 1 }
        val yarnNamedIdx = ns.indexOfFirst { it == "named" || it == "yarn" }
            .let { if (it >= 0) it else ns.size - 1 }

        return yarn.classes.map { cls ->
            val obf = cls.names.getOrNull(officialIdx)
            val interm = cls.names.getOrNull(yarnIntermIdx)
            val yarnName = cls.names.getOrNull(yarnNamedIdx)
            val mojClass = obf?.let { mojClassByObf[it] }
            val mojmapName = if (mojNamedIdx >= 0) mojClass?.names?.getOrNull(mojNamedIdx) else null

            // index members of mojmap class by (obfName, obfDesc) for join
            val mojMethods = HashMap<Pair<String?, String?>, ParsedMethod>()
            val mojFields = HashMap<Pair<String?, String?>, ParsedField>()
            mojClass?.methods?.forEach {
                mojMethods[it.names.getOrNull(0) to it.descs.getOrNull(0)] = it
            }
            mojClass?.fields?.forEach {
                mojFields[it.names.getOrNull(0) to it.descs.getOrNull(0)] = it
            }

            val methods = cls.methods.map { m ->
                val mObf = m.names.getOrNull(officialIdx)
                val mObfDesc = m.descs.getOrNull(officialIdx)
                val mInterm = m.names.getOrNull(yarnIntermIdx)
                val mIntermDesc = m.descs.getOrNull(yarnIntermIdx)
                val mYarn = m.names.getOrNull(yarnNamedIdx)
                val moj = mojMethods[mObf to mObfDesc]
                val mMoj = if (mojNamedIdx >= 0) moj?.names?.getOrNull(mojNamedIdx) else null
                UnifiedMemberEntry(mObf, mObfDesc, mInterm, mIntermDesc, mYarn, mMoj)
            }
            val fields = cls.fields.map { f ->
                val fObf = f.names.getOrNull(officialIdx)
                val fObfDesc = f.descs.getOrNull(officialIdx)
                val fInterm = f.names.getOrNull(yarnIntermIdx)
                val fIntermDesc = f.descs.getOrNull(yarnIntermIdx)
                val fYarn = f.names.getOrNull(yarnNamedIdx)
                val moj = mojFields[fObf to fObfDesc]
                val fMoj = if (mojNamedIdx >= 0) moj?.names?.getOrNull(mojNamedIdx) else null
                UnifiedMemberEntry(fObf, fObfDesc, fInterm, fIntermDesc, fYarn, fMoj)
            }
            UnifiedClassEntry(obf, interm, yarnName, mojmapName, methods, fields)
        }
    }

    private fun joinFromIntermediaryOnly(
        intermediary: ParsedMappings,
        intermIdx: Int,
        mojmap: ParsedMappings?,
        mojNamedIdx: Int,
        mojClassByObf: Map<String, ParsedClass>,
    ): List<UnifiedClassEntry> {
        return intermediary.classes.map { cls ->
            val obf = cls.names.getOrNull(0)
            val interm = cls.names.getOrNull(intermIdx)
            val mojClass = obf?.let { mojClassByObf[it] }
            val mojmapName = if (mojNamedIdx >= 0) mojClass?.names?.getOrNull(mojNamedIdx) else null

            val mojMethods = HashMap<Pair<String?, String?>, ParsedMethod>()
            val mojFields = HashMap<Pair<String?, String?>, ParsedField>()
            mojClass?.methods?.forEach {
                mojMethods[it.names.getOrNull(0) to it.descs.getOrNull(0)] = it
            }
            mojClass?.fields?.forEach {
                mojFields[it.names.getOrNull(0) to it.descs.getOrNull(0)] = it
            }

            val methods = cls.methods.map { m ->
                val mObf = m.names.getOrNull(0)
                val mObfDesc = m.descs.getOrNull(0)
                val mInterm = m.names.getOrNull(intermIdx)
                val mIntermDesc = m.descs.getOrNull(intermIdx)
                val moj = mojMethods[mObf to mObfDesc]
                val mMoj = if (mojNamedIdx >= 0) moj?.names?.getOrNull(mojNamedIdx) else null
                UnifiedMemberEntry(mObf, mObfDesc, mInterm, mIntermDesc, null, mMoj)
            }
            val fields = cls.fields.map { f ->
                val fObf = f.names.getOrNull(0)
                val fObfDesc = f.descs.getOrNull(0)
                val fInterm = f.names.getOrNull(intermIdx)
                val fIntermDesc = f.descs.getOrNull(intermIdx)
                val moj = mojFields[fObf to fObfDesc]
                val fMoj = if (mojNamedIdx >= 0) moj?.names?.getOrNull(mojNamedIdx) else null
                UnifiedMemberEntry(fObf, fObfDesc, fInterm, fIntermDesc, null, fMoj)
            }
            UnifiedClassEntry(obf, interm, null, mojmapName, methods, fields)
        }
    }

    private fun joinFromMojOnly(mojmap: ParsedMappings, mojNamedIdx: Int): List<UnifiedClassEntry> {
        if (mojNamedIdx < 0) return emptyList()
        return mojmap.classes.map { cls ->
            val obf = cls.names.getOrNull(0)
            val moj = cls.names.getOrNull(mojNamedIdx)
            val methods = cls.methods.map { m ->
                UnifiedMemberEntry(
                    obfName = m.names.getOrNull(0),
                    obfDesc = m.descs.getOrNull(0),
                    intermediaryName = null, intermediaryDesc = null,
                    yarnName = null,
                    mojmapName = m.names.getOrNull(mojNamedIdx),
                )
            }
            val fields = cls.fields.map { f ->
                UnifiedMemberEntry(
                    obfName = f.names.getOrNull(0),
                    obfDesc = f.descs.getOrNull(0),
                    intermediaryName = null, intermediaryDesc = null,
                    yarnName = null,
                    mojmapName = f.names.getOrNull(mojNamedIdx),
                )
            }
            UnifiedClassEntry(obf, null, null, moj, methods, fields)
        }
    }
}
