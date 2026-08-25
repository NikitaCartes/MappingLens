package xyz.nikitacartes.mappinglens.config

import xyz.nikitacartes.mappinglens.version.VersionCatalog
import io.ktor.server.config.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

data class SourcesConfig(
    val yarnRepo: String,
    val mojmapRepo: String,
    val intermediaryMappings: String,
    val artifactStore: String,
    /**
     * Intermediary mappings for Mojang's unobfuscated releases (the versions after 1.21.11), whose
     * `official` namespace is the unobfuscated name rather than an obfuscated one. They come from a
     * separate repository than [intermediaryMappings] and must stay separate: a `<version>.tiny`
     * found under [intermediaryMappings] means "this version is obfuscated and has mappings", which
     * is exactly what these versions are not. Empty disables the source.
     */
    val unobfuscatedIntermediaryMappings: String = "",
    /** Entry limit of the resolved-path caches below. See [CacheConfig.paths]. */
    val pathCacheSize: Int = CacheConfig().paths,
) {
    // Resolving the decompiled / remapped jar requires a Files.list on every call.
    // Diff/source endpoints invoke it per file; cache the result per (version, key).
    private val decompiledJarCache = lruCache<Pair<String, String>, Optional<Path>>(pathCacheSize)
    private val remappedJarCache = lruCache<Pair<String, String>, Optional<Path>>(pathCacheSize)
    private val libraryJarsCache = lruCache<String, List<Path>>(pathCacheSize)

    fun artifactStorePath(): Path = Paths.get(artifactStore)

    fun minecraftJarsPath(): Path = artifactStorePath().resolve("mc-versions")

    fun decompiledSourceJar(versionId: String, mappingType: String): Path? =
        cachedJar(decompiledJarCache, versionId to mappingType) {
            findFirstJar(artifactStorePath().resolve("decompiled").resolve(versionId), "merged-map_${mappingType}")
        }

    fun remappedJar(versionId: String, namespace: String): Path? {
        val mappingType = when (namespace) {
            "yarn" -> "yarn"
            "mojmap" -> "mojmap"
            else -> return null
        }
        return cachedJar(remappedJarCache, versionId to mappingType) {
            findFirstJar(artifactStorePath().resolve("remapped-mc").resolve(versionId), "merged-remapped-map_${mappingType}")
        }
    }

    /** [resolve] on a miss, remembered either way. Optional, because the cache cannot hold a null. */
    private fun <K> cachedJar(cache: MutableMap<K, Optional<Path>>, key: K, resolve: () -> Path?): Path? {
        cache[key]?.let { return it.orElse(null) }
        val found = Optional.ofNullable(resolve())
        cache[key] = found
        return found.orElse(null)
    }

    /**
     * The jar files of [versionId]'s declared libraries, read from its Mojang mc-meta manifest and
     * resolved against the flat `artifact-store/libraries/` pool. Gives the token symbol solver a
     * complete compile classpath so library-typed identifiers (Guava, Brigadier, fastutil, …) resolve
     * instead of being dropped. Empty when the version has no mc-meta or none of its libraries are stored.
     */
    fun libraryJars(versionId: String): List<Path> {
        libraryJarsCache[versionId]?.let { return it }
        val resolved = resolveLibraryJars(versionId)
        libraryJarsCache[versionId] = resolved
        return resolved
    }

    private fun resolveLibraryJars(versionId: String): List<Path> {
        val meta = newestMcMeta(versionId) ?: return emptyList()
        val libDir = artifactStorePath().resolve("libraries")
        return libraryBasenames(meta).mapNotNull { bn -> libDir.resolve(bn).takeIf { it.exists() } }
    }

    /** Newest `mc-meta/mojang-launcher/<versionId>_<sha1>.json` for the canonical [versionId], or null. */
    private fun newestMcMeta(versionId: String): Path? {
        val dir = artifactStorePath().resolve("mc-meta").resolve("mojang-launcher")
        if (!dir.isDirectory()) return null
        return Files.list(dir).use { stream ->
            stream.filter { VersionCatalog.canonicalFromMetaName(it.name) == versionId }
                .max(compareBy { it.getLastModifiedTime().toMillis() })
                .orElse(null)
        }
    }

    /** Library jar file names from a mc-meta manifest (`libraries[].downloads.artifact.path` basenames). */
    private fun libraryBasenames(metaFile: Path): List<String> = try {
        val obj = META_JSON.parseToJsonElement(metaFile.readText()) as? JsonObject ?: return emptyList()
        val libs = obj["libraries"] as? JsonArray ?: return emptyList()
        libs.mapNotNull { lib ->
            ((lib as? JsonObject)?.get("downloads") as? JsonObject)
                ?.let { it["artifact"] as? JsonObject }
                ?.get("path")?.jsonPrimitive?.contentOrNull
                ?.substringAfterLast('/')
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun findFirstJar(versionDir: Path, prefix: String): Path? {
        if (!versionDir.exists() || !versionDir.isDirectory()) return null
        return Files.list(versionDir).use { stream ->
            stream.filter { p -> p.name.startsWith(prefix) && p.name.endsWith(".jar") }
                .sorted(compareBy { it.name })
                .findFirst()
                .orElse(null)
        }
    }

    companion object {
        private val META_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

data class SearchConfig(
    val maxResults: Int,
    val defaultResults: Int,
)

/**
 * The resource explorer, which reads a clone of `misode/mcmeta`.
 *
 * [repo] empty disables the feature: the endpoints answer 404 and nothing else changes. The set of
 * branches the clone holds is the granularity. An operator who does not want the textures and the
 * sounds clones `diff` alone, and the `assets` paths then carry no data.
 */
data class ResourcesConfig(val repo: String = "")

data class AppConfig(
    val databasePath: String,
    val sources: SourcesConfig,
    /** Version ids the `index` command restricts itself to; empty means every version in the store. */
    val initialVersions: List<String>,
    val search: SearchConfig,
    /**
     * The named mapping namespaces the `index` command reads: `yarn`, `mojmap` or both. A namespace
     * left out here is not read from the store, not scanned for source files and gets no reference
     * index, so its columns stay null and `/versions` reports it as absent. Intermediary is not part
     * of the choice: it is the join key the other two are matched through.
     */
    val mappings: Set<String> = ALL_MAPPINGS,
    /**
     * Restricts the `index` command to Mojang's stable releases. Everything Mojang types as a
     * snapshot is left out with it (pre-releases, release candidates, April Fools versions, combat
     * snapshots), as are the `_unobfuscated` variants, which duplicate a build already indexed.
     * The GitCraft counterpart is `--only-stable`.
     */
    val onlyReleases: Boolean = false,
    val resources: ResourcesConfig = ResourcesConfig(),
    /** How much the server is allowed to hold in memory between requests. */
    val cache: CacheConfig = CacheConfig(),
) {
    init {
        require(mappings.isNotEmpty() && mappings.all { it in ALL_MAPPINGS }) {
            "indexing.mappings must name yarn, mojmap or both, but was: $mappings"
        }
    }

    companion object {
        val ALL_MAPPINGS = setOf("yarn", "mojmap")

        fun load(config: ApplicationConfig): AppConfig {
            val ml = config.config("mappinglens")
            val cache = loadCache(ml)
            return AppConfig(
                databasePath = ml.property("database.path").getString(),
                sources = SourcesConfig(
                    yarnRepo = ml.property("sources.yarn-repo").getString(),
                    mojmapRepo = ml.property("sources.mojmap-repo").getString(),
                    intermediaryMappings = ml.property("sources.intermediary-mappings").getString(),
                    artifactStore = ml.property("sources.artifact-store").getString(),
                    unobfuscatedIntermediaryMappings =
                        ml.propertyOrNull("sources.unobfuscated-intermediary-mappings")?.getString().orEmpty(),
                    pathCacheSize = cache.paths,
                ),
                cache = cache,
                initialVersions = ml.propertyOrNull("indexing.initial-versions")?.getList() ?: emptyList(),
                mappings = ml.propertyOrNull("indexing.mappings")?.getString()
                    ?.split(',', ' ')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
                    ?.takeIf { it.isNotEmpty() } ?: ALL_MAPPINGS,
                onlyReleases = ml.propertyOrNull("indexing.only-releases")?.getString().toBoolean(),
                search = SearchConfig(
                    maxResults = ml.property("search.max-results").getString().toInt(),
                    defaultResults = ml.property("search.default-results").getString().toInt(),
                ),
                resources = ResourcesConfig(
                    repo = ml.propertyOrNull("resources.repo")?.getString().orEmpty(),
                ),
            )
        }

        /** The `cache` block, every entry of which falls back to the [CacheConfig] default. */
        private fun loadCache(ml: ApplicationConfig): CacheConfig {
            val default = CacheConfig()
            fun limit(key: String, fallback: Int) =
                ml.propertyOrNull("cache.$key")?.getString()?.trim()?.toIntOrNull() ?: fallback
            return CacheConfig(
                referenceIndexes = limit("reference-indexes", default.referenceIndexes),
                declarations = limit("declarations", default.declarations),
                nameMaps = limit("name-maps", default.nameMaps),
                symbolSolvers = limit("symbol-solvers", default.symbolSolvers),
                tokens = limit("tokens", default.tokens),
                paths = limit("paths", default.paths),
            )
        }
    }
}
