package dev.mappinglens.ingestion

import org.slf4j.LoggerFactory

/**
 * Merges several mojmap tiny files (Mojang ships separate client and server mappings, each
 * `official -> named`) into one [ParsedMappings], keyed by obfuscated/official name. A class or
 * member present in only one of client/server must survive the merge.
 */
object MojmapMerge {
    private val log = LoggerFactory.getLogger(MojmapMerge::class.java)

    fun merge(mappings: List<ParsedMappings>): ParsedMappings? {
        if (mappings.isEmpty()) return null
        if (mappings.size == 1) return mappings.single()

        val namespaces = mappings.first().namespaces
        val classesByObf = linkedMapOf<String, ParsedClass>()
        for (mapping in mappings) {
            if (mapping.namespaces != namespaces) {
                log.warn(
                    "Skipping mapping merge input with namespaces {} because expected {}",
                    mapping.namespaces,
                    namespaces,
                )
                continue
            }
            for (cls in mapping.classes) {
                val key = cls.names.getOrNull(0) ?: cls.names.filterNotNull().joinToString("|")
                if (key.isBlank()) continue
                classesByObf[key] = classesByObf[key]?.let { mergeClass(it, cls) } ?: cls
            }
        }
        return ParsedMappings(namespaces, classesByObf.values.toList())
    }

    private fun mergeClass(left: ParsedClass, right: ParsedClass): ParsedClass = ParsedClass(
        names = mergeNullableLists(left.names, right.names),
        methods = mergeMembers(left.methods, right.methods, ::ParsedMethod),
        fields = mergeMembers(left.fields, right.fields, ::ParsedField),
    )

    /** Methods and fields carry the same names+descs shape, so one merge serves both. */
    private fun <T : HasNamesDescs> mergeMembers(
        left: List<T>,
        right: List<T>,
        create: (List<String?>, List<String?>) -> T,
    ): List<T> {
        val merged = linkedMapOf<Pair<String, String>, T>()
        for (member in left + right) {
            val key = memberKey(member.names, member.descs)
            val previous = merged[key]
            merged[key] = if (previous == null) member else create(
                mergeNullableLists(previous.names, member.names),
                mergeNullableLists(previous.descs, member.descs),
            )
        }
        return merged.values.toList()
    }

    private fun memberKey(names: List<String?>, descs: List<String?>): Pair<String, String> {
        val name = names.getOrNull(0) ?: names.filterNotNull().joinToString("|")
        val desc = descs.getOrNull(0) ?: descs.filterNotNull().joinToString("|")
        return name to desc
    }

    private fun mergeNullableLists(left: List<String?>, right: List<String?>): List<String?> {
        val size = maxOf(left.size, right.size)
        return (0 until size).map { idx -> left.getOrNull(idx) ?: right.getOrNull(idx) }
    }
}
