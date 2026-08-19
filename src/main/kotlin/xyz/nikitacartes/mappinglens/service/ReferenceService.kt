package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.db.ReferenceIndexStore
import xyz.nikitacartes.mappinglens.model.ReferenceChanges
import xyz.nikitacartes.mappinglens.model.ReferenceDiffResponse
import xyz.nikitacartes.mappinglens.model.ReferenceGroup
import xyz.nikitacartes.mappinglens.model.ReferenceItem
import xyz.nikitacartes.mappinglens.model.ReferenceMove
import xyz.nikitacartes.mappinglens.model.ReferenceResponse
import xyz.nikitacartes.mappinglens.model.ReferenceSite
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/** Referring site -> how many instructions in that site hit one target. */
internal typealias Referrers = Map<ReferenceService.Referrer, Int>

/**
 * Owner class -> member (`name:descriptor`, empty for the class itself) -> who references it.
 *
 * Grouped by owner rather than by full target key because that is the unit everything reads: one
 * class answers a member query, a class query and a diff alike, and it is the unit the prebuilt
 * sidecar stores as one row.
 */
internal typealias Index = Map<String, Map<String, Referrers>>

/** Splits `owner` or `owner:name:descriptor` the way [Index] is keyed. */
internal fun Index.at(key: String): Referrers =
    this[key.substringBefore(':')]?.get(key.substringAfter(':', "")).orEmpty()

/**
 * "Find all references": a reverse index from a class/member to the enclosing methods (and class
 * headers) that reference it, built by scanning the version's pre-remapped named jar via ASM. Only
 * references whose target is another Minecraft class in the same jar are kept (JDK calls are dropped),
 * and the whole index is cached per (version, namespace) — the index is immutable for the server's
 * lifetime, so the first query pays the scan and the rest are map lookups.
 *
 * Reference keys match the mcsrc convention so the frontend can build them from a token:
 *   class  -> `owner`
 *   method -> `owner:name:descriptor`
 *   field  -> `owner:name:descriptor`
 *
 * A referrer key has the same shape as a target key, which is what lets [references] walk the call
 * chain upwards instead of answering one level at a time.
 */
class ReferenceService(private val config: AppConfig) {

    /**
     * Where a reference occurs: an enclosing method, or a class header when [member] is null.
     *
     * [synthetic] carries the javac lambda body the reference literally sits in (`lambda$tick$3`),
     * while [member] names the method that lambda was written in. The lambda index moves between
     * versions, so a mixin can only be written against the enclosing name.
     */
    data class Referrer(
        val owner: String,
        val member: String?,
        val descriptor: String?,
        val kind: String,
        val synthetic: String? = null,
    )

