package dev.mappinglens.model

import kotlinx.serialization.SerialName
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
    val descriptor: String? = null,
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
data class DiffEntryItem(
    val type: String,
    val name: String? = null,
    val intermediary: String? = null,
    val owner: String? = null,
    @SerialName("oldName") val oldName: String? = null,
    @SerialName("newName") val newName: String? = null,
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
