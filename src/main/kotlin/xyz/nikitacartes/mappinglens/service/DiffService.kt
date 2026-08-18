package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.db.tables.*
import xyz.nikitacartes.mappinglens.ingestion.GitSourceRepository
import xyz.nikitacartes.mappinglens.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.exists

class DiffService(private val db: Database, private val config: AppConfig? = null) {

    fun diff(
        from: String,
        to: String,
        namespace: String,
        type: String,
        packageFilter: String?,
        changeType: String,
        limit: Int,
    ): DiffResponse {
        val mappingType = namespace.takeIf { it == "yarn" || it == "mojmap" }
        val sourceCandidates = mappingType?.let {
            loadSourceCandidates(from, to, it, packageFilter?.normalizeDiffPath())
        }

        return transaction(db) {
        val fromId = versionRowId(from)
        val toId = versionRowId(to)
        if (fromId == null || toId == null) return@transaction emptyDiffResponse(from, to, namespace)

        if (sourceCandidates?.available == true && sourceCandidates.files.isEmpty()) {
            return@transaction emptyDiffResponse(from, to, namespace)
        }

        val candidateStableKeys = if (sourceCandidates?.available == true) {
            resolveCandidateStableKeys(fromId, toId, namespace, sourceCandidates.files)
        } else {
            null
        }
        if (sourceCandidates?.available == true && candidateStableKeys != null && candidateStableKeys.isEmpty()) {
            return@transaction emptyDiffResponse(from, to, namespace)
        }

        val memberNameCol = sqlNameColumn(namespace)
        val classDiff = diffClasses(fromId, toId, namespace, packageFilter, candidateStableKeys)
        val methodDiff = diffMembers("methods", fromId, toId, memberNameCol, "method", packageFilter, candidateStableKeys)
        val fieldDiff = diffMembers("fields", fromId, toId, memberNameCol, "field", packageFilter, candidateStableKeys)

        val includeClasses = type == "class" || type == "all"
        val includeMethods = type == "method" || type == "all"
        val includeFields = type == "field" || type == "all"
        val changeFilter = when (changeType) {
            "added", "removed", "renamed", "all" -> changeType
            else -> "all"
        }
        fun keep(kind: String) = changeFilter == "all" || changeFilter == kind

        val added = buildList {
            if (includeClasses && keep("added")) addAll(classDiff.added)
            if (includeMethods && keep("added")) addAll(methodDiff.added)
            if (includeFields && keep("added")) addAll(fieldDiff.added)
        }.take(limit)
        val removed = buildList {
            if (includeClasses && keep("removed")) addAll(classDiff.removed)
            if (includeMethods && keep("removed")) addAll(methodDiff.removed)
            if (includeFields && keep("removed")) addAll(fieldDiff.removed)
        }.take(limit)
        val renamed = buildList {
            if (includeClasses && keep("renamed")) addAll(classDiff.renamed)
            if (includeMethods && keep("renamed")) addAll(methodDiff.renamed)
            if (includeFields && keep("renamed")) addAll(fieldDiff.renamed)
        }.take(limit)

        DiffResponse(
            from = from, to = to, namespace = namespace,
            changes = DiffChanges(added, removed, renamed),
            summary = DiffSummary(
                classesAdded = classDiff.added.size,
                classesRemoved = classDiff.removed.size,
                classesRenamed = classDiff.renamed.size,
                methodsAdded = methodDiff.added.size,
                methodsRemoved = methodDiff.removed.size,
                methodsRenamed = methodDiff.renamed.size,
                fieldsAdded = fieldDiff.added.size,
                fieldsRemoved = fieldDiff.removed.size,
                fieldsRenamed = fieldDiff.renamed.size,
            )
        )
    }
    }

    /**
     * Diff exactly one class between two versions, listing added/removed/renamed members by name.
     * Member identity uses the same key as [diffFiles]' per-file member counts, so the summary
     * numbers match `/diff/files` for the same class bit-for-bit. Unlike `package=` (a package-path
     * prefix over the whole diff), `class=` targets a single class by its name in [namespace].
     */
    fun diffClass(
        from: String,
        to: String,
        namespace: String,
        className: String,
        type: String,
        changeType: String,
        limit: Int,
    ): DiffResponse = transaction(db) {
        val fromId = versionRowId(from)
        val toId = versionRowId(to)
        if (fromId == null || toId == null) return@transaction emptyDiffResponse(from, to, namespace)

        val normClass = className.normalizeDiffPath().removeSuffix(".java")
        val fromCid = classIdByName(fromId, namespace, normClass)
        val toCid = classIdByName(toId, namespace, normClass)
        if (fromCid == null && toCid == null) return@transaction emptyDiffResponse(from, to, namespace)

        val classAdded = if (fromCid == null && toCid != null) listOf(DiffEntryItem("class", name = normClass)) else emptyList()
        val classRemoved = if (fromCid != null && toCid == null) listOf(DiffEntryItem("class", name = normClass)) else emptyList()

        val methodDiff = memberDiff(MethodTable, fromCid, toCid, namespace, normClass)
        val fieldDiff = memberDiff(FieldTable, fromCid, toCid, namespace, normClass)

        val includeClasses = type == "class" || type == "all"
        val includeMethods = type == "method" || type == "all"
        val includeFields = type == "field" || type == "all"
        fun keep(kind: String) = changeType == "all" || changeType == kind

        val added = buildList {
            if (includeClasses && keep("added")) addAll(classAdded)
            if (includeMethods && keep("added")) addAll(methodDiff.added)
            if (includeFields && keep("added")) addAll(fieldDiff.added)
        }.take(limit)
        val removed = buildList {
            if (includeClasses && keep("removed")) addAll(classRemoved)
            if (includeMethods && keep("removed")) addAll(methodDiff.removed)
            if (includeFields && keep("removed")) addAll(fieldDiff.removed)
        }.take(limit)
        val renamed = buildList {
            if (includeMethods && keep("renamed")) addAll(methodDiff.renamed)
            if (includeFields && keep("renamed")) addAll(fieldDiff.renamed)
        }.take(limit)

        DiffResponse(
            from = from, to = to, namespace = namespace,
            changes = DiffChanges(added, removed, renamed),
            summary = DiffSummary(
                classesAdded = classAdded.size,
                classesRemoved = classRemoved.size,
                methodsAdded = methodDiff.added.size,
                methodsRemoved = methodDiff.removed.size,
                methodsRenamed = methodDiff.renamed.size,
                fieldsAdded = fieldDiff.added.size,
                fieldsRemoved = fieldDiff.removed.size,
                fieldsRenamed = fieldDiff.renamed.size,
            ),
        )
    }