    /** Indexes held in memory, keyed by (version, namespace). */
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<Pair<String, String>, Index>(16, 0.75f, true) {
            // ponytail: plain access-ordered LRU. One version's index is ~140 MB, and an unbounded
            // map reached 1557 MB of RSS over 16 versions, against -Xmx4g in the compose file.
            override fun removeEldestEntry(eldest: Map.Entry<Pair<String, String>, Index>) =
                size > MAX_CACHED_INDEXES
        }
    )

    /**
     * One group per (version, target). Returns null when the namespace is unsupported or no
     * requested version has a named jar; a version that has one but knows nothing of a target gives
     * an empty group, which is the honest answer.
     *
     * Several targets against several versions in one call is the shape the work has: checking that
     * a mixin still holds means asking the same handful of targets of every version being collapsed,
     * and the per-version index is built once and reused across every target of that version.
     *
     * [depth] above 1 also walks the callers of the callers, up to that many frames, and reports the
     * chains as `paths` — "which entry points reach this" rather than "who calls this".
     */
    fun references(
        versions: List<String>,
        targets: List<String>,
        namespace: String,
        depth: Int = 1,
        maxPaths: Int = MAX_PATHS,
    ): ReferenceResponse? {
        if (namespace != "yarn" && namespace != "mojmap") return null
        return Lookup(namespace).use { lookup ->
            val available = versions.filter { lookup.has(it) }
            if (available.isEmpty()) return null
            val groups = available.flatMap { version ->
                targets.map { target ->
                    ReferenceGroup(
                        version = version,
                        query = target,
                        references = items(lookup.at(version, target)),
                        paths = if (depth > 1) walk(lookup, version, target, depth, maxPaths) else emptyList(),
                    )
                }
            }
            ReferenceResponse(namespace, groups)
        }
    }

    /**
     * What changed between two versions in the sites that use [query], a class or a member key.
     * Returns null when the namespace is unsupported or either version has no named jar.
     *
     * The answer a mod update needs is not "does the signature still exist" but "is the call still
     * where the mixin expects it". A call that moves from one method to another keeps every
     * signature intact, so `/exists` reports nothing and the injection point breaks in silence.
     */
    fun diffReferences(from: String, to: String, query: String, namespace: String): ReferenceDiffResponse? {
        if (namespace != "yarn" && namespace != "mojmap") return null
        return Lookup(namespace).use { lookup ->
            if (!lookup.has(from) || !lookup.has(to)) return null
            val before = sites(lookup, from, query)
            val after = sites(lookup, to, query)
            val removed = (before.keys - after.keys).sorted()
            val added = (after.keys - before.keys).sorted()
            ReferenceDiffResponse(
                from = from,
                to = to,
                namespace = namespace,
                query = query,
                changes = ReferenceChanges(
                    added = added.map { site(it, after.getValue(it)) },
                    removed = removed.map { site(it, before.getValue(it)) },
                    moved = moves(removed, added, before, after),
                ),
            )
        }
    }

    /**
     * The versions of [versions] this namespace has no prebuilt row for, and so has to scan a jar
     * for. A prebuilt version costs one row read, which is why the route caps the scanned ones only.
     */
    fun scanned(versions: List<String>, namespace: String): List<String> {
        val conn = ReferenceIndexStore.openReadOnly(config.databasePath) ?: return versions
        return conn.use { open ->
            val prebuilt = ReferenceIndexStore.built(open)
            versions.filter { (it to namespace) !in prebuilt }
        }
    }

    /** Calling site (`owner#member`) -> the members of [query] it reaches, as `name:descriptor`. */
    private fun sites(lookup: Lookup, version: String, query: String): Map<String, Set<String>> {
        val members = lookup.members(version, query.substringBefore(':'))
        // A class query answers for all its members. Its class-level edges (a cast, a field type)
        // carry no member to report, and a `new` shows up through `<init>` anyway.
        val wanted = if (':' in query) listOf(query.substringAfter(':')) else members.keys.filter { it.isNotEmpty() }

        val out = HashMap<String, MutableSet<String>>()
        for (target in wanted) {
            for (referrer in members[target].orEmpty().keys) {
                val member = referrer.member ?: continue
                out.getOrPut("${referrer.owner}#$member") { HashSet() }.add(target)
            }
        }
        return out
    }

    private fun site(key: String, targets: Set<String>): ReferenceSite {
        val owner = key.substringBefore('#')
        return ReferenceSite(
            owner = owner,
            ownerSimple = owner.substringAfterLast('/'),
            member = key.substringAfter('#'),
            targets = targets.sorted(),
        )
    }

    /** Removed and added sites that reach exactly the same members, paired inside each such group. */
    private fun moves(
        removed: List<String>,
        added: List<String>,
        before: Map<String, Set<String>>,
        after: Map<String, Set<String>>,
    ): List<ReferenceMove> {
        val addedByTargets = added.groupBy { after.getValue(it) }
        return removed.groupBy { before.getValue(it) }
            .flatMap { (targets, gone) -> pair(gone, addedByTargets[targets].orEmpty()) }
    }

    /**
     * Pairs sites of one target group by the strongest exact signal first: the same method name
     * under a renamed class, then the same class with a renamed method, then a lone pair. Every
     * step needs a unique match on both sides, so an ambiguous group pairs nothing and its sites
     * stay in `added` and `removed` alone, which is the honest answer.
     */
    private fun pair(gone: List<String>, arrived: List<String>): List<ReferenceMove> {
        val left = gone.toMutableList()
        val right = arrived.toMutableList()
        val moves = ArrayList<ReferenceMove>()

        fun matchOn(part: (String) -> String) {
            for (from in left.toList()) {
                val to = right.singleOrNull { part(it) == part(from) } ?: continue
                if (left.count { part(it) == part(to) } != 1) continue
                moves.add(ReferenceMove(from, to))
                left.remove(from)
                right.remove(to)
            }
        }

        matchOn { it.substringAfter('#') }
        matchOn { it.substringBefore('#') }
        if (left.size == 1 && right.size == 1) moves.add(ReferenceMove(left.single(), right.single()))
        return moves
    }

    /**
     * Every chain of callers reaching [target], outermost frame first, at most [depth] frames long.
     *
     * Cycles are broken per path rather than globally: a shared helper is legitimately reached
     * through several chains, and one global visited-set would keep the first chain and drop the
     * rest. A frame whose own key cannot be formed ends its path, see [Referrer.descriptor].
     */
    private fun walk(lookup: Lookup, version: String, target: String, depth: Int, maxPaths: Int): List<List<ReferenceItem>> {
        val paths = ArrayList<List<ReferenceItem>>()

        fun step(key: String, path: List<ReferenceItem>, seen: Set<String>) {
            if (paths.size >= maxPaths) return
            val callers = lookup.at(version, key).filterKeys { it.member != null }
            if (callers.isEmpty() || path.size >= depth) {
                if (path.isNotEmpty()) paths.add(path.asReversed().toList())
                return
            }
            for ((referrer, count) in callers.entries.sortedWith(BY_OWNER_THEN_MEMBER)) {
                val frames = path + item(referrer, count)
                val callerKey = referrer.descriptor?.let { "${referrer.owner}:${referrer.member}:$it" }
                if (callerKey == null || callerKey in seen) {
                    paths.add(frames.asReversed().toList())
                } else {
                    step(callerKey, frames, seen + callerKey)
                }
                if (paths.size >= maxPaths) return
            }
        }

        step(target, emptyList(), setOf(target))
        return paths
    }

    private fun items(referrers: Map<Referrer, Int>): List<ReferenceItem> = referrers.entries
        .sortedWith(BY_OWNER_THEN_MEMBER)
        .map { (referrer, count) -> item(referrer, count) }

    private fun item(referrer: Referrer, count: Int) = ReferenceItem(
        owner = referrer.owner,
        ownerSimple = referrer.owner.substringAfterLast('/'),
        member = referrer.member,
        descriptor = referrer.descriptor,
        kind = referrer.kind,
        count = count,
        synthetic = referrer.synthetic,
    )

    /**
     * One request's view of the index. A version the prebuilt file covers is read a class at a time
     * and nothing is held; any other version is scanned out of its jar and cached whole.
     *
     * The connection lives for the request rather than for the read, because a walk asks for
     * hundreds of classes: opening one connection each costs 0.7ms a class where a shared one costs
     * 0.1ms. It closes with the request, which keeps the server as stateless as it was.
     */
    private inner class Lookup(private val namespace: String) : AutoCloseable {
        private val conn = ReferenceIndexStore.openReadOnly(config.databasePath)
        private val prebuilt = conn?.let { ReferenceIndexStore.built(it) }.orEmpty()

        /** Whether this version can be answered at all, prebuilt or by scanning its jar. */
        fun has(version: String) =
            (version to namespace) in prebuilt || config.sources.remappedJar(version, namespace) != null

        fun members(version: String, owner: String): Map<String, Referrers> =
            if (conn != null && (version to namespace) in prebuilt) {
                ReferenceIndexStore.read(conn, version, namespace, owner)
            } else {
                index(version, namespace)[owner].orEmpty()
            }

        fun at(version: String, key: String): Referrers =
            members(version, key.substringBefore(':'))[key.substringAfter(':', "")].orEmpty()

        override fun close() {
            conn?.close()
        }
    }

    private fun index(versionId: String, namespace: String): Index {
        val key = versionId to namespace
        cache[key]?.let { return it }
        val jar = config.sources.remappedJar(versionId, namespace)?.toFile()
        val built = jar?.let { scanJar(it) } ?: emptyMap()
        cache[key] = built
        return built
    }

    companion object {
        /** How many (version, namespace) indexes stay in memory. */
        private const val MAX_CACHED_INDEXES = 8

        /** Chains one walk reports before it gives up; a hot helper is reached through thousands. */
        const val MAX_PATHS = 200

        private val BY_OWNER_THEN_MEMBER = compareBy<Map.Entry<Referrer, Int>>(
            { it.key.owner }, { it.key.member ?: "" }, { it.key.synthetic ?: "" },
        )

        /**
         * The reverse index of one named jar. Two passes over the zip rather than one pass into a
         * list of every class file: that list peaked at ~150 MB per version, on top of the index it
         * was only there to help build.
         */
        fun scanJar(jar: File): Index = ZipFile(jar).use { zip ->
            // ponytail: an entry path is the internal name for every jar this reads. Parsing all
            // 11k class files a second time only to read that name back cost 350 ms per version.
            val classNames = zip.classEntries().map { it.name.removeSuffix(".class") }.toHashSet()
            scan(classNames, zip.classEntries().map { entry -> zip.getInputStream(entry).use { it.readBytes() } })
        }

        private fun ZipFile.classEntries() = entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") }

        /**
         * Reverse index over a set of class files. Pure over the bytes (no jar/config), so it is
         * unit-testable. [classNames] is the set of classes the jar holds: a reference to anything
         * outside it is a JDK or library target, and is dropped.
         */
        fun scan(classNames: Set<String>, classes: Sequence<ByteArray>): Index {
            val index = HashMap<String, MutableMap<String, MutableMap<Referrer, Int>>>()
            val record = { owner: String, member: String, referrer: Referrer ->
                index.getOrPut(owner) { HashMap() }.getOrPut(member) { HashMap() }
                    .merge(referrer, 1, Int::plus)
                Unit
            }
            for (bytes in classes) {
                ClassReader(bytes).accept(ReferenceCollector(classNames, record), ClassReader.SKIP_FRAMES)
            }
            return index
        }
    }

    private class ReferenceCollector(
        private val mcClasses: Set<String>,
        private val record: (String, String, Referrer) -> Unit,
    ) : ClassVisitor(Opcodes.ASM9) {
        private lateinit var className: String

        /** Method name -> its descriptors, for this class alone. Fills as the class is visited. */
        private val declared = HashMap<String, MutableList<String>>()

        /**
         * References found so far, held until [visitEnd] because folding a lambda body into its
         * enclosing method needs [declared] complete, and a lambda may be visited before it.
         */
        private val pending = ArrayList<Triple<String, String, Referrer>>()

        override fun visit(version: Int, access: Int, name: String, sig: String?, superName: String?, interfaces: Array<String>?) {
            className = name
            // Class-header references (extends / implements), attributed to the class itself.
            (listOfNotNull(superName) + (interfaces?.toList() ?: emptyList()))
                .filter { it in mcClasses && it != name }
                .forEach { pending.add(Triple(it, "", Referrer(name, null, null, "class"))) }
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, sig: String?, exceptions: Array<String>?): MethodVisitor {
            declared.getOrPut(name) { ArrayList() }.add(descriptor)
            val referrer = Referrer(className, name, descriptor, "method")
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(op: Int, owner: String, mName: String, mDesc: String, itf: Boolean) =
                    member(owner, "$mName:$mDesc")

                override fun visitFieldInsn(op: Int, owner: String, fName: String, fDesc: String) =
                    member(owner, "$fName:$fDesc")

                override fun visitTypeInsn(op: Int, type: String) {
                    if (type in mcClasses && type != className) pending.add(Triple(type, "", referrer))
                }

                /**
                 * `Foo::bar` and `this::bar` compile to an `invokedynamic` whose target hides in the
                 * bootstrap arguments, so [visitMethodInsn] never sees them. Only LambdaMetafactory
                 * counts: `ObjectMethods.bootstrap` carries a record's component accessors, and
                 * reporting those would claim every record field is called by its own `hashCode`.
                 */
                override fun visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle, vararg args: Any?) {
                    if (bsm.owner != LAMBDA_METAFACTORY) return
                    for (arg in args) {
                        if (arg is Handle) member(arg.owner, "${arg.name}:${arg.desc}")
                    }
                }

                /**
                 * A member reference is recorded even inside its own class: without those edges a
                 * walk upwards stops at the first private helper. The class-level edge keeps the
                 * older rule, because "who uses this class" reads better without the class itself.
                 */
                private fun member(owner: String, member: String) {
                    if (owner !in mcClasses) return
                    pending.add(Triple(owner, member, referrer))
                    if (owner != className) pending.add(Triple(owner, "", referrer))
                }
            }
        }

        override fun visitEnd() {
            for ((owner, member, referrer) in pending) record(owner, member, fold(referrer))
        }

        /** `lambda$tick$3` in the bytecode is `tick` to anyone reading or writing a mixin. */
        private fun fold(referrer: Referrer): Referrer {
            val synthetic = referrer.member ?: return referrer
            if (!synthetic.startsWith(LAMBDA_PREFIX)) return referrer
            val enclosing = when (val name = synthetic.removePrefix(LAMBDA_PREFIX).substringBeforeLast('$')) {
                "new" -> "<init>"
                "static" -> "<clinit>"
                else -> name
            }
            val descriptors = declared[enclosing] ?: return referrer
            // An overloaded enclosing method leaves the descriptor unknown rather than guessed. Such
            // a frame still names the method, and ends its chain in a walk.
            return referrer.copy(member = enclosing, descriptor = descriptors.singleOrNull(), synthetic = synthetic)
        }

        private companion object {
            const val LAMBDA_PREFIX = "lambda$"
            const val LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory"
        }
    }
}
