package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.model.ValidateEntry
import xyz.nikitacartes.mappinglens.model.ValidateRequest
import xyz.nikitacartes.mappinglens.model.ValidateResponse
import xyz.nikitacartes.mappinglens.model.ValidateSpan
import xyz.nikitacartes.mappinglens.model.ValidateTarget
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.zip.ZipFile

/**
 * "Do my mixins still hold?" — one mixin target for each version of a range, collapsed into spans
 * the way [HistoryService] collapses names. A signature check alone is not enough: a call that moves
 * out of the hooked method keeps every signature intact, so the mixin compiles, applies, and then
 * does nothing.
 *
 * Five answers, in the order a mod author acts on them:
 *   `ok`         the method is there and, when `at` was given, so is the instruction it names
 *   `renamed`    the name is there under another descriptor, `closest` carries it
 *   `inherited`  a supertype declares it, so the call resolves but the mixin does not apply
 *   `call_moved` the method is there and the `at` instruction is not, `movedTo` says where it went
 *   `missing`    neither the method nor a near declaration; `movedTo` still says where the call is
 *
 * One read of one jar entry answers a (version, target) pair. Only `call_moved` costs more, and
 * only when the call left the class entirely: then the reverse index of [ReferenceService] is asked
 * to pair the old calling site with a new one.
 */