    private data class MemberRec(val key: String, val name: String?, val descriptor: String?)

    /** Members of [classId] keyed exactly as [methodKeys]/[fieldKeys], carrying the namespace display name + descriptor. */
    private fun memberRecords(cols: MemberTable, classId: Int?, namespace: String): List<MemberRec> {
        if (classId == null) return emptyList()
        val nameCol = when (namespace) { "mojmap" -> cols.mojmapName; "intermediary" -> cols.intermediaryName; else -> cols.yarnName }
        return cols.selectAll().where { cols.classId eq classId }.map { row ->
            MemberRec(
                key = memberKey(row[cols.intermediaryName] ?: row[cols.mojmapName], row[cols.stableDesc] ?: row[cols.obfDesc], row[cols.obfName], row[cols.obfDesc]),
                name = row[nameCol],
                descriptor = row[cols.intermediaryDesc] ?: row[cols.obfDesc],
            )
        }
    }

    private fun memberDiff(cols: MemberTable, fromCid: Int?, toCid: Int?, namespace: String, owner: String): TypedDiff {
        val kind = cols.kind
        val from = memberRecords(cols, fromCid, namespace)
        val to = memberRecords(cols, toCid, namespace)
        val fromByKey = from.associateBy { it.key }
        val toByKey = to.associateBy { it.key }
        fun item(r: MemberRec) = DiffEntryItem(type = kind, name = r.name, owner = owner, intermediaryDescriptor = r.descriptor)
        val added = (toByKey.keys - fromByKey.keys).map { item(toByKey.getValue(it)) }
        val removed = (fromByKey.keys - toByKey.keys).map { item(fromByKey.getValue(it)) }
        val renamed = (fromByKey.keys intersect toByKey.keys)
            .filter { fromByKey.getValue(it).name != toByKey.getValue(it).name }
            .map { DiffEntryItem(type = kind, owner = owner, oldName = fromByKey.getValue(it).name, newName = toByKey.getValue(it).name, intermediaryDescriptor = toByKey.getValue(it).descriptor) }
        return TypedDiff(added, removed, renamed)
    }

    fun diffFiles(from: String, to: String, namespace: String, pathPrefix: String?): FileDiffResponse = transaction(db) {
        val fromId = versionRowId(from)
        val toId = versionRowId(to)
        if (fromId == null || toId == null) {
            return@transaction FileDiffResponse(from, to, namespace, FileDiff(emptyList(), emptyList(), emptyList()))
        }
        val mappingType = if (namespace == "mojmap") "mojmap" else "yarn"

        val fromFiles = SourceFileTable.selectAll()
            .where { (SourceFileTable.versionId eq fromId) and (SourceFileTable.mappingType eq mappingType) }
            .associateBy { it[SourceFileTable.relativePath] }
        val toFiles = SourceFileTable.selectAll()
            .where { (SourceFileTable.versionId eq toId) and (SourceFileTable.mappingType eq mappingType) }
            .associateBy { it[SourceFileTable.relativePath] }

        val filterFn: (String) -> Boolean = if (pathPrefix.isNullOrBlank()) { _ -> true } else { p -> p.startsWith(pathPrefix) }
        val added = (toFiles.keys - fromFiles.keys).filter(filterFn).sorted()
        val removed = (fromFiles.keys - toFiles.keys).filter(filterFn).sorted()
        val modified = (fromFiles.keys intersect toFiles.keys)
            .filter(filterFn)
            .filter { fromFiles.getValue(it)[SourceFileTable.contentHash] != toFiles.getValue(it)[SourceFileTable.contentHash] }
            .sorted()
            .map { path -> fileChangeWithMemberCounts(path, fromId, toId, namespace, fromFiles.getValue(path), toFiles.getValue(path)) }

        FileDiffResponse(from, to, namespace, FileDiff(added, removed, modified))
    }

    fun diffPatch(
        from: String,
        to: String,
        namespace: String,
        pathPrefix: String?,
        functionFilter: String?,
        contextLines: Int,
        limit: Int,
        ignoreWhitespace: Boolean = false,
    ): PatchDiffResponse {
        val mappingType = if (namespace == "mojmap") "mojmap" else "yarn"
        val normalizedPath = pathPrefix?.normalizeDiffPath()?.takeIf { it.isNotBlank() }
        val normalizedFunction = functionFilter?.normalizeFunctionFilter()?.takeIf { it.isNotBlank() }
        val candidates = indexedSourceCandidates(from, to, mappingType, normalizedPath).orEmpty()
        val selectedFiles = candidates.take(limit)
        val patches = mutableListOf<String>()
        val patchFiles = mutableListOf<PatchFileChange>()

        // Open each decompiled source JAR once for the whole iteration to avoid re-opening
        // ZipFile per file (a single diff/patch call can touch hundreds of files).
        SourceJarReader(config, from, mappingType).use { fromReader ->
            SourceJarReader(config, to, mappingType).use { toReader ->
                for (file in selectedFiles) {
                    val oldSource = if (file.changeType == "added") null else readSource(fromReader, from, mappingType, file.path)
                    val newSource = if (file.changeType == "removed") null else readSource(toReader, to, mappingType, file.path)
                    if ((file.changeType != "added" && oldSource == null) ||
                        (file.changeType != "removed" && newSource == null)
                    ) {
                        continue
                    }

                    val filePatch = buildFilePatch(
                        path = file.path,
                        changeType = file.changeType,
                        oldSource = oldSource.orEmpty(),
                        newSource = newSource.orEmpty(),
                        functionName = normalizedFunction,
                        contextLines = contextLines,
                        ignoreWhitespace = ignoreWhitespace,
                    )
                    if (filePatch.isNotBlank()) {
                        patchFiles += PatchFileChange(file.path, file.changeType)
                        patches += filePatch
                    }
                }
            }
        }

        return PatchDiffResponse(
            from = from,
            to = to,
            namespace = mappingType,
            path = normalizedPath,
            function = normalizedFunction,
            files = patchFiles,
            fileCount = patchFiles.size,
            truncated = candidates.size > selectedFiles.size,
            patch = patches.joinToString("\n"),
        )
    }

