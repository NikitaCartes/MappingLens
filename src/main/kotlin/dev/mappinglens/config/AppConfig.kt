package dev.mappinglens.config

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
import java.util.concurrent.ConcurrentHashMap
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
) {
    // Resolving the decompiled / remapped jar requires a Files.list on every call.
    // Diff/source endpoints invoke it per file; cache the result per (version, key).
    private val decompiledJarCache = ConcurrentHashMap<Pair<String, String>, Optional<Path>>()
    private val remappedJarCache = ConcurrentHashMap<Pair<String, String>, Optional<Path>>()
    private val libraryJarsCache = ConcurrentHashMap<String, List<Path>>()

    fun artifactStorePath(): Path = Paths.get(artifactStore)

    fun mappingsPath(): Path = artifactStorePath().resolve("mappings")

    fun minecraftJarsPath(): Path = artifactStorePath().resolve("mc-versions")

    fun decompiledSourceJar(versionId: String, mappingType: String): Path? =
        decompiledJarCache.computeIfAbsent(versionId to mappingType) {
            Optional.ofNullable(findFirstJar(artifactStorePath().resolve("decompiled").resolve(versionId), "merged-map_${mappingType}"))
        }.orElse(null)

    fun remappedJar(versionId: String, namespace: String): Path? {
        val mappingType = when (namespace) {
            "yarn" -> "yarn"
            "mojmap" -> "mojmap"
            else -> return null
        }
        return remappedJarCache.computeIfAbsent(versionId to mappingType) {
            Optional.ofNullable(findFirstJar(artifactStorePath().resolve("remapped-mc").resolve(versionId), "merged-remapped-map_${mappingType}"))
        }.orElse(null)
    }

    /**
     * The jar files of [versionId]'s declared libraries, read from its Mojang mc-meta manifest and
     * resolved against the flat `artifact-store/libraries/` pool. Gives the token symbol solver a
     * complete compile classpath so library-typed identifiers (Guava, Brigadier, fastutil, …) resolve
     * instead of being dropped. Empty when the version has no mc-meta or none of its libraries are stored.
     */
    fun libraryJars(versionId: String): List<Path> =
        libraryJarsCache.computeIfAbsent(versionId) { resolveLibraryJars(it) }

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
            stream.filter { canonicalFromMetaName(it.name) == versionId }
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

        /** mc-meta files are `<canonical>_<sha1>.json`; split on the last `_` and require a 40-hex sha. */
        private fun canonicalFromMetaName(name: String): String? {
            if (!name.endsWith(".json")) return null
            val stem = name.removeSuffix(".json")
            val cut = stem.lastIndexOf('_')
            if (cut <= 0) return null
            val sha = stem.substring(cut + 1)
            if (sha.length != 40 || !sha.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return stem.substring(0, cut)
        }
    }
}

data class IndexingConfig(
    val pollIntervalSeconds: Int,
    val initialVersions: List<String>,
    val indexOnStartup: Boolean,
)

data class SearchConfig(
    val maxResults: Int,
    val defaultResults: Int,
)

data class AppConfig(
    val databasePath: String,
    val sources: SourcesConfig,
    val indexing: IndexingConfig,
    val search: SearchConfig,
) {
    companion object {
        fun load(config: ApplicationConfig): AppConfig {
            val ml = config.config("mappinglens")
            return AppConfig(
                databasePath = ml.property("database.path").getString(),
                sources = SourcesConfig(
                    yarnRepo = ml.property("sources.yarn-repo").getString(),
                    mojmapRepo = ml.property("sources.mojmap-repo").getString(),
                    intermediaryMappings = ml.property("sources.intermediary-mappings").getString(),
                    artifactStore = ml.property("sources.artifact-store").getString(),
                ),
                indexing = IndexingConfig(
                    pollIntervalSeconds = ml.property("indexing.poll-interval-seconds").getString().toInt(),
                    initialVersions = ml.propertyOrNull("indexing.initial-versions")?.getList() ?: emptyList(),
                    indexOnStartup = ml.property("indexing.index-on-startup").getString().toBoolean(),
                ),
                search = SearchConfig(
                    maxResults = ml.property("search.max-results").getString().toInt(),
                    defaultResults = ml.property("search.default-results").getString().toInt(),
                ),
            )
        }
    }
}
