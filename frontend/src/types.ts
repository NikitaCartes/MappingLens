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
