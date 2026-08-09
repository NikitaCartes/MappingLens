// Mirrors the MappingLens REST DTOs (dev.mappinglens.model.Dtos.kt).

export type Namespace = "yarn" | "mojmap" | "intermediary" | "obfuscated";
export type SearchNamespace = "all" | "yarn" | "mojmap" | "intermediary";
export type SearchType = "all" | "class" | "method" | "field";

export interface VersionInfo {
  id: string;
  releaseType: string;
  releaseTime?: string | null;
  protocolVersion?: number | null;
  hasYarn: boolean;
  hasMojmap: boolean;
  hasIntermediary: boolean;
  classCount: number;
  methodCount: number;
  fieldCount: number;
  indexedAt: string;
}

export interface VersionListResponse {
  versions: VersionInfo[];
}

export interface ClassRef {
  intermediary?: string | null;
  yarn?: string | null;
  mojmap?: string | null;
  obfuscated?: string | null;
}

export interface SearchResultEntry {
  type: "class" | "method" | "field";
  intermediary?: string | null;
  yarn?: string | null;
  mojmap?: string | null;
  obfuscated?: string | null;
  owner?: ClassRef | null;
  descriptor?: string | null;
  score: number;
}

export interface SearchResponse {
  query: string;
  version: string;
  totalResults: number;
  results: SearchResultEntry[];
}

// --- Code / structure / compare ---

export type SourceNamespace = "yarn" | "mojmap";

export interface ClassEntry {
  obfuscated?: string | null;
  intermediary?: string | null;
  yarn?: string | null;
  mojmap?: string | null;
  presence?: string | null; // both | yarn_only | mojmap_only
}

export interface ClassListResponse {
  version: string;
  classes: ClassEntry[];
}

/** Names of one class across namespaces — what a code tab carries so it can switch namespace. */
export interface ClassNames {
  obfuscated?: string | null;
  intermediary?: string | null;
  yarn?: string | null;
  mojmap?: string | null;
}

export interface SourceResponse {
  version: string;
  class: string;
  namespace: string;
  source: string;
  path: string;
}

export interface BytecodeResponse {
  version: string;
  class: string;
  bytecode: string;
}

export interface BlameResponse {
  version: string;
  class: string;
  namespace: string;
  path: string;
  /** Versions referenced by `lines`, each listed once. */
  versions: string[];
  /** Index into `versions` for each line of the file, line 1 first. */
  lines: number[];
}

export interface DiffEntryItem {
  type: string;
  name?: string | null;
  intermediary?: string | null;
  owner?: string | null;
  oldName?: string | null;
  newName?: string | null;
}

export interface DiffChanges {
  added: DiffEntryItem[];
  removed: DiffEntryItem[];
  renamed: DiffEntryItem[];
}

export interface DiffSummary {
  classesAdded: number;
  classesRemoved: number;
  classesRenamed: number;
  methodsAdded: number;
  methodsRemoved: number;
  methodsRenamed: number;
  fieldsAdded: number;
  fieldsRemoved: number;
  fieldsRenamed: number;
}

export interface DiffResponse {
  from: string;
  to: string;
  namespace: string;
  changes: DiffChanges;
  summary: DiffSummary;
}

export interface FileChange {
  path: string;
  methodsAdded: number;
  methodsRemoved: number;
  fieldsAdded: number;
  fieldsRemoved: number;
}

export interface FileDiff {
  added: string[];
  removed: string[];
  modified: FileChange[];
}

export interface FileDiffResponse {
  from: string;
  to: string;
  namespace: string;
  files: FileDiff;
}

export interface HierarchyNode {
  name: string;
  simpleName: string;
  isInterface: boolean;
  isAbstract: boolean;
}

export interface HierarchyEdge {
  parent: string;
  child: string;
}

export interface HierarchyResponse {
  version: string;
  namespace: string;
  root: string;
  nodes: HierarchyNode[];
  edges: HierarchyEdge[];
}

export interface ReferenceItem {
  owner: string;
  ownerSimple: string;
  member?: string | null;
  descriptor?: string | null;
  kind: string; // class | method | field
}

export interface ReferenceResponse {
  version: string;
  namespace: string;
  query: string;
  references: ReferenceItem[];
}

export interface SourceToken {
  startLine: number;
  startColumn: number;
  endLine: number;
  endColumn: number; // exclusive (one past last char)
  type: "class" | "method" | "field";
  className: string; // owner internal name (slashes, `$` for nested)
  name?: string | null;
  descriptor?: string | null;
  declaration: boolean;
}

export interface TokensResponse {
  version: string;
  class: string;
  namespace: string;
  source: string;
  tokens: SourceToken[];
}
