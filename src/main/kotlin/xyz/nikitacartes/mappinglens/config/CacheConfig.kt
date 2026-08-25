package xyz.nikitacartes.mappinglens.config

import java.util.Collections

/**
 * Entry limits for everything the server holds in memory between requests.
 *
 * Every one of these caches is keyed on a version, so it grows with the number of versions a client
 * walks over rather than with the request rate. Left unbounded they fill the heap: sixteen reference
 * indexes alone reached 1557 MB against the 4 GB the compose file gives the server.
 *
 * Each cache gets a count of its own rather than sharing one byte budget, because the entries differ
 * in size by four orders of magnitude, from a resolved jar path to a 61 MB reference index, and a
 * byte budget would need a size estimate for every value. Raise a count to trade heap for the work
 * of rebuilding an entry; the defaults suit the 4 GB heap the compose file sets.
 *
 * The sizes below are the retained heap of one entry, measured over 1.16.5, 1.21.4 and 1.21.11 in
 * the yarn namespace. Mojmap costs the same.
 */
data class CacheConfig(
    /** Reverse-reference indexes scanned out of a jar, 34 to 61 MB each. */
    val referenceIndexes: Int = 8,
    /**
     * Declaration graphs scanned out of a jar, 10 to 19 MB each. Only versions the declaration index
     * does not cover reach this cache, so a complete index leaves it empty.
     */
    val declarations: Int = 4,
    /** Obfuscated-to-named maps read out of the main index, 17 to 32 MB per (version, namespace). */
    val nameMaps: Int = 4,
    /**
     * JavaParser symbol solvers, which hold one open jar handle per library of the version.
     *
     * This count does not bound the heap, and no count can. A fresh solver is 12 MB and grows as it
     * resolves classes, to 57 MB after 240 of them, with no plateau in that range: browsing a whole
     * version is on the order of 0.5 to 1 GB. Evicting a solver returns none of it, because
     * `JavaParserFacade.instances` is a static `WeakHashMap<TypeSolver, JavaParserFacade>` whose
     * value holds its own key, so no entry of that map is ever collected. Measured: after one solver
     * grew to 36.4 MB, dropping it returned 0 and `JavaParserFacade.clearInstances()` returned
     * 28.3 MB. Calling that is the only lever JavaParser offers, and nothing calls it yet.
     */
    val symbolSolvers: Int = 4,
    /**
     * Resolved token lists, one per (version, class, namespace), each holding the class source.
     *
     * The entry size spans two orders of magnitude, because the source dominates it: 40 KB on
     * average over classes taken across the whole size range, 970 KB over the 20 largest classes of
     * a version. This limit is therefore worth 20 MB of heap for ordinary browsing and 474 MB in the
     * worst case a client can reach.
     */
    val tokens: Int = 500,
    /** Resolved jar paths and library jar lists, one per version. Kilobytes in total. */
    val paths: Int = 2000,
) {
    init {
        require(
            listOf(referenceIndexes, declarations, nameMaps, symbolSolvers, tokens, paths).all { it > 0 }
        ) { "every cache limit must be at least 1, but was: $this" }
    }
}

/**
 * An access-ordered map that evicts its eldest entry once it holds more than [maxEntries].
 *
 * ponytail: LinkedHashMap in access order is the JDK's LRU, so no cache library is needed. Callers
 * read and write it with `get` and `put` rather than `computeIfAbsent`, so a miss does not hold the
 * monitor for the seconds an entry takes to build. Two requests that miss at once both build and the
 * second write wins; every value here is derived from an immutable index, so a double build wastes
 * work but cannot give a wrong answer.
 */
fun <K, V> lruCache(maxEntries: Int): MutableMap<K, V> =
    Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean = size > maxEntries
        }
    )
