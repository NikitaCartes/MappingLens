package dev.mappinglens.data

import dev.mappinglens.ingestion.CorrespondenceResolver
import dev.mappinglens.ingestion.MojmapMerge
import dev.mappinglens.ingestion.TinyV2Parser
import dev.mappinglens.ingestion.UnifiedClassEntry
import dev.mappinglens.ingestion.UnobfuscatedJarScanner
import dev.mappinglens.version.VersionCatalog
import dev.mappinglens.version.VersionMeta
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Read-only access to the GitCraft data store, keyed by **canonical version id** (the space-form
 * name; see REWRITE_PLAN.md section 5–section 6). This is the single place that knows the on-disk layout.
 *
 * Mapping tiny files have deterministic names, so they are resolved by membership in a cached
 * directory listing. Jar files embed unpredictable ids (`...-id_eb144c0d-6f27430b.jar`), so they are
 * resolved by glob over the (small) per-version directory and cached. Nothing here is ever written.
 */
class GitCraftStore(
    val artifactStore: Path,
    val intermediaryMappingsDir: Path,
    val catalog: VersionCatalog = VersionCatalog.load(artifactStore),
) {
    private val log = LoggerFactory.getLogger(GitCraftStore::class.java)

    private val mappingsDir = artifactStore.resolve("mappings")
    private val decompiledDir = artifactStore.resolve("decompiled")
    private val remappedDir = artifactStore.resolve("remapped-mc")
    private val mcVersionsDir = artifactStore.resolve("mc-versions")

    // The mappings dir holds thousands of files for ~600 versions; list it once.
    private val mappingNames: Set<String> by lazy { listNames(mappingsDir) }
    private val intermediaryNames: Set<String> by lazy { listNames(intermediaryMappingsDir) }

    private val jarCache = ConcurrentHashMap<String, Optional<Path>>()

    /** Resolved on-disk sources for one version. */
    data class VersionSources(
        val canonical: String,
        val intermediary: Path?,
        val yarn: Path?,
        val mojmaps: List<Path>,
        /** True for Mojang's pre-deobfuscated releases (26.x / `_unobfuscated`) with no tiny mappings. */
        val unobfuscated: Boolean,
        val unobfuscatedJar: Path?,
        val meta: VersionMeta?,
    ) {
        val hasYarn get() = yarn != null
        val hasIntermediary get() = intermediary != null
        val hasMojmap get() = mojmaps.isNotEmpty() || unobfuscated
        val hasAny get() = hasYarn || hasIntermediary || mojmaps.isNotEmpty() || (unobfuscated && unobfuscatedJar != null)
    }

    // ---------------------------------------------------------------- mapping tiny resolution

    /** Intermediary mappings (tiny v1). Prefers the dedicated intermediary repo over artifact-store. */
    fun intermediaryTiny(version: String): Path? {
        if ("$version.tiny" in intermediaryNames) return intermediaryMappingsDir.resolve("$version.tiny")
        if ("$version-intermediary.tiny" in intermediaryNames) return intermediaryMappingsDir.resolve("$version-intermediary.tiny")
        if ("$version-intermediary.tiny" in mappingNames) return mappingsDir.resolve("$version-intermediary.tiny")
        return null
    }

    /** Yarn merged mappings (tiny v2, official->intermediary->named), highest build number. */
    fun yarnTiny(version: String): Path? {
        val prefix = "$version-yarn-build."
        return mappingNames.asSequence()
            .filter { it.startsWith(prefix) && it.endsWith(".tiny") && !it.contains("-constants") && !it.contains("-unpick") }
            .maxByOrNull { it.removePrefix(prefix).removeSuffix(".tiny").toIntOrNull() ?: -1 }
            ?.let { mappingsDir.resolve(it) }
    }

    /** Mojmap tiny files (official->named): a combined `-moj` or the separate client+server pair. */
    fun mojmapTinies(version: String): List<Path> =
        listOf("$version-moj.tiny", "$version-client-moj.tiny", "$version-server-moj.tiny")
            .filter { it in mappingNames }
            .map { mappingsDir.resolve(it) }

    // ---------------------------------------------------------------- jar resolution (glob + cache)

    /** Decompiled `.java` source jar for yarn/mojmap. */
    fun decompiledJar(version: String, namespace: String): Path? =
        cachedJar("dec|$version|$namespace") { firstJar(decompiledDir.resolve(version), "merged-map_$namespace") }

    /** Remapped (named) `.class` jar for yarn/mojmap — used for bytecode. */
    fun remappedJar(version: String, namespace: String): Path? =
        cachedJar("rem|$version|$namespace") { firstJar(remappedDir.resolve(version), "merged-remapped-map_$namespace") }

    /** Obfuscated merged client+server jar. */
    fun obfMergedJar(version: String): Path? =
        cachedJar("obf|$version") { firstJar(mcVersionsDir.resolve(version), "merged-") }

    private fun cachedJar(key: String, resolve: () -> Path?): Path? =
        jarCache.computeIfAbsent(key) { Optional.ofNullable(resolve()) }.orElse(null)

    private fun firstJar(dir: Path, prefix: String): Path? {
        if (!dir.exists() || !dir.isDirectory()) return null
        return Files.list(dir).use { stream ->
            stream.filter { it.name.startsWith(prefix) && it.name.endsWith(".jar") }
                .sorted(compareBy { it.name })
                .findFirst()
                .orElse(null)
        }
    }

    // ---------------------------------------------------------------- enumeration

    /**
     * All resolvable canonical version ids, ordered by semver. The id universe is the union of the
     * version catalog, the artifact-store version folders, and the mapping file prefixes; ids that
     * resolve to no usable data are dropped.
     */
    fun versionIds(): List<String> {
        val ids = LinkedHashSet<String>()
        ids += catalog.all().map { it.canonical }
        ids += subdirNames(decompiledDir)
        ids += subdirNames(remappedDir)
        ids += subdirNames(mcVersionsDir)
        ids += intermediaryVersionNames()
        ids += artifactMappingVersionNames()

        val resolvable = ids.filter { resolve(it).hasAny }
        log.info("Enumerated {} resolvable versions (of {} candidate ids)", resolvable.size, ids.size)
        return catalog.sorted(resolvable)
    }

    fun resolve(version: String): VersionSources {
        val intermediary = intermediaryTiny(version)
        val yarn = yarnTiny(version)
        val mojmaps = mojmapTinies(version)
        if (intermediary == null && yarn == null && mojmaps.isEmpty()) {
            val jar = unobfuscatedJar(version)
            if (jar != null) {
                return VersionSources(version, null, null, emptyList(), unobfuscated = true, unobfuscatedJar = jar, meta = catalog.get(version))
            }
        }
        return VersionSources(version, intermediary, yarn, mojmaps, unobfuscated = false, unobfuscatedJar = null, meta = catalog.get(version))
    }

    private fun unobfuscatedJar(version: String): Path? =
        remappedJar(version, "mojmap") ?: obfMergedJar(version)

    // ---------------------------------------------------------------- parse convenience

    /**
     * Resolve, parse and join all of a version's mappings into the unified obf-keyed view. Used by
     * the offline indexer; the server never calls this.
     */
    fun parseUnified(version: String): List<UnifiedClassEntry> {
        val src = resolve(version)
        if (src.unobfuscated && src.unobfuscatedJar != null) {
            return UnobfuscatedJarScanner.scan(src.unobfuscatedJar)
        }
        val intermediary = src.intermediary?.let { TinyV2Parser.parse(it) }
        val yarn = src.yarn?.let { TinyV2Parser.parse(it) }
        val mojmap = MojmapMerge.merge(src.mojmaps.map { TinyV2Parser.parse(it) })
        return CorrespondenceResolver.resolve(intermediary, yarn, mojmap)
    }

    // ---------------------------------------------------------------- helpers

    private fun listNames(dir: Path): Set<String> =
        if (dir.isDirectory()) Files.list(dir).use { it.map(Path::name).collect(Collectors.toSet()) } else emptySet()

    private fun subdirNames(dir: Path): List<String> =
        if (dir.isDirectory()) Files.list(dir).use { s -> s.filter { it.isDirectory() }.map(Path::name).collect(Collectors.toList()) } else emptyList()

    private fun intermediaryVersionNames(): List<String> = intermediaryNames.mapNotNull { n ->
        when {
            n.endsWith("-intermediary-v1.tiny") -> null
            n.endsWith("-intermediary.tiny") -> n.removeSuffix("-intermediary.tiny")
            n.endsWith(".tiny") -> n.removeSuffix(".tiny")
            else -> null
        }
    }

    private fun artifactMappingVersionNames(): List<String> = mappingNames.mapNotNull { n ->
        when {
            n.endsWith("-client-moj.tiny") -> n.removeSuffix("-client-moj.tiny")
            n.endsWith("-server-moj.tiny") -> n.removeSuffix("-server-moj.tiny")
            n.endsWith("-moj.tiny") -> n.removeSuffix("-moj.tiny")
            n.contains("-yarn-build.") && n.endsWith(".tiny") -> n.substringBefore("-yarn-build.")
            else -> null
        }
    }
}
