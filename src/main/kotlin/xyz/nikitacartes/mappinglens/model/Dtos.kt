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
    /** The descriptor as intermediary names it. Absent on a version whose tiny files omit it. */
    val intermediaryDescriptor: String? = null,
    /**
     * The same descriptor as the named namespaces spell it, which is what `/exists` wants, so a key
     * built out of this entry needs no translation. Each is present when the entry carries a name
     * in that namespace, so a version without yarn returns [mojmapDescriptor] alone.
     */
    val yarnDescriptor: String? = null,
    val mojmapDescriptor: String? = null,
    val score: Double = 0.0,
    /**
     * A javac lambda body (`lambda$addRecipes$0`), which the mappings name like any other method.
     * Such a row is left out unless `includeSynthetic=true`, because a search for `addRecipes` means
     * the method, and the index in the lambda's name moves between versions.
     */
    val synthetic: Boolean = false,
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
    /** Instructions in this site that hit the target; `@At(ordinal = N)` numbers them 0..count-1. */
    val count: Int = 1,
    /** The javac lambda body the reference sits in, when [member] is the method that lambda is written in. */
    val synthetic: String? = null,
)

/** The sites referencing one target in one version. */
@Serializable
data class ReferenceGroup(
    val version: String,
    val query: String,
    val references: List<ReferenceItem>,
    /**
     * Whether [query] could match this version's index at all. False means [references] is not an
     * answer about the target: the key carries no descriptor and so can never match a `name:descriptor`
     * index key, or the descriptor names no member the index knows, or the index has no row for the
     * owner. All three used to give an empty [references], which is also what "nothing calls this"
     * looks like.
     */
    val resolved: Boolean = true,
    /** The keys [query] would match instead, when it did not resolve. Empty when [resolved] is true. */
    val candidates: List<String> = emptyList(),
    /** Caller chains reaching the target, outermost frame first. Empty unless `depth` was above 1. */
    val paths: List<List<ReferenceItem>> = emptyList(),
)

@Serializable
data class ReferenceResponse(
    val namespace: String,
    /** One group per (version, target), versions oldest first and targets in request order. */
    val results: List<ReferenceGroup>,
)

/** One calling method and the members of the queried target it touches, in one version. */
@Serializable
data class ReferenceSite(
    val owner: String,
    val ownerSimple: String,
    val member: String,
    /** The members of the query this site reaches, as `name:descriptor`. */
    val targets: List<String>,
)

/** A call that left one method for another between the two versions. */
@Serializable
data class ReferenceMove(
    val from: String,
    val to: String,
)

@Serializable
data class ReferenceChanges(
    val added: List<ReferenceSite>,
    val removed: List<ReferenceSite>,
    /**
     * Pairs from [removed] and [added] that reach exactly the same members, matched one to one.
     * A rename of the calling method reads as a move; the pair also stays in the two lists above.
     */
    val moved: List<ReferenceMove>,
)

@Serializable
data class ReferenceDiffResponse(
    val from: String,
    val to: String,
    val namespace: String,
    val query: String,
    val changes: ReferenceChanges,
)

/** The body form of a `/references` request, for batches too large for a query string. */
@Serializable
data class ReferenceRequest(
    val namespace: String = "mojmap",
    val targets: List<String> = emptyList(),
    /** The far end of a version range; the route's `{version}` is the near end. */
    val to: String? = null,
    val releasesOnly: Boolean = false,
    val includeVariants: Boolean = false,
    /** Frames of the caller chain to walk; 1 answers "who calls this" and reports no paths. */
    val depth: Int = 1,
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
     *  - `kind`: the owner declares this name, but as a field where a method was asked for, or the
     *    other way round. [closest] is therefore not a drop-in replacement for the key.
     */
    val reason: String? = null,
    /**
     * Every declaration under `owner:name`, in key form, for a key given without a descriptor. Such
     * a key can never match on its own, and a bare `exists: false` for it reads the same as a member
     * that is really gone. One entry is the canonical resolution of `owner#name`, several are its
     * overloads. Empty for a key that carries a descriptor, and for a class key.
     */
    val candidates: List<String> = emptyList(),
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
    /**
     * Why the owner does not declare it, spelled as `/exists` spells it. `inherited` means a
     * supertype declares it and the call still resolves, and [declaredIn] names that supertype;
     * [members] then describes the inherited declaration. A `present: false` span with no reason
     * is a member that is really gone.
     */
    val reason: String? = null,
    val declaredIn: String? = null,
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

/**
 * A run of consecutive versions whose method body hashes alike. [hash] is null where the version
 * has no such method, so a gap reads as a gap rather than as another body.
 */
@Serializable
data class BodyHashSpan(
    val from: String,
    val to: String,
    val versions: Int,
    val hash: String? = null,
)

@Serializable
data class BodyHashEntry(
    val query: String,
    val spans: List<BodyHashSpan>,
)

@Serializable
data class BodyHashResponse(
    val namespace: String,
    /** `named` or `intermediary`: which names the hash was taken over. */
    val normalize: String,
    val results: List<BodyHashEntry>,
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

/** Where a mixin injects, as `@At` spells it. */
@Serializable
data class ValidateAt(
    /** `INVOKE` or `FIELD`; both match one instruction by its target key. */
    val value: String = "INVOKE",
    /** The instruction's own target, `owner:name:descriptor`. */
    val target: String,
)

/** One mixin target to follow across a range: the method it hooks, and where inside it. */
@Serializable
data class ValidateTarget(
    /** The caller's own label for this target, echoed back on the result. */
    val id: String,
    val owner: String,
    val method: String,
    /** Null follows every overload of the name. */
    val descriptor: String? = null,
    val at: ValidateAt? = null,
)

@Serializable
data class ValidateRequest(
    val namespace: String = "mojmap",
    val targets: List<ValidateTarget> = emptyList(),
)

/** A run of consecutive versions that answer the same way about one target. */
@Serializable
data class ValidateSpan(
    val from: String,
    val to: String,
    val versions: Int,
    /** `ok` | `renamed` | `inherited` | `call_moved` | `missing`. */
    val status: String,
    /** How many instructions in the body hit `at`. Only on `ok`, and only when `at` was given. */
    val atCount: Int? = null,
    /** The declaration to hook instead, in key form. On `renamed` and `inherited`. */
    val closest: String? = null,
    /** `owner#method` the call went to, when it could be paired. On `call_moved`. */
    val movedTo: String? = null,
)

@Serializable
data class ValidateEntry(
    val id: String,
    val spans: List<ValidateSpan>,
)

@Serializable
data class ValidateResponse(
    val namespace: String,
    val results: List<ValidateEntry>,
)
