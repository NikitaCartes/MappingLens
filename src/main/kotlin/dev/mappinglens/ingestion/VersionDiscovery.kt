package dev.mappinglens.ingestion

import dev.mappinglens.config.SourcesConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Discovers all available versions from the configured source directories and resolves
 * the file paths for intermediary / yarn / mojmap mappings of each version.
 */
class VersionDiscovery(private val sources: SourcesConfig) {
    private val log = LoggerFactory.getLogger(VersionDiscovery::class.java)

    data class VersionFiles(
        val versionId: String,
        val intermediary: Path?,
        val yarn: Path?,
        val mojmap: Path?,
        val mojmaps: List<Path> = listOfNotNull(mojmap),
        /**
         * True when this version has no obfuscation mapping at all (e.g. Mojang's
         * "unobfuscated" releases starting with 26.x). For these the class/method/field
         * names are extracted directly from the (already-named) jar via ASM.
         */
        val unobfuscated: Boolean = false,
        /** Jar to scan with ASM when [unobfuscated] is true. */
        val unobfuscatedJar: Path? = null,
    ) {
        val hasAny get() = intermediary != null || yarn != null || mojmaps.isNotEmpty() ||
            (unobfuscated && unobfuscatedJar != null)
    }

    fun discover(): List<VersionFiles> {
        val intermediaryDir = Paths.get(sources.intermediaryMappings)
        val artifactDir = sources.mappingsPath()

        // Single scan of each directory; group files by version up front so resolve* don't
        // re-list the directory per version (the artifact-store mappings folder contains
        // thousands of files for ~600 versions).
        val intermediaryNames: List<String> = if (intermediaryDir.exists() && intermediaryDir.isDirectory()) {
            Files.list(intermediaryDir).use { stream -> stream.map { it.name }.toList() }
        } else emptyList()
        val artifactNames: List<String> = if (artifactDir.exists() && artifactDir.isDirectory()) {
            Files.list(artifactDir).use { stream -> stream.map { it.name }.toList() }
        } else emptyList()

        val versions = mutableSetOf<String>()
        for (n in intermediaryNames) {
            if (n.endsWith(".tiny") && !n.endsWith("-v1.tiny") && !n.endsWith("-intermediary-v1.tiny")) {
                versions += n.removeSuffix(".tiny").removeSuffix("-intermediary")
            }
        }
        for (n in artifactNames) {
            when {
                n.endsWith("-client-moj.tiny") -> versions += n.removeSuffix("-client-moj.tiny")
                n.endsWith("-server-moj.tiny") -> versions += n.removeSuffix("-server-moj.tiny")
                n.endsWith("-moj.tiny") -> versions += n.removeSuffix("-moj.tiny")
                n.contains("-yarn-build.") && n.endsWith(".tiny") -> versions += n.substringBefore("-yarn-build.")
                n.contains("-intermediary") && n.endsWith(".tiny") -> versions += n.substringBefore("-intermediary")
            }
        }

        val intermediaryByName = intermediaryNames.toHashSet()
        val artifactByName = artifactNames.toHashSet()

        // Augment with versions from Mojang launcher semver cache (artifact-store root).
        // Newer Mojang releases (e.g. 26.x and several _unobfuscated alternates) ship
        // pre-deobfuscated jars and have no tiny mappings in artifact-store/mappings.
        val semverNames = loadSemverCacheVersions()
        versions += semverNames

        val remappedRoot = sources.artifactStorePath().resolve("remapped-mc")
        val mcVersionsRoot = sources.minecraftJarsPath()

        val result = versions.sorted().mapNotNull { v ->
            val mojmaps = resolveMojmaps(artifactDir, artifactByName, v)
            val intermediary = resolveIntermediary(intermediaryDir, artifactDir, intermediaryByName, artifactByName, v)
            val yarn = resolveYarn(artifactDir, artifactNames, v)
            if (intermediary == null && yarn == null && mojmaps.isEmpty()) {
                val jar = findUnobfuscatedJar(remappedRoot, mcVersionsRoot, v)
                if (jar != null) {
                    VersionFiles(
                        versionId = v,
                        intermediary = null, yarn = null, mojmap = null, mojmaps = emptyList(),
                        unobfuscated = true,
                        unobfuscatedJar = jar,
                    )
                } else null
            } else {
                VersionFiles(
                    versionId = v,
                    intermediary = intermediary,
                    yarn = yarn,
                    mojmap = mojmaps.firstOrNull(),
                    mojmaps = mojmaps,
                )
            }
        }.filter { it.hasAny }

        log.info("Discovered {} versions", result.size)
        return result
    }

    private fun loadSemverCacheVersions(): Set<String> {
        val cache = sources.artifactStorePath().resolve("semver-cache-mojang-launcher.json")
        if (!cache.exists()) return emptySet()
        return try {
            val obj = Json.parseToJsonElement(cache.readText()) as? JsonObject ?: return emptySet()
            obj.keys.toSet()
        } catch (e: Exception) {
            log.warn("Failed to parse {}: {}", cache, e.message)
            emptySet()
        }
    }

    private fun findUnobfuscatedJar(remappedRoot: Path, mcVersionsRoot: Path, version: String): Path? {
        val remappedDir = remappedRoot.resolve(version)
        if (remappedDir.exists() && remappedDir.isDirectory()) {
            Files.list(remappedDir).use { stream ->
                stream.filter { it.name.endsWith(".jar") && it.name.startsWith("merged-remapped-map_mojmap") }
                    .findFirst().orElse(null)
            }?.let { return it }
        }
        val mcDir = mcVersionsRoot.resolve(version)
        if (mcDir.exists() && mcDir.isDirectory()) {
            return Files.list(mcDir).use { stream ->
                val list = stream.filter { it.name.endsWith(".jar") }.toList()
                list.firstOrNull { it.name.startsWith("merged-") }
                    ?: list.firstOrNull { it.name.startsWith("client-") }
                    ?: list.firstOrNull()
            }
        }
        return null
    }

    private fun resolveIntermediary(
        intermediaryDir: Path,
        artifactDir: Path,
        intermediaryNames: Set<String>,
        artifactNames: Set<String>,
        version: String,
    ): Path? {
        if ("$version.tiny" in intermediaryNames) return intermediaryDir.resolve("$version.tiny")
        if ("$version-intermediary.tiny" in intermediaryNames) return intermediaryDir.resolve("$version-intermediary.tiny")
        if ("$version-intermediary.tiny" in artifactNames) return artifactDir.resolve("$version-intermediary.tiny")
        return null
    }

    private fun resolveYarn(artifactDir: Path, artifactNames: List<String>, version: String): Path? {
        val prefix = "$version-yarn-build."
        return artifactNames.asSequence()
            .filter { n ->
                n.startsWith(prefix) && n.endsWith(".tiny") &&
                    !n.contains("-constants") && !n.contains("-unpick")
            }
            .maxByOrNull { n -> n.removePrefix(prefix).removeSuffix(".tiny").toIntOrNull() ?: -1 }
            ?.let { artifactDir.resolve(it) }
    }

    private fun resolveMojmaps(artifactDir: Path, artifactNames: Set<String>, version: String): List<Path> {
        val candidates = listOf(
            "$version-moj.tiny",
            "$version-client-moj.tiny",
            "$version-server-moj.tiny",
        )
        return candidates.filter { it in artifactNames }.map { artifactDir.resolve(it) }
    }
}
