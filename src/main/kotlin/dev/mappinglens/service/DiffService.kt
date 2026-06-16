package dev.mappinglens.service

import dev.mappinglens.config.AppConfig
import dev.mappinglens.db.tables.*
import dev.mappinglens.ingestion.GitSourceRepository
import dev.mappinglens.model.*
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

        val classDiff = diffClasses(fromId, toId, namespace, packageFilter, candidateStableKeys)
        val methodDiff = diffMethods(fromId, toId, namespace, packageFilter, candidateStableKeys)
        val fieldDiff = diffFields(fromId, toId, namespace, packageFilter, candidateStableKeys)

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
    ): PatchDiffResponse {
        val mappingType = if (namespace == "mojmap") "mojmap" else "yarn"
        val normalizedPath = pathPrefix?.normalizeDiffPath()?.takeIf { it.isNotBlank() }
        val normalizedFunction = functionFilter?.normalizeFunctionFilter()?.takeIf { it.isNotBlank() }
        val candidates = sourceDiffCandidates(from, to, mappingType, normalizedPath)
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

    private fun nameCol(table: ClassTable, namespace: String): Column<String?> = when (namespace) {
        "mojmap" -> table.mojmapName
        "intermediary" -> table.intermediaryName
        else -> table.yarnName
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
        return transaction(db) {
            val fromId = versionRowId(from)
            val toId = versionRowId(to)
            if (fromId == null || toId == null) return@transaction SourceCandidateSet(false, emptyList())

            val fromFiles = SourceFileTable.selectAll()
                .where { (SourceFileTable.versionId eq fromId) and (SourceFileTable.mappingType eq mappingType) }
                .associate { it[SourceFileTable.relativePath] to it[SourceFileTable.contentHash] }
            val toFiles = SourceFileTable.selectAll()
                .where { (SourceFileTable.versionId eq toId) and (SourceFileTable.mappingType eq mappingType) }
                .associate { it[SourceFileTable.relativePath] to it[SourceFileTable.contentHash] }

            if (fromFiles.isEmpty() || toFiles.isEmpty()) {
                return@transaction SourceCandidateSet(false, emptyList())
            }

            fun keep(path: String): Boolean = pathPrefix.isNullOrBlank() || path.startsWith(pathPrefix)
            val files = buildList {
                addAll((toFiles.keys - fromFiles.keys).filter(::keep).map { SourcePatchFile(it, "added") })
                addAll((fromFiles.keys - toFiles.keys).filter(::keep).map { SourcePatchFile(it, "removed") })
                addAll(
                    (fromFiles.keys intersect toFiles.keys)
                        .filter(::keep)
                        .filter { fromFiles.getValue(it) != toFiles.getValue(it) }
                        .map { SourcePatchFile(it, "modified") },
                )
            }.sortedWith(compareBy<SourcePatchFile> { it.path }.thenBy { it.changeType })

            SourceCandidateSet(available = true, files = files)
        }
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
        val namespaceColumn = when (namespace) {
            "mojmap" -> ClassTable.mojmapName
            "intermediary" -> ClassTable.intermediaryName
            else -> ClassTable.yarnName
        }

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

    private fun sourceDiffCandidates(from: String, to: String, mappingType: String, pathPrefix: String?): List<SourcePatchFile> = transaction(db) {
        val fromId = versionRowId(from)
        val toId = versionRowId(to)
        if (fromId == null || toId == null) return@transaction emptyList()

        val fromFiles = SourceFileTable.selectAll()
            .where { (SourceFileTable.versionId eq fromId) and (SourceFileTable.mappingType eq mappingType) }
            .associate { it[SourceFileTable.relativePath] to it[SourceFileTable.contentHash] }
        val toFiles = SourceFileTable.selectAll()
            .where { (SourceFileTable.versionId eq toId) and (SourceFileTable.mappingType eq mappingType) }
            .associate { it[SourceFileTable.relativePath] to it[SourceFileTable.contentHash] }

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
    ): String {
        val oldSlice = functionName?.let { extractFunctionSlice(oldSource, it) }
        val newSlice = functionName?.let { extractFunctionSlice(newSource, it) }
        if (functionName != null && oldSlice == null && newSlice == null) return ""

        val oldLines = oldSlice?.lines ?: if (functionName == null) splitSourceLines(oldSource) else emptyList()
        val newLines = newSlice?.lines ?: if (functionName == null) splitSourceLines(newSource) else emptyList()
        val oldStartLine = oldSlice?.startLine ?: if (oldLines.isEmpty()) 0 else 1
        val newStartLine = newSlice?.startLine ?: if (newLines.isEmpty()) 0 else 1
        val diffLines = buildLineDiff(oldLines, newLines, oldStartLine, newStartLine)
        if (diffLines.none { it.kind != DiffLineKind.SAME }) return ""

        return formatFilePatch(path, changeType, diffLines, contextLines.coerceIn(0, 20))
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
        val nameColExpr: (Int) -> String = { _ -> when (namespace) {
            "mojmap" -> "mojmap_name"; "intermediary" -> "intermediary_name"; else -> "yarn_name"
        } }
        val nameCol = nameColExpr(0)
        val addedPackageSql = packageCondition("c2", packageFilter)
        val removedPackageSql = packageCondition("c1", packageFilter)
        val renamedPackageSql = packageConditionEither("c1", "c2", packageFilter)

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
        // added
        conn.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT c2.id, c2.intermediary_name, c2.$nameCol AS name FROM classes c2
                LEFT JOIN classes c1 ON c1.$keyCol = c2.$keyCol AND c1.version_id = $fromId
                WHERE c2.version_id = $toId AND c1.id IS NULL $addedPackageSql $addedCandidateSql
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
                LEFT JOIN classes c2 ON c1.$keyCol = c2.$keyCol AND c2.version_id = $toId
                WHERE c1.version_id = $fromId AND c2.id IS NULL $removedPackageSql $removedCandidateSql
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) removed += DiffEntryItem(
                    type = "class",
                    name = rs.getString("name"),
                    intermediary = rs.getString("intermediary_name"),
                )
            }
            st.executeQuery(
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
     * mappings, `mojmap_name` when neither does (Mojang's unobfuscated releases), or null when
     * the two versions live in incompatible worlds and cannot be diffed.
     */
    private fun stableIdentityColumn(fromId: Int, toId: Int): String? {
        val rows = VersionTable.selectAll().where { VersionTable.id inList listOf(fromId, toId) }
            .associate { it[VersionTable.id].value to it[VersionTable.hasIntermediary] }
        val fromHas = rows[fromId] ?: false
        val toHas = rows[toId] ?: false
        return when {
            fromHas && toHas -> "intermediary_name"
            !fromHas && !toHas -> "mojmap_name"
            else -> null
        }
    }

    private fun diffMethods(
        fromId: Int,
        toId: Int,
        namespace: String,
        packageFilter: String?,
        candidateStableKeys: Set<String>?,
    ): TypedDiff {
        val nameCol = when (namespace) { "mojmap" -> "mojmap_name"; "intermediary" -> "intermediary_name"; else -> "yarn_name" }
        return diffMembers("methods", fromId, toId, nameCol, "method", packageFilter, candidateStableKeys)
    }

    private fun diffFields(
        fromId: Int,
        toId: Int,
        namespace: String,
        packageFilter: String?,
        candidateStableKeys: Set<String>?,
    ): TypedDiff {
        val nameCol = when (namespace) { "mojmap" -> "mojmap_name"; "intermediary" -> "intermediary_name"; else -> "yarn_name" }
        return diffMembers("fields", fromId, toId, nameCol, "field", packageFilter, candidateStableKeys)
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
        val nameCol = when (namespace) {
            "mojmap" -> ClassTable.mojmapName
            "intermediary" -> ClassTable.intermediaryName
            else -> ClassTable.yarnName
        }
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
                row[MethodTable.intermediaryDesc] ?: row[MethodTable.obfDesc],
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
                row[FieldTable.intermediaryDesc] ?: row[FieldTable.obfDesc],
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
        val addedPackageSql = packageCondition("c2", packageFilter)
        val removedPackageSql = packageCondition("c1", packageFilter)
        val renamedPackageSql = packageConditionEither("c1", "c2", packageFilter)
        // Pick a join key that works whether intermediary mappings exist (default fast path)
        // or both versions are Mojang's unobfuscated 26.x family (fall back to mojmap_name
        // and the descriptor we stored in obf_desc for those).
        val keyCol = stableIdentityColumn(fromId, toId) ?: return TypedDiff(emptyList(), emptyList(), emptyList())
        val keyDesc = if (keyCol == "intermediary_name") "intermediary_desc" else "obf_desc"
        val addedCandidateSql = stableKeyCondition("c2", keyCol, candidateStableKeys)
        val removedCandidateSql = stableKeyCondition("c1", keyCol, candidateStableKeys)
        val renamedCandidateSql = stableKeyCondition("c1", keyCol, candidateStableKeys)
        val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection
            .connection as java.sql.Connection
        conn.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT m2.intermediary_name, m2.$nameCol AS name,
                       c2.$nameCol AS owner
                FROM $table m2
                JOIN classes c2 ON c2.id = m2.class_id
                LEFT JOIN $table m1 ON m1.$keyCol = m2.$keyCol
                    AND m1.$keyDesc = m2.$keyDesc AND m1.version_id = $fromId
                WHERE m2.version_id = $toId AND m1.id IS NULL $addedPackageSql $addedCandidateSql
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
                LEFT JOIN $table m2 ON m1.$keyCol = m2.$keyCol
                    AND m1.$keyDesc = m2.$keyDesc AND m2.version_id = $toId
                WHERE m1.version_id = $fromId AND m2.id IS NULL $removedPackageSql $removedCandidateSql
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) removed += DiffEntryItem(
                    type = kind,
                    name = rs.getString("name"),
                    intermediary = rs.getString("intermediary_name"),
                    owner = rs.getString("owner"),
                )
            }
            st.executeQuery(
                """
                SELECT m1.intermediary_name,
                       m1.$nameCol AS old_name,
                       m2.$nameCol AS new_name,
                                             c2.$nameCol AS owner
                FROM $table m1
                JOIN $table m2 ON m1.$keyCol = m2.$keyCol
                    AND m1.$keyDesc = m2.$keyDesc
                                JOIN classes c1 ON c1.id = m1.class_id
                                JOIN classes c2 ON c2.id = m2.class_id
                WHERE m1.version_id = $fromId AND m2.version_id = $toId
                  AND m1.$keyCol IS NOT NULL
                  AND c1.$keyCol = c2.$keyCol
                  AND IFNULL(m1.$nameCol,'') != IFNULL(m2.$nameCol,'')
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

    private fun packageCondition(alias: String, packageFilter: String?): String {
        if (packageFilter.isNullOrBlank()) return ""
        val escaped = packageFilter.replace("'", "''")
        return "AND $alias.package_path LIKE '$escaped%'"
    }

    private fun packageConditionEither(leftAlias: String, rightAlias: String, packageFilter: String?): String {
        if (packageFilter.isNullOrBlank()) return ""
        val escaped = packageFilter.replace("'", "''")
        return "AND ($leftAlias.package_path LIKE '$escaped%' OR $rightAlias.package_path LIKE '$escaped%')"
    }

    private fun stableKeyCondition(alias: String, keyCol: String, candidateStableKeys: Set<String>?): String {
        if (candidateStableKeys == null) return ""
        if (candidateStableKeys.isEmpty()) return "AND 1=0"
        val inList = candidateStableKeys.joinToString(",") { sqlStringLiteral(it) }
        return "AND $alias.$keyCol IN ($inList)"
    }

    private fun sqlStringLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    private companion object {
        const val MAX_LCS_CELLS = 4_000_000L
    }
}
