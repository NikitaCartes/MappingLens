package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.lruCache
import xyz.nikitacartes.mappinglens.db.ClassDecl
import xyz.nikitacartes.mappinglens.db.DeclarationLookup
import xyz.nikitacartes.mappinglens.model.ExistsResponse
import xyz.nikitacartes.mappinglens.model.ExistsResult

/**
 * "Does this member still exist?" — batch existence check for class/member keys against a version's
 * declarations, read a class at a time out of the prebuilt declaration index and scanned out of the
 * pre-remapped named jar for a version that index does not cover.
 *
 * Keys are the mcsrc convention: a class internal name `owner`, or a member `owner:name:descriptor`.
 * The named jar carries the namespace's own descriptors, so a mojmap descriptor matches a mojmap jar
 * exactly — no descriptor remapping needed. Used to validate mixin/shadow targets when updating a mod.
 *
 * A key that misses also reports the nearest declaration, so one call says what changed instead of
 * only that something did. See [ExistsResult.closest].
 */
class ExistsService(private val config: AppConfig) {

    /** Whole-jar scans of the versions the declaration index does not cover. */
    private val fallback = lruCache<Pair<String, String>, Map<String, ClassDecl>>(config.cache.declarations)

    /** Returns null when the namespace is unsupported or the version has no declarations to read. */
    fun exists(versionId: String, namespace: String, keys: List<String>): ExistsResponse? {
        if (namespace != "yarn" && namespace != "mojmap") return null
        DeclarationLookup(config, versionId, namespace, fallback).use { decls ->
            if (!decls.available) return null
            return ExistsResponse(versionId, namespace, keys.map { resultFor(it, decls) })
        }
    }

    private fun resultFor(key: String, decls: DeclarationLookup): ExistsResult {
        val owner = key.substringBefore(':')
        val decl = decls[owner]
        val exists = if (key.contains(':')) decl != null && key.substringAfter(':') in decl.members else decl != null
        if (exists) return ExistsResult(key = key, exists = true)
        // A key without a descriptor cannot match `owner:name:descriptor` however real the member
        // is, so it is answered with the declarations it names rather than with a bare `false`.
        val candidates = descriptorsUnder(decl, key).sorted().map { "$key:$it" }
        if (candidates.isNotEmpty()) return ExistsResult(key = key, exists = false, candidates = candidates)
        val (closest, reason) = nearest(key, decls) ?: (null to null)
        return ExistsResult(key = key, exists = false, closest = closest, reason = reason)
    }

    /**
     * The nearest declaration to a member key that missed, with the reason it differs. Three probes,
     * in the order a mod author cares about: an inherited declaration still resolves at runtime, so
     * the mixin is fine and the key only names the wrong owner. A changed descriptor does not, and
     * is the edit to make. A declaration of the other kind under the same name is neither, and says
     * so. A class key, an unknown owner, or an unknown name gives null.
     */
    private fun nearest(key: String, decls: DeclarationLookup): Pair<String, String>? {
        val owner = key.substringBefore(':')
        val name = key.substringAfter(':', "").substringBefore(':')
        val descriptor = key.substringAfter(':', "").substringAfter(':', "")
        if (name.isEmpty() || descriptor.isEmpty() || decls[owner] == null) return null

        decls.ancestorsOf(owner).firstOrNull { decls[it]?.members?.contains("$name:$descriptor") == true }
            ?.let { return "$it:$name:$descriptor" to "inherited" }
        // A method descriptor opens with '(' and a field descriptor does not, so the kind needs no
        // column of its own. A candidate of the other kind is reported under a reason of its own:
        // the descriptors under a name cover fields and methods alike, and a client that pastes
        // `closest` into a mixin on a bare `descriptor` would otherwise write an @Inject into a field.
        val candidates = descriptorsUnder(decls[owner], "$owner:$name")
        val method = descriptor.startsWith("(")
        candidates.firstOrNull { it.startsWith("(") == method }
            ?.let { return "$owner:$name:$it" to "descriptor" }
        return candidates.firstOrNull()?.let { "$owner:$name:$it" to "kind" }
    }

    /**
     * The descriptors [decl] declares under the name [key] names, in declaration order. Empty for a
     * class key and for a key that already carries a descriptor: only `owner:name` names a set of
     * overloads to report.
     */
    private fun descriptorsUnder(decl: ClassDecl?, key: String): List<String> {
        val name = key.substringAfter(':', "")
        if (decl == null || name.isEmpty() || name.contains(':')) return emptyList()
        val prefix = "$name:"
        return decl.members.filter { it.startsWith(prefix) }.map { it.substring(prefix.length) }
    }
}
