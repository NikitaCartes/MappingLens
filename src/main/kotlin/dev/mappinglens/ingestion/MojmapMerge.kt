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
        methods = mergeMethods(left.methods, right.methods),
        fields = mergeFields(left.fields, right.fields),
    )

    private fun mergeMethods(left: List<ParsedMethod>, right: List<ParsedMethod>): List<ParsedMethod> {
        val merged = linkedMapOf<Pair<String, String>, ParsedMethod>()
        fun add(method: ParsedMethod) {
            val key = memberKey(method.names, method.descs)
            merged[key] = merged[key]?.let {
                ParsedMethod(
                    names = mergeNullableLists(it.names, method.names),
                    descs = mergeNullableLists(it.descs, method.descs),
                )
            } ?: method
        }
        left.forEach(::add)
        right.forEach(::add)
        return merged.values.toList()
    }

    private fun mergeFields(left: List<ParsedField>, right: List<ParsedField>): List<ParsedField> {
        val merged = linkedMapOf<Pair<String, String>, ParsedField>()
        fun add(field: ParsedField) {
            val key = memberKey(field.names, field.descs)
            merged[key] = merged[key]?.let {
                ParsedField(
                    names = mergeNullableLists(it.names, field.names),
                    descs = mergeNullableLists(it.descs, field.descs),
                )
            } ?: field
        }
        left.forEach(::add)
        right.forEach(::add)
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