    private fun versionRowId(versionId: String): Int? =
        VersionTable.selectAll().where { VersionTable.versionId eq versionId }.singleOrNull()?.get(VersionTable.id)?.value

    private data class SourcePatchFile(val path: String, val changeType: String)

    private data class SourceCandidateSet(
        val available: Boolean,
        val files: List<SourcePatchFile>,
    )

    private data class SourceSlice(val lines: List<String>, val startLine: Int)

    private enum class DiffLineKind { SAME, OLD, NEW }

    private data class DiffLine(
        val kind: DiffLineKind,
        val text: String,
        val oldLine: Int?,
        val newLine: Int?,
    )

    private data class HunkRange(val start: Int, val end: Int)

    private data class TypedDiff(
        val added: List<DiffEntryItem>,
        val removed: List<DiffEntryItem>,
        val renamed: List<DiffEntryItem>,
    )

    /** Class name column of [namespace]; anything else reads as yarn, as every diff query does. */
    private fun classNameColumn(namespace: String): Column<String?> = when (namespace) {
        "mojmap" -> ClassTable.mojmapName
        "intermediary" -> ClassTable.intermediaryName
        else -> ClassTable.yarnName
    }

    /** The same choice as [classNameColumn] in raw SQL; classes, methods and fields share the names. */
    private fun sqlNameColumn(namespace: String): String = when (namespace) {
        "mojmap" -> "mojmap_name"
        "intermediary" -> "intermediary_name"
        else -> "yarn_name"
    }

    private fun emptyDiffResponse(from: String, to: String, namespace: String): DiffResponse = DiffResponse(
        from = from,
        to = to,
        namespace = namespace,
        changes = DiffChanges(emptyList(), emptyList(), emptyList()),
        summary = DiffSummary(),
    )

    private fun loadSourceCandidates(from: String, to: String, mappingType: String, pathPrefix: String?): SourceCandidateSet {
        gitSourceCandidates(from, to, mappingType, pathPrefix)?.let {
            return SourceCandidateSet(available = true, files = it)
        }
        val files = indexedSourceCandidates(from, to, mappingType, pathPrefix)
            ?: return SourceCandidateSet(available = false, files = emptyList())
        return SourceCandidateSet(available = true, files = files)
    }

    /**
     * Changed source files of the two versions as the index records them (content hash per path).
     * Null when either version is unknown or has no indexed sources, which is what tells the diff
     * to fall back to the mapping tables alone.
     */
    private fun indexedSourceCandidates(
        from: String,
        to: String,
        mappingType: String,
        pathPrefix: String?,
    ): List<SourcePatchFile>? = transaction(db) {
        val fromId = versionRowId(from) ?: return@transaction null
        val toId = versionRowId(to) ?: return@transaction null

        val fromFiles = SourceFileTable.selectAll()
            .where { (SourceFileTable.versionId eq fromId) and (SourceFileTable.mappingType eq mappingType) }
            .associate { it[SourceFileTable.relativePath] to it[SourceFileTable.contentHash] }
        val toFiles = SourceFileTable.selectAll()
            .where { (SourceFileTable.versionId eq toId) and (SourceFileTable.mappingType eq mappingType) }
            .associate { it[SourceFileTable.relativePath] to it[SourceFileTable.contentHash] }
        if (fromFiles.isEmpty() || toFiles.isEmpty()) return@transaction null

        fun keep(path: String): Boolean = pathPrefix.isNullOrBlank() || path.startsWith(pathPrefix)
        buildList {
            addAll((toFiles.keys - fromFiles.keys).filter(::keep).map { SourcePatchFile(it, "added") })
            addAll((fromFiles.keys - toFiles.keys).filter(::keep).map { SourcePatchFile(it, "removed") })
            addAll(
                (fromFiles.keys intersect toFiles.keys)
                    .filter(::keep)
                    .filter { fromFiles.getValue(it) != toFiles.getValue(it) }
                    .map { SourcePatchFile(it, "modified") },
            )
        }.sortedWith(compareBy<SourcePatchFile> { it.path }.thenBy { it.changeType })
    }

    private fun gitSourceCandidates(from: String, to: String, mappingType: String, pathPrefix: String?): List<SourcePatchFile>? {
        val appConfig = config ?: return null
        val repoRoot = Paths.get(if (mappingType == "mojmap") appConfig.sources.mojmapRepo else appConfig.sources.yarnRepo)
        val repo = GitSourceRepository(repoRoot)
        return repo.diff(from, to, pathPrefix)?.map { SourcePatchFile(it.relativePath, it.changeType) }
    }

    private fun resolveCandidateStableKeys(
        fromId: Int,
        toId: Int,
        namespace: String,
        sourceCandidates: List<SourcePatchFile>,
    ): Set<String>? {
        val candidatePrefixes = sourceCandidates.asSequence()
            .map { it.path.removeSuffix(".java") }
            .filter { it.isNotBlank() }
            .toSet()
        if (candidatePrefixes.isEmpty()) return emptySet()

        val stableKeyColumn = stableIdentityColumn(fromId, toId) ?: return null
        val stableColumn = if (stableKeyColumn == "mojmap_name") ClassTable.mojmapName else ClassTable.intermediaryName
        val namespaceColumn = classNameColumn(namespace)

        return ClassTable.selectAll()
            .where { ClassTable.versionId inList listOf(fromId, toId) }
            .mapNotNull { row ->
                val stableKey = row[stableColumn]
                val className = row[namespaceColumn]
                if (stableKey == null || className == null) return@mapNotNull null
                stableKey.takeIf { className.substringBefore('$') in candidatePrefixes }
            }
            .toSet()
    }

    private fun readSource(reader: SourceJarReader, versionId: String, mappingType: String, relativePath: String): String? {
        reader.read(relativePath)?.let { return it }
        val appConfig = config ?: return null

        val rootPath = Paths.get(if (mappingType == "mojmap") appConfig.sources.mojmapRepo else appConfig.sources.yarnRepo)
        if (!rootPath.exists()) return null
        val gitSource = GitSourceRepository(rootPath)
        if (gitSource.isGitWorkTree()) return gitSource.read(versionId, relativePath)

        val sourceRoot = GitSourceRepository.filesystemSourceRoot(rootPath) ?: return null
        val file = sourceRoot.resolve(GitSourceRepository.normalizeRelativePath(relativePath))
        if (!Files.isRegularFile(file)) return null
        return runCatching { Files.readString(file) }.getOrNull()
    }

