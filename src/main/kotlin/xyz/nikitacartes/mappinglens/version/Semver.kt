package xyz.nikitacartes.mappinglens.version

/**
 * Minimal Semantic Versioning 2.0.0 value, used purely to order Minecraft versions.
 *
 * The semver strings come from GitCraft's `semver-cache-mojang-launcher.json` (e.g. `1.14-rc.1`,
 * `1.21.11-alpha.25.43.a`, `1.10.1`). Ordering by parsed semver — instead of lexicographically —
 * is what makes `1.9` sort before `1.10` and a pre-release sort before its final release.
 */
data class Semver(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Dot-separated pre-release identifiers; empty means a stable release (highest precedence). */
    val preRelease: List<String>,
) : Comparable<Semver> {

    override fun compareTo(other: Semver): Int {
        (major - other.major).let { if (it != 0) return it }
        (minor - other.minor).let { if (it != 0) return it }
        (patch - other.patch).let { if (it != 0) return it }

        // A version WITH a pre-release has lower precedence than the same version without one.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1

        val shared = minOf(preRelease.size, other.preRelease.size)
        for (i in 0 until shared) {
            val cmp = comparePreReleaseId(preRelease[i], other.preRelease[i])
            if (cmp != 0) return cmp
        }
        // A larger set of pre-release fields has higher precedence when all shared fields are equal.
        return preRelease.size - other.preRelease.size
    }

    companion object {
        fun parse(value: String): Semver? {
            val noBuild = value.substringBefore('+').trim()
            if (noBuild.isEmpty()) return null
            val core = noBuild.substringBefore('-')
            val pre = noBuild.substringAfter('-', "")
            val parts = core.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val preIds = if (pre.isEmpty()) emptyList() else pre.split('.')
            return Semver(major, minor, patch, preIds)
        }

        /**
         * Best-effort semver derived from a GitCraft canonical id, for newest builds the
         * semver-cache hasn't caught up to yet (`26.2`, `26.2-pre-3`, `26.2-rc-1`, `26.2-snapshot-1`),
         * and for ids the cache never covers at all: `_unobfuscated`/`_experimental-snapshot-N`/
         * `_combat-N` variants and old space-form pre-releases (`1.14 Pre-Release 1`). Without this
         * they fall back to "unknown semver, sorts as if newest" (see [VersionCatalog.order]), which
         * is fine for a one-off joke id but wrong for a few dozen real historical versions — they'd
         * clump together after whatever known-semver id happens to precede them alphabetically,
         * instead of near their true `major.minor.patch`.
         *
         * The leading `major[.minor[.patch]]` run is read up to the first `-`, `_`, or space,
         * whichever comes first; anything after that separator becomes the pre-release qualifier, so
         * a variant always sorts as *some* pre-release of its base version (never above the bare
         * release). Returns null only when no such leading numeric run exists at all (space-named ids
         * with no version prefix, weekly snapshots like `25w43a`) so the caller keeps its
         * lexicographic fallback. `snapshot` maps to `alpha` to match the cache's own convention →
         * precedence alpha < pre < rc < release.
         */
        fun fromMinecraftId(id: String): Semver? {
            val sepIdx = id.indexOfFirst { it == '-' || it == '_' || it == ' ' }
            val core = if (sepIdx < 0) id else id.substring(0, sepIdx)
            val coreParts = core.split('.')
            val major = coreParts.getOrNull(0)?.toIntOrNull() ?: return null
            if (coreParts.size > 3 || coreParts.drop(1).any { it.toIntOrNull() == null }) return null
            val minor = coreParts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = coreParts.getOrNull(2)?.toIntOrNull() ?: 0
            if (sepIdx < 0) return Semver(major, minor, patch, emptyList())
            val rest = id.substring(sepIdx + 1)
            if (rest.isEmpty()) return Semver(major, minor, patch, emptyList())
            val tokens = rest.split('-', '_', ' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return Semver(major, minor, patch, emptyList())
            val qualifier = if (tokens[0] == "snapshot") "alpha" else tokens[0].lowercase()
            return Semver(major, minor, patch, listOf(qualifier) + tokens.drop(1))
        }

        /** Numeric identifiers compare numerically and rank below alphanumeric ones (semver section 11). */
        private fun comparePreReleaseId(a: String, b: String): Int {
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            return when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
