package dev.mappinglens.config

import io.ktor.server.config.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

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

    private fun findFirstJar(versionDir: Path, prefix: String): Path? {
        if (!versionDir.exists() || !versionDir.isDirectory()) return null
        return Files.list(versionDir).use { stream ->
            stream.filter { p -> p.name.startsWith(prefix) && p.name.endsWith(".jar") }
                .sorted(compareBy { it.name })
                .findFirst()
                .orElse(null)
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