    /** Holds an opened decompiled-source ZipFile for the lifetime of a single diff/patch call. */
    private class SourceJarReader(config: AppConfig?, versionId: String, mappingType: String) : AutoCloseable {
        private val zip: ZipFile? = config?.sources?.decompiledSourceJar(versionId, mappingType)
            ?.let { runCatching { ZipFile(it.toFile()) }.getOrNull() }

        fun read(relativePath: String): String? {
            val zip = zip ?: return null
            val entry = zip.getEntry(relativePath) ?: return null
            return runCatching {
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }.getOrNull()
        }

        override fun close() {
            zip?.runCatching { close() }
        }
    }

    private fun buildFilePatch(
        path: String,
        changeType: String,
        oldSource: String,
        newSource: String,
        functionName: String?,
        contextLines: Int,
        ignoreWhitespace: Boolean = false,
    ): String {
        val oldSlice = functionName?.let { extractFunctionSlice(oldSource, it) }
        val newSlice = functionName?.let { extractFunctionSlice(newSource, it) }
        if (functionName != null && oldSlice == null && newSlice == null) return ""

        val oldLines = oldSlice?.lines ?: if (functionName == null) splitSourceLines(oldSource) else emptyList()
        val newLines = newSlice?.lines ?: if (functionName == null) splitSourceLines(newSource) else emptyList()
        val oldStartLine = oldSlice?.startLine ?: if (oldLines.isEmpty()) 0 else 1
        val newStartLine = newSlice?.startLine ?: if (newLines.isEmpty()) 0 else 1
        // Primary engine is Myers O(ND): it yields the minimal (optimal) edit script in memory
        // proportional to the *edit distance*, so a big class with a handful of real changes stays
        // a handful of hunks. The old prefix/suffix + LCS path (buildLineDiff) allocates an O(n·m)
        // matrix and, once past MAX_LCS_CELLS, dumps the whole file as one -/+ block (a 4k-line
        // class → a 240 KB "everything changed" patch). It survives only as the fallback for
        // pathologically dissimilar files where Myers' distance blows the memory budget.
        // `ignoreWhitespace` is just a normalizing equality; the algorithm is otherwise identical.
        val norm: (String) -> String = if (ignoreWhitespace) ::normalizeWhitespace else { s -> s }
        val diffLines = myersLineDiff(oldLines, newLines, oldStartLine, newStartLine, norm)
            ?: buildLineDiff(oldLines, newLines, oldStartLine, newStartLine)
        if (diffLines.none { it.kind != DiffLineKind.SAME }) return ""

        return formatFilePatch(path, changeType, diffLines, contextLines.coerceIn(0, 20))
    }

    private fun normalizeWhitespace(line: String): String = line.trim().replace(WS_RUN, " ")

    /** Test seam: the raw diff engine ("±text" per line), exercising Myers-primary + fallback without a DB. */
    internal fun diffLinesForTest(old: List<String>, new: List<String>, ignoreWhitespace: Boolean): List<String> {
        val norm: (String) -> String = if (ignoreWhitespace) ::normalizeWhitespace else { s -> s }
        val lines = myersLineDiff(old, new, 1, 1, norm) ?: buildLineDiff(old, new, 1, 1)
        return lines.map { (if (it.kind == DiffLineKind.OLD) "-" else if (it.kind == DiffLineKind.NEW) "+" else " ") + it.text }
    }

