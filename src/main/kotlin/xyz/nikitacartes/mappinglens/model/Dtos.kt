package xyz.nikitacartes.mappinglens.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val error: String,
    val message: String,
    val status: Int,
)

@Serializable
data class VersionInfo(
    val id: String,
    val releaseType: String,
    val releaseTime: String? = null,
    val protocolVersion: Int? = null,
    val hasYarn: Boolean,
    val hasMojmap: Boolean,
    val hasIntermediary: Boolean,
    /** The version this one re-indexes from the pre-deobfuscated jar, or null when it stands alone. */
    val variantOf: String? = null,
    val classCount: Long = 0,
    val methodCount: Long = 0,
    val fieldCount: Long = 0,
    val indexedAt: String,
)

@Serializable
data class VersionListResponse(val versions: List<VersionInfo>)

@Serializable
data class ClassEntry(
    val obfuscated: String? = null,
    val intermediary: String? = null,
    val yarn: String? = null,
    val mojmap: String? = null,
    val presence: String? = null, // both | yarn_only | mojmap_only
)

/** All classes of one version, for building the package/class structure tree client-side. */
@Serializable
data class ClassListResponse(
    val version: String,
    val classes: List<ClassEntry>,
)

@Serializable
data class ClassRef(
    val intermediary: String? = null,
    val yarn: String? = null,
    val mojmap: String? = null,
    val obfuscated: String? = null,
)

@Serializable
data class SearchResultEntry(
    val type: String, // class | method | field
    val intermediary: String? = null,
    val yarn: String? = null,
    val mojmap: String? = null,
    val obfuscated: String? = null,
    val owner: ClassRef? = null,
    /**
     * Named descriptors are not indexed, so this is the intermediary one whatever `namespace` asked
     * for. Named after what it holds: read as a plain `descriptor` it invites being pasted into
     * `/exists`, which wants the descriptor of the requested namespace and rejects this one.
     * `POST /translate/{version}` converts a key from one namespace to another, descriptor included.
     */
    val intermediaryDescriptor: String? = null,
    val score: Double = 0.0,
)

@Serializable
data class SearchResponse(
    val query: String,
    val version: String,
    val totalResults: Int,
    val results: List<SearchResultEntry>,
)

@Serializable
data class TranslateInput(val name: String, val namespace: String)

@Serializable
data class TranslateOutput(val name: String? = null, val namespace: String)

@Serializable
data class TranslateResponse(
    val input: TranslateInput,
    val output: TranslateOutput,
    val intermediary: String? = null,
    val obfuscated: String? = null,
    val version: String,
    val type: String,
)

@Serializable
data class BatchTranslateRequest(
    val from: String = "yarn",
    val to: String = "mojmap",
    /** Class internal names, or `owner:name:descriptor` member keys, spelled in `from`. */
    val keys: List<String> = emptyList(),
)

@Serializable
data class BatchTranslateItem(
    val key: String,
    /**
     * The same key in the `to` namespace, descriptor included, or null when the version has no such
     * class or member. Ready to post to `/exists/{version}` unchanged, which is the point of the
     * endpoint: `/search` reports intermediary descriptors, and `/exists` matches named ones.
     */
    val translated: String? = null,
    val type: String? = null, // class | method | field
    val intermediary: String? = null,
)

@Serializable
data class BatchTranslateResponse(
    val version: String,
    val from: String,
    val to: String,
    val results: List<BatchTranslateItem>,
)

@Serializable
data class DiffEntryItem(
    val type: String,
    val name: String? = null,
    val intermediary: String? = null,
    val owner: String? = null,
    /** The intermediary descriptor, for the reason given on [SearchResultEntry.intermediaryDescriptor]. */
    val intermediaryDescriptor: String? = null,
    val oldName: String? = null,
    val newName: String? = null,
)

@Serializable
data class DiffChanges(
    val added: List<DiffEntryItem>,
    val removed: List<DiffEntryItem>,
    val renamed: List<DiffEntryItem>,
)

@Serializable
data class DiffSummary(
    val classesAdded: Int = 0,
    val classesRemoved: Int = 0,
    val classesRenamed: Int = 0,
    val methodsAdded: Int = 0,
    val methodsRemoved: Int = 0,
    val methodsRenamed: Int = 0,
    val fieldsAdded: Int = 0,
    val fieldsRemoved: Int = 0,
    val fieldsRenamed: Int = 0,
)

@Serializable
data class DiffResponse(
    val from: String,
    val to: String,
    val namespace: String,
    val changes: DiffChanges,
    val summary: DiffSummary,
)

@Serializable
data class FileChange(
    val path: String,
    val methodsAdded: Int = 0,
    val methodsRemoved: Int = 0,
    val fieldsAdded: Int = 0,
    val fieldsRemoved: Int = 0,
)

@Serializable
data class FileDiff(
    val added: List<String>,
    val removed: List<String>,
    val modified: List<FileChange>,
)

@Serializable
data class FileDiffResponse(
    val from: String,
    val to: String,
    val namespace: String,
    val files: FileDiff,
)

@Serializable
data class PatchFileChange(
    val path: String,
    val changeType: String,
)

