package dev.mappinglens.version

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Authoritative metadata for a single logical version, keyed by its **canonical id** — the
 * space-form folder/file name shared by `decompiled/`, `remapped-mc/`, `mc-versions/`,
 * `intermediary/mappings/`, `artifact-store/mappings/` and the semver-cache (e.g.
 * `1.14 Pre-Release 1`, `25w43a`, `1.14.3-pre1`). See REWRITE_PLAN.md section 5.
 */
data class VersionMeta(
    val canonical: String,
    /** Canonical semver from semver-cache (e.g. `1.14-rc.1`); null if absent. Used for ordering. */
    val semver: String?,
    /** Mojang launcher `type` (release|snapshot|old_beta|old_alpha); null if no mc-meta found. */
    val releaseType: String?,
    /** ISO-8601 `releaseTime` from mc-meta; null if no mc-meta found. */
    val releaseTime: String?,
)

/**
 * Read-only catalog of version identities built from the GitCraft artifact store. The single source
 * of truth for version ordering (by semver, not lexicographically) and version metadata.
 */
class VersionCatalog(private val metaByCanonical: Map<String, VersionMeta>) {

    fun all(): Collection<VersionMeta> = metaByCanonical.values

    fun get(canonical: String): VersionMeta? = metaByCanonical[canonical]

    /**
     * Effective semver: the cached value, or one derived from the canonical id when the cache hasn't
     * caught up to the newest builds (otherwise a bare release sorts below its own pre/rc builds).
     */
    private fun semverOf(id: String): Semver? {
        val meta = metaByCanonical[id] ?: return null
        return meta.semver?.let(Semver::parse) ?: Semver.fromMinecraftId(meta.canonical)
    }

    /**
     * Orders canonical ids by their parsed semver. Ids with unknown semver sort last, then by name,
     * so ordering is always total and deterministic.
     */
    val order: Comparator<String> = Comparator { a, b ->
        val sa = semverOf(a)
        val sb = semverOf(b)
        when {
            sa != null && sb != null -> sa.compareTo(sb).let { if (it != 0) it else a.compareTo(b) }
            sa != null -> -1
            sb != null -> 1
            else -> a.compareTo(b)
        }
    }

    fun sorted(canonicalIds: Collection<String>): List<String> = canonicalIds.sortedWith(order)

    companion object {
        private val log = LoggerFactory.getLogger(VersionCatalog::class.java)
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * mc-meta files are named `<canonical>_<sha1>.json` where sha1 is 40 hex chars. The canonical
         * id may itself contain underscores and spaces, so split on the LAST underscore and validate
         * the trailing component is a sha1.
         */
        internal fun canonicalFromMetaName(name: String): String? {
            if (!name.endsWith(".json")) return null
            val stem = name.removeSuffix(".json")
            val cut = stem.lastIndexOf('_')
            if (cut <= 0) return null
            val sha = stem.substring(cut + 1)
            if (sha.length != 40 || !sha.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return stem.substring(0, cut)
        }

        /**
         * Build a catalog from `<artifactStore>/semver-cache-mojang-launcher.json` (ids -> semver) and
         * the per-version json files under `<artifactStore>/mc-meta/mojang-launcher/` (ids -> type/releaseTime). The id universe
         * is the union of both sources; when several mc-meta files exist for one id the newest wins.
         */
        fun load(artifactStore: Path): VersionCatalog {
            val semverById = loadSemverCache(artifactStore.resolve("semver-cache-mojang-launcher.json"))
            val metaById = loadMcMeta(artifactStore.resolve("mc-meta").resolve("mojang-launcher"))

            val ids = LinkedHashSet<String>().apply { addAll(semverById.keys); addAll(metaById.keys) }
            val map = ids.associateWith { id ->
                val (type, time) = metaById[id] ?: (null to null)
                VersionMeta(canonical = id, semver = semverById[id], releaseType = type, releaseTime = time)
            }
            log.info("Loaded version catalog: {} ids ({} with semver, {} with mc-meta)", map.size, semverById.size, metaById.size)
            return VersionCatalog(map)
        }

        private fun loadSemverCache(cache: Path): Map<String, String> {
            if (!cache.exists()) return emptyMap()
            return try {
                val obj = json.parseToJsonElement(cache.readText()) as? JsonObject ?: return emptyMap()
                obj.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull ?: "" }
                    .filterValues { it.isNotEmpty() }
            } catch (e: Exception) {
                log.warn("Failed to parse semver cache {}: {}", cache, e.message)
                emptyMap()
            }
        }

        private fun loadMcMeta(dir: Path): Map<String, Pair<String?, String?>> {
            if (!dir.exists() || !dir.isDirectory()) return emptyMap()
            // Newest-mtime-wins per canonical id.
            data class Candidate(val type: String?, val time: String?, val mtime: Long)
            val byId = HashMap<String, Candidate>()
            Files.list(dir).use { stream ->
                stream.forEach { path ->
                    val id = canonicalFromMetaName(path.name) ?: return@forEach
                    val mtime = path.getLastModifiedTime().toMillis()
                    val existing = byId[id]
                    if (existing == null || mtime > existing.mtime) {
                        val (type, time) = readTypeAndTime(path)
                        byId[id] = Candidate(type, time, mtime)
                    }
                }
            }
            return byId.mapValues { (_, c) -> c.type to c.time }
        }

        private fun readTypeAndTime(file: Path): Pair<String?, String?> = try {
            val obj = json.parseToJsonElement(file.readText()) as? JsonObject
            val type = obj?.get("type")?.jsonPrimitive?.contentOrNull
            val time = obj?.get("releaseTime")?.jsonPrimitive?.contentOrNull
            type to time
        } catch (e: Exception) {
            log.warn("Failed to parse mc-meta {}: {}", file, e.message)
            null to null
        }
    }
}