    /**
     * Myers O(ND) diff (Eugene Myers, 1986) producing the same [DiffLine] stream the patch formatter
     * consumes. Lines are compared through [norm] so callers can ignore whitespace. Returns null when
     * the edit distance would exceed the memory budget (two genuinely dissimilar files, e.g. a full
     * reformat) so the caller can fall back to the LCS path. SAME lines carry the new-side text so
     * context reads coherently against the `+++ b/` file.
     *
     * The trace keeps one V snapshot per edit-distance round, so its memory is `D · (2·(n+m)+1)`
     * ints. [maxD] is derived from [MYERS_TRACE_BUDGET_INTS] to bound that at ~64 MB regardless of
     * file size — for a typical class this permits hundreds of scattered changes before falling back.
     */
    private fun myersLineDiff(
        old: List<String>,
        new: List<String>,
        oldStartLine: Int,
        newStartLine: Int,
        norm: (String) -> String,
    ): List<DiffLine>? {
        val a = old.map(norm)
        val b = new.map(norm)
        val n = a.size
        val m = b.size
        val max = n + m
        if (max == 0) return emptyList()
        val offset = max
        val maxD = minOf(max, (MYERS_TRACE_BUDGET_INTS / (2L * max + 1)).toInt())
        val v = IntArray(2 * max + 1)
        val trace = ArrayList<IntArray>(minOf(max, maxD) + 1)
        var dFinal = -1
        for (d in 0..maxD) {
            trace += v.copyOf()
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    v[offset + k + 1]
                } else {
                    v[offset + k - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { dFinal = d; break }
            }
            if (dFinal >= 0) break
        }
        if (dFinal < 0) return null

        val edits = ArrayDeque<DiffLine>()
        var x = n
        var y = m
        for (d in dFinal downTo 1) {
            val vPrev = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vPrev[offset + k - 1] < vPrev[offset + k + 1])) k + 1 else k - 1
            val prevX = vPrev[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                edits.addFirst(DiffLine(DiffLineKind.SAME, new[y - 1], oldStartLine + x - 1, newStartLine + y - 1))
                x--; y--
            }
            if (x == prevX) {
                edits.addFirst(DiffLine(DiffLineKind.NEW, new[y - 1], null, newStartLine + y - 1))
                y--
            } else {
                edits.addFirst(DiffLine(DiffLineKind.OLD, old[x - 1], oldStartLine + x - 1, null))
                x--
            }
        }
        while (x > 0 && y > 0) {
            edits.addFirst(DiffLine(DiffLineKind.SAME, new[y - 1], oldStartLine + x - 1, newStartLine + y - 1))
            x--; y--
        }
        return edits.toList()
    }

    private fun splitSourceLines(source: String): List<String> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isEmpty()) return emptyList()
        val parts = normalized.split('\n')
        return if (normalized.endsWith('\n')) parts.dropLast(1) else parts
    }

    private fun extractFunctionSlice(source: String, functionName: String): SourceSlice? {
        val lines = splitSourceLines(source)
        if (lines.isEmpty()) return null
        val functionRegex = Regex("\\b${Regex.escape(functionName)}\\s*\\(")
        val signatureLine = lines.indices.firstOrNull { idx ->
            val trimmed = lines[idx].trimStart()
            !trimmed.startsWith("//") && functionRegex.containsMatchIn(lines[idx])
        } ?: return null

        var start = signatureLine
        while (start > 0 && lines[start - 1].trimStart().startsWith("@")) start--

        var end = signatureLine
        var balance = 0
        var foundBody = false
        for (idx in signatureLine until lines.size) {
            val line = lines[idx]
            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            if (opens > 0) foundBody = true
            balance += opens
            balance -= closes
            end = idx
            if (foundBody && balance <= 0) break
        }

        return SourceSlice(lines.subList(start, end + 1), start + 1)
    }

    private fun buildLineDiff(oldLines: List<String>, newLines: List<String>, oldStartLine: Int, newStartLine: Int): List<DiffLine> {
        var prefix = 0
        val commonLimit = minOf(oldLines.size, newLines.size)
        while (prefix < commonLimit && oldLines[prefix] == newLines[prefix]) prefix++

        var suffix = 0
        while (
            suffix < oldLines.size - prefix &&
            suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) {
            suffix++
        }

        val result = mutableListOf<DiffLine>()
        for (idx in 0 until prefix) {
            result += DiffLine(DiffLineKind.SAME, oldLines[idx], oldStartLine + idx, newStartLine + idx)
        }

        val oldMiddleEnd = oldLines.size - suffix
        val newMiddleEnd = newLines.size - suffix
        result += buildMiddleLineDiff(
            oldMiddle = oldLines.subList(prefix, oldMiddleEnd),
            newMiddle = newLines.subList(prefix, newMiddleEnd),
            oldStartLine = oldStartLine + prefix,
            newStartLine = newStartLine + prefix,
        )

        for (idx in oldMiddleEnd until oldLines.size) {
            val newIdx = newMiddleEnd + (idx - oldMiddleEnd)
            result += DiffLine(DiffLineKind.SAME, oldLines[idx], oldStartLine + idx, newStartLine + newIdx)
        }
        return result
    }

    private fun buildMiddleLineDiff(
        oldMiddle: List<String>,
        newMiddle: List<String>,
        oldStartLine: Int,
        newStartLine: Int,
    ): List<DiffLine> {
        if (oldMiddle.isEmpty()) {
            return newMiddle.mapIndexed { idx, line -> DiffLine(DiffLineKind.NEW, line, null, newStartLine + idx) }
        }
        if (newMiddle.isEmpty()) {
            return oldMiddle.mapIndexed { idx, line -> DiffLine(DiffLineKind.OLD, line, oldStartLine + idx, null) }
        }

        val cellCount = oldMiddle.size.toLong() * newMiddle.size.toLong()
        if (cellCount > MAX_LCS_CELLS) {
            return oldMiddle.mapIndexed { idx, line -> DiffLine(DiffLineKind.OLD, line, oldStartLine + idx, null) } +
                newMiddle.mapIndexed { idx, line -> DiffLine(DiffLineKind.NEW, line, null, newStartLine + idx) }
        }

        val dp = Array(oldMiddle.size + 1) { IntArray(newMiddle.size + 1) }
        for (i in oldMiddle.lastIndex downTo 0) {
            for (j in newMiddle.lastIndex downTo 0) {
                dp[i][j] = if (oldMiddle[i] == newMiddle[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < oldMiddle.size && j < newMiddle.size) {
            when {
                oldMiddle[i] == newMiddle[j] -> {
                    result += DiffLine(DiffLineKind.SAME, oldMiddle[i], oldStartLine + i, newStartLine + j)
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    result += DiffLine(DiffLineKind.OLD, oldMiddle[i], oldStartLine + i, null)
                    i++
                }
                else -> {
                    result += DiffLine(DiffLineKind.NEW, newMiddle[j], null, newStartLine + j)
                    j++
                }
            }
        }
        while (i < oldMiddle.size) {
            result += DiffLine(DiffLineKind.OLD, oldMiddle[i], oldStartLine + i, null)
            i++
        }
        while (j < newMiddle.size) {
            result += DiffLine(DiffLineKind.NEW, newMiddle[j], null, newStartLine + j)
            j++
        }
        return result
    }

    private fun formatFilePatch(path: String, changeType: String, diffLines: List<DiffLine>, contextLines: Int): String {
        val hunks = hunkRanges(diffLines, contextLines)
        if (hunks.isEmpty()) return ""
        return buildString {
            append("diff --git a/").append(path).append(" b/").append(path).append('\n')
            when (changeType) {
                "added" -> {
                    append("new file mode 100644\n")
                    append("--- /dev/null\n")
                    append("+++ b/").append(path).append('\n')
                }
                "removed" -> {
                    append("deleted file mode 100644\n")
                    append("--- a/").append(path).append('\n')
                    append("+++ /dev/null\n")
                }
                else -> {
                    append("--- a/").append(path).append('\n')
                    append("+++ b/").append(path).append('\n')
                }
            }
            for (hunk in hunks) append(formatHunk(diffLines, hunk))
        }.trimEnd()
    }

    private fun hunkRanges(diffLines: List<DiffLine>, contextLines: Int): List<HunkRange> {
        val changes = diffLines.indices.filter { diffLines[it].kind != DiffLineKind.SAME }
        if (changes.isEmpty()) return emptyList()
        val ranges = mutableListOf<HunkRange>()
        var start = maxOf(0, changes.first() - contextLines)
        var end = minOf(diffLines.lastIndex, changes.first() + contextLines)
        for (change in changes.drop(1)) {
            val nextStart = maxOf(0, change - contextLines)
            val nextEnd = minOf(diffLines.lastIndex, change + contextLines)
            if (nextStart <= end + 1) {
                end = maxOf(end, nextEnd)
            } else {
                ranges += HunkRange(start, end)
                start = nextStart
                end = nextEnd
            }
        }
        ranges += HunkRange(start, end)
        return ranges
    }

    private fun formatHunk(diffLines: List<DiffLine>, hunk: HunkRange): String {
        val lines = diffLines.subList(hunk.start, hunk.end + 1)
        val oldCount = lines.count { it.kind != DiffLineKind.NEW }
        val newCount = lines.count { it.kind != DiffLineKind.OLD }
        val oldStart = lines.firstNotNullOfOrNull { it.oldLine } ?: previousOldLine(diffLines, hunk.start) ?: 0
        val newStart = lines.firstNotNullOfOrNull { it.newLine } ?: previousNewLine(diffLines, hunk.start) ?: 0
        return buildString {
            append("@@ -").append(formatRange(oldStart, oldCount))
                .append(" +").append(formatRange(newStart, newCount))
                .append(" @@\n")
            for (line in lines) {
                append(
                    when (line.kind) {
                        DiffLineKind.SAME -> ' '
                        DiffLineKind.OLD -> '-'
                        DiffLineKind.NEW -> '+'
                    },
                )
                append(line.text).append('\n')
            }
        }
    }

    private fun previousOldLine(diffLines: List<DiffLine>, beforeIndex: Int): Int? =
        (beforeIndex - 1 downTo 0).firstNotNullOfOrNull { diffLines[it].oldLine }

    private fun previousNewLine(diffLines: List<DiffLine>, beforeIndex: Int): Int? =
        (beforeIndex - 1 downTo 0).firstNotNullOfOrNull { diffLines[it].newLine }

    private fun formatRange(start: Int, count: Int): String = if (count == 1) start.toString() else "$start,$count"

    private fun String.normalizeDiffPath(): String = trim().replace('\\', '/').removePrefix("/")

    private fun String.normalizeFunctionFilter(): String = trim()
        .substringAfterLast('#')
        .substringAfterLast('/')
        .substringAfterLast('.')

    private fun diffClasses(
        fromId: Int,
        toId: Int,
        namespace: String,
        packageFilter: String?,
        candidateStableKeys: Set<String>?,
    ): TypedDiff {
        val nameCol = sqlNameColumn(namespace)
        val addedPackageSql = packageCondition("c2", nameCol, packageFilter)
        val removedPackageSql = packageCondition("c1", nameCol, packageFilter)
        val renamedPackageSql = packageConditionEither("c1", "c2", nameCol, packageFilter)

        val added = mutableListOf<DiffEntryItem>()
        val removed = mutableListOf<DiffEntryItem>()
        val renamed = mutableListOf<DiffEntryItem>()

        val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection
            .connection as java.sql.Connection
        // Pick a stable class-identity column compatible with both versions. Default to the
        // indexed `intermediary_name`; for two unobfuscated Mojang releases (26.x and
        // *_unobfuscated) intermediary_name is NULL, so fall back to `mojmap_name`.
        val keyCol = stableIdentityColumn(fromId, toId) ?: return TypedDiff(emptyList(), emptyList(), emptyList())
        val addedCandidateSql = stableKeyCondition("c2", keyCol, candidateStableKeys)
        val removedCandidateSql = stableKeyCondition("c1", keyCol, candidateStableKeys)
        val renamedCandidateSql = stableKeyCondition("c1", keyCol, candidateStableKeys)
        // Added/removed as a set difference on the stable key, scoped to each version. A direct
        // self-join on $keyCol uses the version-agnostic name index and fans out across every
        // indexed version (~500), turning a 7.6k-class diff into a 26s scan; NOT IN against the
        // single-version key set keeps it sub-second. NULL key = no stable identity → per-row
        // sentinel so it always counts as added/removed (matching join NULL semantics).
        val keyOrUnique = "CASE WHEN c2.$keyCol IS NULL THEN char(2)||c2.id ELSE c2.$keyCol END"
        val keyOrUniqueFrom = "CASE WHEN c1.$keyCol IS NULL THEN char(2)||c1.id ELSE c1.$keyCol END"
        conn.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT c2.id, c2.intermediary_name, c2.$nameCol AS name FROM classes c2
                WHERE c2.version_id = $toId
                  AND $keyOrUnique NOT IN (
                    SELECT c1.$keyCol FROM classes c1 WHERE c1.version_id = $fromId AND c1.$keyCol IS NOT NULL
                  )
                  $addedPackageSql $addedCandidateSql
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) added += DiffEntryItem(
                    type = "class",
                    name = rs.getString("name"),
                    intermediary = rs.getString("intermediary_name"),
                )
            }
            st.executeQuery(
                """
                SELECT c1.id, c1.intermediary_name, c1.$nameCol AS name FROM classes c1
                WHERE c1.version_id = $fromId
                  AND $keyOrUniqueFrom NOT IN (
                    SELECT c2.$keyCol FROM classes c2 WHERE c2.version_id = $toId AND c2.$keyCol IS NOT NULL
                  )
                  $removedPackageSql $removedCandidateSql
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) removed += DiffEntryItem(
                    type = "class",
                    name = rs.getString("name"),
                    intermediary = rs.getString("intermediary_name"),
                )
            }
            // keyCol == nameCol (unobfuscated) ⇒ a rename is undetectable; skip the scan.
            if (keyCol != nameCol) st.executeQuery(
                """
                SELECT c1.intermediary_name, c1.$nameCol AS old_name, c2.$nameCol AS new_name
                FROM classes c1
                JOIN classes c2 ON c1.$keyCol = c2.$keyCol
                WHERE c1.version_id = $fromId AND c2.version_id = $toId
                  AND c1.$keyCol IS NOT NULL
                  AND IFNULL(c1.$nameCol,'') != IFNULL(c2.$nameCol,'')
                                    $renamedPackageSql $renamedCandidateSql
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) renamed += DiffEntryItem(
                    type = "class",
                    intermediary = rs.getString("intermediary_name"),
                    oldName = rs.getString("old_name"),
                    newName = rs.getString("new_name"),
                )
            }
        }
        return TypedDiff(added, removed, renamed)
    }

    /**
     * Returns the column name that uniquely identifies a class/member across the two given
     * versions: `intermediary_name` (the indexed default) when both versions carry intermediary
     * mappings, `mojmap_name` when one of them does not (Mojang's unobfuscated releases have no
     * intermediary), or null when the two versions share no namespace and cannot be diffed.
     */
    private fun stableIdentityColumn(fromId: Int, toId: Int): String? {
        val rows = VersionTable.selectAll().where { VersionTable.id inList listOf(fromId, toId) }
            .associate { it[VersionTable.id].value to Pair(it[VersionTable.hasIntermediary], it[VersionTable.hasMojmap]) }
        val (fromIntermediary, fromMojmap) = rows[fromId] ?: Pair(false, false)
        val (toIntermediary, toMojmap) = rows[toId] ?: Pair(false, false)
        return when {
            fromIntermediary && toIntermediary -> "intermediary_name"
            fromMojmap && toMojmap -> "mojmap_name"
            else -> null
        }
    }

    private fun fileChangeWithMemberCounts(
        path: String,
        fromId: Int,
        toId: Int,
        namespace: String,
        fromRow: ResultRow,
        toRow: ResultRow,
    ): FileChange {
        val className = path.removeSuffix(".java")
        val fromClassId = fromRow[SourceFileTable.classId]?.value ?: classIdByName(fromId, namespace, className)
        val toClassId = toRow[SourceFileTable.classId]?.value ?: classIdByName(toId, namespace, className)
        if (fromClassId == null || toClassId == null) return FileChange(path = path)

        val fromMethods = methodKeys(fromClassId)
        val toMethods = methodKeys(toClassId)
        val fromFields = fieldKeys(fromClassId)
        val toFields = fieldKeys(toClassId)
        return FileChange(
            path = path,
            methodsAdded = (toMethods - fromMethods).size,
            methodsRemoved = (fromMethods - toMethods).size,
            fieldsAdded = (toFields - fromFields).size,
            fieldsRemoved = (fromFields - toFields).size,
        )
    }

    private fun classIdByName(versionRowId: Int, namespace: String, className: String): Int? {
        val nameCol = classNameColumn(namespace)
        return ClassTable.selectAll()
            .where { (ClassTable.versionId eq versionRowId) and (nameCol eq className) }
            .singleOrNull()
            ?.get(ClassTable.id)
            ?.value
    }

    private fun methodKeys(classId: Int): Set<String> = MethodTable.selectAll()
        .where { MethodTable.classId eq classId }
        .map { row ->
            memberKey(
                row[MethodTable.intermediaryName] ?: row[MethodTable.mojmapName],
                row[MethodTable.stableDesc] ?: row[MethodTable.obfDesc],
                row[MethodTable.obfName],
                row[MethodTable.obfDesc],
            )
        }
        .toSet()

    private fun fieldKeys(classId: Int): Set<String> = FieldTable.selectAll()
        .where { FieldTable.classId eq classId }
        .map { row ->
            memberKey(
                row[FieldTable.intermediaryName] ?: row[FieldTable.mojmapName],
                row[FieldTable.stableDesc] ?: row[FieldTable.obfDesc],
                row[FieldTable.obfName],
                row[FieldTable.obfDesc],
            )
        }
        .toSet()

    private fun memberKey(intermediaryName: String?, intermediaryDesc: String?, obfName: String?, obfDesc: String?): String {
        val name = intermediaryName ?: obfName.orEmpty()
        val desc = intermediaryDesc ?: obfDesc.orEmpty()
        return "$name#$desc"
    }

    private fun diffMembers(
        table: String,
        fromId: Int,
        toId: Int,
        nameCol: String,
        kind: String,
        packageFilter: String?,
        candidateStableKeys: Set<String>?,
    ): TypedDiff {
        val added = mutableListOf<DiffEntryItem>()
        val removed = mutableListOf<DiffEntryItem>()
        val renamed = mutableListOf<DiffEntryItem>()
        val addedPackageSql = packageCondition("c2", nameCol, packageFilter)
        val removedPackageSql = packageCondition("c1", nameCol, packageFilter)
        val renamedPackageSql = packageConditionEither("c1", "c2", nameCol, packageFilter)
        // Pick a join key that works whether intermediary mappings exist (default fast path)
        // or both versions are Mojang's unobfuscated 26.x family (fall back to mojmap_name
        // and the descriptor we stored in obf_desc for those).
        val keyCol = stableIdentityColumn(fromId, toId) ?: return TypedDiff(emptyList(), emptyList(), emptyList())
        // `stable_desc` is the same descriptor in both versions; `obf_desc` covers an index built
        // before that column existed, where it reads as it did before — wrong, but not empty.
        fun keyDesc(m: String) = stableMemberDesc("$m.")
        // The tiny files name a method only in the class that first declares it, so `keyCol` reads
        // NULL on every override and the row fell to the per-row sentinel below: added and removed at
        // once, on every pair of versions. The mojmap name, then the official one, stand in — both
        // read the same in both versions for a member the intermediary does not name. Classes keep
        // plain [keyCol]; only members fall back.
        fun memberKey(m: String) =
            if (keyCol == "mojmap_name") "$m.mojmap_name" else stableMemberName("$m.")
        val addedCandidateSql = stableKeyCondition("c2", keyCol, candidateStableKeys)
        val removedCandidateSql = stableKeyCondition("c1", keyCol, candidateStableKeys)
        val renamedCandidateSql = stableKeyCondition("c1", keyCol, candidateStableKeys)
        // Member identity across versions = (owner class key, member key, member desc). Encoded as a
        // single string so added/removed reduce to a set difference instead of a self-join: with a
        // non-unique fallback key (mojmap_name+obf_desc on unobfuscated 26.x) a join explodes into a
        // many-to-many cartesian and took minutes. A NULL in any key part means "no stable identity",
        // so that row can never match — `keyOrUnique` gives it a per-row sentinel (always added/removed)
        // and `keyNotNull` drops it from the lookup set, exactly replicating SQL join NULL semantics.
        fun key(c: String, m: String) = "$c.$keyCol||char(1)||${memberKey(m)}||char(1)||${keyDesc(m)}"
        fun keyOrUnique(c: String, m: String) =
            "CASE WHEN ${memberKey(m)} IS NULL OR $c.$keyCol IS NULL OR ${keyDesc(m)} IS NULL " +
                "THEN char(2)||$m.id ELSE ${key(c, m)} END"
        fun keyNotNull(c: String, m: String) =
            "${memberKey(m)} IS NOT NULL AND $c.$keyCol IS NOT NULL AND ${keyDesc(m)} IS NOT NULL"
        val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection
            .connection as java.sql.Connection
        conn.createStatement().use { st ->
            // Added = members in `to` whose (owner class, member, desc) identity is absent from `from`.
            st.executeQuery(
                """
                SELECT m2.intermediary_name, m2.$nameCol AS name,
                       c2.$nameCol AS owner
                FROM $table m2
                JOIN classes c2 ON c2.id = m2.class_id
                WHERE m2.version_id = $toId
                  AND ${keyOrUnique("c2", "m2")} NOT IN (
                    SELECT ${key("c1", "m1")} FROM $table m1 JOIN classes c1 ON c1.id = m1.class_id
                    WHERE m1.version_id = $fromId AND ${keyNotNull("c1", "m1")}
                  )
                  $addedPackageSql $addedCandidateSql
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) added += DiffEntryItem(
                    type = kind,
                    name = rs.getString("name"),
                    intermediary = rs.getString("intermediary_name"),
                    owner = rs.getString("owner"),
                )
            }
            st.executeQuery(
                """
                SELECT m1.intermediary_name, m1.$nameCol AS name,
                       c1.$nameCol AS owner
                FROM $table m1
                JOIN classes c1 ON c1.id = m1.class_id
                WHERE m1.version_id = $fromId
                  AND ${keyOrUnique("c1", "m1")} NOT IN (
                    SELECT ${key("c2", "m2")} FROM $table m2 JOIN classes c2 ON c2.id = m2.class_id
                    WHERE m2.version_id = $toId AND ${keyNotNull("c2", "m2")}
                  )
                  $removedPackageSql $removedCandidateSql
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) removed += DiffEntryItem(
                    type = kind,
                    name = rs.getString("name"),
                    intermediary = rs.getString("intermediary_name"),
                    owner = rs.getString("owner"),
                )
            }
            // A rename is "same stable key, different display name". When the stable key IS the
            // display name (unobfuscated versions, where keyCol == nameCol) a rename is undetectable
            // by definition, so skip the query entirely instead of scanning to a guaranteed 0 rows.
            // The class pair drives the join, and `CROSS JOIN` pins that order: SQLite otherwise
            // starts from the member and pairs it by name across the whole version, which reads
            // every member row of both versions and took 50s where this takes 0.4s. The members
            // carry no `version_id` condition on purpose — a member belongs to the version of its
            // class, so the condition is redundant, and adding it moves the member lookup off
            // `${table}_class_id` and onto `${table}_version_id`, back to the 50s plan.
            // A rename needs a name on both sides. Comparing them as `IFNULL(name,'')` instead read
            // a constructor as renamed on the pair of versions where yarn started naming it, from
            // nothing to `<init>`, 98 times on one pair.
            if (keyCol != nameCol) st.executeQuery(
                """
                SELECT m1.intermediary_name,
                       m1.$nameCol AS old_name,
                       m2.$nameCol AS new_name,
                       c2.$nameCol AS owner
                FROM classes c1
                CROSS JOIN classes c2 ON c2.$keyCol = c1.$keyCol AND c2.version_id = $toId
                CROSS JOIN $table m1 ON m1.class_id = c1.id
                CROSS JOIN $table m2 ON m2.class_id = c2.id
                    AND ${memberKey("m2")} = ${memberKey("m1")} AND ${keyDesc("m2")} = ${keyDesc("m1")}
                WHERE c1.version_id = $fromId
                  AND c1.$keyCol IS NOT NULL
                  AND m1.$nameCol IS NOT NULL AND m2.$nameCol IS NOT NULL
                  AND m1.$nameCol != m2.$nameCol
                  $renamedPackageSql $renamedCandidateSql
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) renamed += DiffEntryItem(
                    type = kind,
                    intermediary = rs.getString("intermediary_name"),
                    oldName = rs.getString("old_name"),
                    newName = rs.getString("new_name"),
                    owner = rs.getString("owner"),
                )
            }
        }
        return TypedDiff(added, removed, renamed)
    }

    /**
     * `AND` clause keeping only classes under [packageFilter], in the namespace the caller asked
     * about. The indexed `package_path` cannot serve this: it is cut from the yarn name, so a
     * mojmap package matched nothing and the whole diff came back empty. The class name column of
     * the namespace carries the package the caller means, and the trailing slash keeps a class name
     * from passing as a package of the same spelling.
     */
    private fun packageCondition(alias: String, nameCol: String, packageFilter: String?): String {
        val prefix = packagePrefix(packageFilter) ?: return ""
        return "AND $alias.$nameCol LIKE '$prefix'"
    }

    private fun packageConditionEither(leftAlias: String, rightAlias: String, nameCol: String, packageFilter: String?): String {
        val prefix = packagePrefix(packageFilter) ?: return ""
        return "AND ($leftAlias.$nameCol LIKE '$prefix' OR $rightAlias.$nameCol LIKE '$prefix')"
    }

    private fun packagePrefix(packageFilter: String?): String? {
        if (packageFilter.isNullOrBlank()) return null
        return packageFilter.trimEnd('/').replace("'", "''") + "/%"
    }

    private fun stableKeyCondition(alias: String, keyCol: String, candidateStableKeys: Set<String>?): String {
        if (candidateStableKeys == null) return ""
        if (candidateStableKeys.isEmpty()) return "AND 1=0"
        val inList = candidateStableKeys.joinToString(",") { sqlStringLiteral(it) }
        return "AND $alias.$keyCol IN ($inList)"
    }

    private fun sqlStringLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    private companion object {
        // LCS fallback matrix cap. Only reached when Myers bails (a near-total reformat); raised from
        // 4M so the fallback still produces a real diff for the largest MC classes (~5k×5k = 25M)
        // before the last-resort whole-file -/+ dump. 40M ints ≈ 160 MB transient, GC'd per call.
        const val MAX_LCS_CELLS = 40_000_000L
        // Memory budget for the Myers trace (ints). ~64 MB caps how many edit rounds we keep before
        // giving up and falling back — comfortably covers every realistic semantic diff.
        const val MYERS_TRACE_BUDGET_INTS = 16_000_000L
        val WS_RUN = Regex("\\s+")
    }
}