class ValidateService(
    private val config: AppConfig,
    private val references: ReferenceService,
) {

    /** The answer for one (version, target) pair; equality is what collapses the spans. */
    private data class Verdict(
        val status: String,
        val atCount: Int? = null,
        val closest: String? = null,
        val movedTo: String? = null,
    )

    /** What one class declares, plus where the `at` target occurs in it. One parse serves both. */
    private class Declared(
        val parents: List<String>,
        /** Method name -> the descriptors declared under it. */
        val overloads: Map<String, List<String>>,
        /** `name:descriptor` -> how many instructions of that method hit the `at` target. */
        val atHits: Map<String, Int>,
    )

    fun validate(request: ValidateRequest, versions: List<String>): ValidateResponse {
        // Version by version, because one jar open answers every target of that version.
        val perVersion = versions.map { version -> checkAll(version, request.namespace, request.targets) }
        val results = request.targets.mapIndexed { i, target ->
            val verdicts = locateMoves(versions, perVersion.map { it[i] }, target, request.namespace)
            ValidateEntry(target.id, collapse(versions.zip(verdicts)))
        }
        return ValidateResponse(request.namespace, results)
    }

    /** One verdict for each target, in the same order. */
    private fun checkAll(version: String, namespace: String, targets: List<ValidateTarget>): List<Verdict> {
        val jar = config.sources.remappedJar(version, namespace) ?: return targets.map { Verdict("missing") }
        return ZipFile(jar.toFile()).use { zip -> targets.map { check(zip, it) } }
    }

    private fun check(zip: ZipFile, target: ValidateTarget): Verdict {
        val at = target.at?.target
        val owner = readClass(zip, target.owner, at) ?: return Verdict("missing")
        val descriptors = owner.overloads[target.method].orEmpty()
        val wanted = descriptors.filter { target.descriptor == null || it == target.descriptor }
        // The method of this class that holds the call now, when exactly one does. It costs nothing:
        // the hits of every method came out of the same parse. Two of them is not an answer, and the
        // pairing in [locateMoves] is the one that can still tell them apart.
        val holder = owner.atHits.keys.singleOrNull()?.let { "${target.owner}#${it.substringBefore(':')}" }

        if (wanted.isEmpty()) {
            if (descriptors.isNotEmpty()) {
                return Verdict("renamed", closest = "${target.owner}:${target.method}:${descriptors.first()}")
            }
            // A renamed method is `missing` the way `/exists` reports it, but the call it carried is
            // still findable, and that is the edit the mixin needs.
            val above = upwards(zip, target, owner.parents)
            return if (above.status == "missing") above.copy(movedTo = holder) else above
        }
        if (at == null) return Verdict("ok")

        val count = wanted.sumOf { owner.atHits["${target.method}:$it"] ?: 0 }
        if (count > 0) return Verdict("ok", atCount = count)
        return Verdict("call_moved", movedTo = holder)
    }

    /**
     * The nearest declaration above [target], breadth-first so a direct parent beats a distant one.
     * An exact match is `inherited`: the call resolves at runtime, but a mixin applies to the class
     * that declares the method, so the target has to move up with it. A supertype that carries only
     * the name is reported as `renamed`, because the descriptor is what has to change either way.
     */
    private fun upwards(zip: ZipFile, target: ValidateTarget, parents: List<String>): Verdict {
        val queue = ArrayDeque(parents)
        val seen = HashSet<String>()
        var renamed: String? = null
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name == "java/lang/Object" || !seen.add(name)) continue
            val cls = readClass(zip, name, null) ?: continue
            queue += cls.parents
            val descriptors = cls.overloads[target.method] ?: continue
            val match = descriptors.firstOrNull { target.descriptor == null || it == target.descriptor }
            if (match != null) return Verdict("inherited", closest = "$name:${target.method}:$match")
            if (renamed == null) renamed = "$name:${target.method}:${descriptors.first()}"
        }
        return if (renamed != null) Verdict("renamed", closest = renamed) else Verdict("missing")
    }

    /**
     * Fills `movedTo` where the call left the class, by diffing the calling sites of the `at` target
     * against the newest earlier version that still had the call. [ReferenceService.diffReferences]
     * already pairs a removed site with an added one, so this only picks the pair that starts at the
     * hooked method.
     *
     * ponytail: one diff for each version of a `call_moved` run. Every version has to be asked
     * anyway, because the call may land somewhere else again, and the index of each is read once.
     */
    private fun locateMoves(
        versions: List<String>,
        verdicts: List<Verdict>,
        target: ValidateTarget,
        namespace: String,
    ): List<Verdict> {
        if (verdicts.none { it.status == "call_moved" && it.movedTo == null }) return verdicts
        val site = "${target.owner}#${target.method}"
        var lastOk: String? = null
        return verdicts.mapIndexed { i, verdict ->
            if (verdict.status == "ok") lastOk = versions[i]
            val from = lastOk
            if (verdict.status != "call_moved" || verdict.movedTo != null || from == null) return@mapIndexed verdict
            val diff = references.diffReferences(from, versions[i], target.at!!.target, namespace)
            verdict.copy(movedTo = diff?.changes?.moved?.firstOrNull { it.from == site }?.to)
        }
    }

    /** Merges each run of versions with an equal verdict into one span. */
    private fun collapse(verdicts: List<Pair<String, Verdict>>): List<ValidateSpan> {
        val spans = ArrayList<ValidateSpan>()
        var last: Verdict? = null
        for ((version, verdict) in verdicts) {
            val previous = spans.lastOrNull()
            if (previous != null && verdict == last) {
                spans[spans.lastIndex] = previous.copy(to = version, versions = previous.versions + 1)
            } else {
                spans += ValidateSpan(version, version, 1, verdict.status, verdict.atCount, verdict.closest, verdict.movedTo)
            }
            last = verdict
        }
        return spans
    }

    /** Null when the jar has no such class. A null [at] skips the code, which is the cheaper parse. */
    private fun readClass(zip: ZipFile, name: String, at: String?): Declared? {
        val entry = zip.getEntry("$name.class") ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val overloads = HashMap<String, MutableList<String>>()
        val hits = HashMap<String, Int>()
        var parents = emptyList<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(v: Int, access: Int, n: String, sig: String?, superName: String?, interfaces: Array<String>?) {
                    parents = listOfNotNull(superName) + interfaces.orEmpty()
                }

                override fun visitMethod(
                    access: Int, name: String, descriptor: String, sig: String?, exceptions: Array<String>?,
                ): MethodVisitor? {
                    overloads.getOrPut(name) { ArrayList(1) } += descriptor
                    if (at == null) return null
                    val self = "$name:$descriptor"
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(op: Int, owner: String, n: String, desc: String, itf: Boolean) =
                            count("$owner:$n:$desc")

                        override fun visitFieldInsn(op: Int, owner: String, n: String, desc: String) =
                            count("$owner:$n:$desc")

                        // ponytail: an `INVOKE` injection point matches an instruction, and a method
                        // reference compiles to an invokedynamic whose handle is not one. Mixin does
                        // not hook it there either, so it is not counted.
                        private fun count(found: String) {
                            if (found == at) hits.merge(self, 1, Int::plus)
                        }
                    }
                }
            },
            if (at == null) ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
            else ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return Declared(parents, overloads, hits)
    }
}