@Serializable
data class PatchDiffResponse(
    val from: String,
    val to: String,
    val namespace: String,
    val path: String? = null,
    val function: String? = null,
    val files: List<PatchFileChange>,
    val fileCount: Int,
    val truncated: Boolean = false,
    val patch: String,
)

@Serializable
data class BytecodeResponse(
    val version: String,
    val `class`: String,
    val bytecode: String,
)

@Serializable
data class SourceResponse(
    val version: String,
    val `class`: String,
    val namespace: String,
    val source: String,
    val path: String,
)

@Serializable
data class BlameResponse(
    val version: String,
    val `class`: String,
    val namespace: String,
    val path: String,
    /** Versions referenced by [lines], each listed once. */
    val versions: List<String>,
    /** Index into [versions] for each line of the file, line 1 first. */
    val lines: List<Int>,
)

@Serializable
data class HierarchyNode(
    val name: String,
    val simpleName: String,
    val isInterface: Boolean = false,
    val isAbstract: Boolean = false,
)

@Serializable
data class HierarchyEdge(
    val parent: String,
    val child: String,
)

@Serializable
data class HierarchyResponse(
    val version: String,
    val namespace: String,
    val root: String,
    val nodes: List<HierarchyNode>,
    val edges: List<HierarchyEdge>,
)

@Serializable
data class SourceToken(
    // Monaco-style 1-based range (endColumn is exclusive, i.e. one past the last char).
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val type: String, // class | method | field
    val className: String, // owner internal name (slashes, `$` for nested), in the source namespace
    val name: String? = null, // member name (null for class tokens)
    val descriptor: String? = null, // member descriptor (null for class tokens)
    val declaration: Boolean = false,
)

@Serializable
data class TokensResponse(
    val version: String,
    val `class`: String,
    val namespace: String,
    val source: String,
    val tokens: List<SourceToken>,
)

@Serializable
data class ReferenceItem(
    val owner: String,
    val ownerSimple: String,
    val member: String? = null,
    val descriptor: String? = null,
    val kind: String, // class | method | field (of the referring site)
)

/** The sites referencing one target in one version. */
@Serializable
data class ReferenceGroup(
    val version: String,
    val query: String,
    val references: List<ReferenceItem>,
)

@Serializable
data class ReferenceResponse(
    val namespace: String,
    /** One group per (version, target), versions oldest first and targets in request order. */
    val results: List<ReferenceGroup>,
)

@Serializable
data class ExistsRequest(
    val namespace: String = "mojmap",
    val members: List<String> = emptyList(),
)

@Serializable
data class ExistsResult(
    val key: String,
    val exists: Boolean,
    /**
     * The nearest declaration to a key that missed, in the same key form, or null when the version
     * declares nothing of that name. Answers "what changed?" without a second call: a bare `false`
     * reads the same whether the descriptor moved, the member moved to a supertype, or the name is
     * gone. Null when [exists] is true.
     */
    val closest: String? = null,
    /**
     * Why [closest] is not the key asked for:
     *  - `inherited`: a supertype declares this exact signature, so the call still resolves.
     *  - `descriptor`: the owner declares this name under another descriptor, so the signature moved.
     */
    val reason: String? = null,
)

@Serializable
data class ExistsResponse(
    val version: String,
    val namespace: String,
    val results: List<ExistsResult>,
)

/** One member matched in a history span. Overloads share a name, so a span can hold several. */
@Serializable
data class HistoryMember(
    val intermediary: String? = null,
    val yarn: String? = null,
    val mojmap: String? = null,
    // Named descriptors are not indexed; the intermediary one answers "did the signature change?".
    val intermediaryDescriptor: String? = null,
)

/** A run of consecutive versions (oldest [from] to newest [to]) that answer the query identically. */
@Serializable
data class HistorySpan(
    val from: String,
    val to: String,
    val versions: Int,
    val present: Boolean,
    // Class queries: the class in each namespace.
    val intermediary: String? = null,
    val yarn: String? = null,
    val mojmap: String? = null,
    // Member queries: the owner in the requested namespace (set even when the member is absent, so
    // a removed member and a removed class are distinguishable), and one entry per overload.
    val owner: String? = null,
    val members: List<HistoryMember> = emptyList(),
)

@Serializable
data class HistoryEntry(
    val query: String,
    val type: String, // class | method | field | unknown
    val spans: List<HistorySpan>,
)

@Serializable
data class HistoryResponse(
    val namespace: String,
    val results: List<HistoryEntry>,
)

@Serializable
data class CompareMember(
    val kind: String, // method | field
    val obfName: String? = null,
    val obfDesc: String? = null,
    val intermediary: String? = null,
    val yarn: String? = null,
    val mojmap: String? = null,
    val status: String, // matched | yarnOnly | mojmapOnly | unmappedYarn | synthetic | initializer
)

@Serializable
data class CompareResponse(
    val version: String,
    val from: String,
    val to: String,
    val obf: String? = null,
    val intermediary: String? = null,
    val yarnClass: String? = null,
    val mojmapClass: String? = null,
    val presence: String? = null, // both | yarn_only | mojmap_only
    val members: List<CompareMember>,
)
