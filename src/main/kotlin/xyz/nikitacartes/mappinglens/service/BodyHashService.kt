package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.db.tables.ClassTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.model.BodyHashEntry
import xyz.nikitacartes.mappinglens.model.BodyHashResponse
import xyz.nikitacartes.mappinglens.model.BodyHashSpan
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.MethodRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * "Did this method's body change between A and B?" — a hash of one method's instructions for each
 * version of a range, with equal neighbours collapsed into spans the way [HistoryService] collapses
 * names. An unchanged hash means there is nothing to re-check, which `/diff/patch` cannot say: it
 * works on decompiled source and reports the decompiler's own cosmetics as a change.
 *
 * The hash covers the instructions, the labels they jump to, the try/catch table and the frame
 * sizes. The constant pool and the line numbers never reach it, so a recompile on its own does not
 * move it.
 *
 * ponytail: a lambda body is a method of its own and is not followed, so a change confined to a
 * lambda does not move the enclosing method's hash. Ask for the lambda itself, or walk into it if
 * that turns out to be the common case.
 */
class BodyHashService(private val config: AppConfig, private val db: Database) {

    /** `owner:name` or `owner:name:descriptor`; an overload set without a descriptor hashes as one. */
    private class Target(val query: String) {
        val owner: String = query.substringBefore(':')
        val name: String = query.substringAfter(':', "").substringBefore(':')
        val descriptor: String? = query.substringAfter(':', "").substringAfter(':', "").ifEmpty { null }
    }

    fun hashes(
        queries: List<String>,
        namespace: String,
        versions: List<String>,
        normalize: String,
    ): BodyHashResponse {
        val targets = queries.map { Target(it) }
        // Version by version, because the jar is opened once and answers every query in it.
        val perVersion = versions.map { it to hashesIn(it, namespace, normalize, targets) }
        return BodyHashResponse(
            namespace, normalize,
            targets.mapIndexed { i, target -> BodyHashEntry(target.query, collapse(perVersion.map { it.first to it.second[i] })) },
        )
    }

    /** One hash for each target in [targets], in the same order; null where the method is not there. */
    private fun hashesIn(version: String, namespace: String, normalize: String, targets: List<Target>): List<String?> {
        val jar = config.sources.remappedJar(version, namespace) ?: return targets.map { null }
        val remapper = if (normalize == "intermediary") intermediaryRemapper(version, namespace) else null
        return ZipFile(jar.toFile()).use { zip ->
            targets.map { target ->
                val entry = zip.getEntry("${target.owner}.class") ?: return@map null
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                hashOf(bytes, target, remapper)
            }
        }
    }

    /**
     * The normalized body of every method of [target] in [bytes], hashed together. ASM resolves the
     * constant pool as it reads, and `SKIP_DEBUG` drops the line numbers and local variable names,
     * so what [Textifier] prints is already the normal form; the labels it numbers in visit order
     * are what makes two identical bodies print identically.
     */
    private fun hashOf(bytes: ByteArray, target: Target, remapper: Remapper?): String? {
        val printed = ArrayList<Pair<String, Textifier>>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int, name: String, descriptor: String, sig: String?, exceptions: Array<String>?,
                ): MethodVisitor? {
                    if (name != target.name) return null
                    if (target.descriptor != null && descriptor != target.descriptor) return null
                    val printer = Textifier()
                    printed += descriptor to printer
                    val trace = TraceMethodVisitor(printer)
                    return if (remapper == null) trace else MethodRemapper(trace, remapper)
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        if (printed.isEmpty()) return null
        // Overloads are sorted, so the answer does not depend on the order the class declares them.
        val text = printed.sortedBy { it.first }.joinToString("\n") { (descriptor, printer) ->
            val out = StringWriter()
            PrintWriter(out).use { printer.print(it) }
            "$descriptor\n${LAMBDA_INDEX.replace(out.toString()) { "lambda\$${it.groupValues[1]}" }}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        // ponytail: 64 bits of it. Long enough that a collision needs billions of methods, short
        // enough to compare by eye across a table of versions.
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Renames the class types of a body to their intermediary names, which is what makes a hash
     * survive a class rename or a package move. Member names are left as the namespace spells them,
     * so a renamed call still shows up — that is the change a mixin author is looking for.
     *
     * ponytail: an unobfuscated version has no intermediary names, so its classes keep the ones they
     * have and its hashes only compare against each other. The obfuscation boundary breaks the span
     * either way.
     */
    private fun intermediaryRemapper(version: String, namespace: String): Remapper = transaction(db) {
        val versionRowId = VersionTable.selectAll().where { VersionTable.versionId eq version }
            .singleOrNull()?.get(VersionTable.id)?.value
        val col = if (namespace == "yarn") ClassTable.yarnName else ClassTable.mojmapName
        val names = if (versionRowId == null) emptyMap() else {
            // Two columns, not the whole row: a version holds ~4000 classes and this runs once for
            // each version of the range.
            ClassTable.select(col, ClassTable.intermediaryName).where { ClassTable.versionId eq versionRowId }
                .mapNotNull { row ->
                    val named = row[col] ?: return@mapNotNull null
                    named to (row[ClassTable.intermediaryName] ?: return@mapNotNull null)
                }
                .toMap()
        }
        object : Remapper() {
            override fun map(internalName: String): String = names[internalName] ?: internalName
        }
    }

    /** Merges each run of versions with an equal hash into one span. */
    private fun collapse(hashes: List<Pair<String, String?>>): List<BodyHashSpan> {
        val spans = ArrayList<BodyHashSpan>()
        for ((version, hash) in hashes) {
            val last = spans.lastOrNull()
            if (last != null && last.hash == hash) {
                spans[spans.lastIndex] = last.copy(to = version, versions = last.versions + 1)
            } else {
                spans += BodyHashSpan(version, version, 1, hash)
            }
        }
        return spans
    }

    private companion object {
        /**
         * `lambda$baseTick$12`: javac numbers the lambdas of a class in declaration order, so an
         * unrelated lambda added above this one shifts the index and would otherwise read as a
         * change of body. The enclosing method name in the middle is the stable part.
         */
        val LAMBDA_INDEX = Regex("lambda\\\$([^\\\$\\s()]+)\\\$\\d+")
    }
}
