import type {
  BlameResponse,
  BytecodeResponse,
  ClassEntry,
  ClassListResponse,
  DiffResponse,
  FileDiffResponse,
  HierarchyResponse,
  ReferenceGroup,
  ReferenceResponse,
  ResourceDiffResponse,
  ResourceHistoryResponse,
  ResourceSearchResponse,
  ResourceTreeResponse,
  ResourceVersion,
  ResourceVersionListResponse,
  TokensResponse,
  SearchNamespace,
  SearchResponse,
  SearchType,
  SourceNamespace,
  SourceResponse,
  VersionInfo,
  VersionListResponse,
} from "./types";

const BASE = "/api/v1";

/** Encodes a version for a path segment (versions can contain spaces, e.g. "1.14 Pre-Release 1"). */
const v = (version: string) => encodeURIComponent(version);
/** Class internal names use "/" as real path separators in catch-all routes — keep them literal. */
const cls = (className: string) => className.split("/").map(encodeURIComponent).join("/");

/** Thrown for non-2xx responses, carrying the API's structured error message when present. */
export class ApiRequestError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
  }
}

async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(url, { signal });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = (await res.json()) as { message?: unknown };
      if (typeof body.message === "string") message = body.message;
    } catch {
      // no JSON body — keep the status line
    }
    throw new ApiRequestError(res.status, message);
  }
  return (await res.json()) as T;
}

export function fetchVersions(signal?: AbortSignal): Promise<VersionInfo[]> {
  return getJson<VersionListResponse>(`${BASE}/versions`, signal).then((r) => r.versions);
}

export interface SearchArgs {
  q: string;
  version?: string;
  type: SearchType;
  namespace: SearchNamespace;
  limit?: number;
  exact?: boolean;
}

export function search(args: SearchArgs, signal?: AbortSignal): Promise<SearchResponse> {
  const params = new URLSearchParams();
  params.set("q", args.q);
  if (args.version) params.set("version", args.version);
  if (args.type !== "all") params.set("type", args.type);
  if (args.namespace !== "all") params.set("namespace", args.namespace);
  params.set("limit", String(args.limit ?? 50));
  if (args.exact) params.set("exact", "true");
  return getJson<SearchResponse>(`${BASE}/search?${params.toString()}`, signal);
}

async function getText(url: string, signal?: AbortSignal): Promise<string> {
  const res = await fetch(url, { signal });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = (await res.clone().json()) as { message?: unknown };
      if (typeof body.message === "string") message = body.message;
    } catch {
      // not JSON — keep the status line
    }
    throw new ApiRequestError(res.status, message);
  }
  return res.text();
}

export function fetchClasses(version: string, signal?: AbortSignal): Promise<ClassEntry[]> {
  return getJson<ClassListResponse>(`${BASE}/classes/${v(version)}`, signal).then((r) => r.classes);
}

export function fetchSource(
  version: string,
  className: string,
  namespace: SourceNamespace,
  signal?: AbortSignal,
): Promise<SourceResponse> {
  return getJson<SourceResponse>(`${BASE}/source/${v(version)}/${cls(className)}?namespace=${namespace}`, signal);
}

export function fetchHierarchy(
  version: string,
  className: string,
  namespace: SourceNamespace,
  signal?: AbortSignal,
): Promise<HierarchyResponse> {
  return getJson<HierarchyResponse>(`${BASE}/hierarchy/${v(version)}/${cls(className)}?namespace=${namespace}`, signal);
}

export function fetchTokens(
  version: string,
  className: string,
  namespace: SourceNamespace,
  signal?: AbortSignal,
): Promise<TokensResponse> {
  return getJson<TokensResponse>(`${BASE}/tokens/${v(version)}/${cls(className)}?namespace=${namespace}`, signal);
}

// The endpoint answers several targets across a range of versions; the UI asks about one of each,
// so unwrap that single group here and leave the views on the shape they already read.
export function fetchReferences(
  version: string,
  query: string,
  namespace: SourceNamespace,
  signal?: AbortSignal,
): Promise<ReferenceGroup> {
  return getJson<ReferenceResponse>(
    `${BASE}/references/${v(version)}?q=${encodeURIComponent(query)}&namespace=${namespace}`,
    signal,
  ).then((r) => r.results[0] ?? { version, query, references: [] });
}

export function fetchBytecode(
  version: string,
  className: string,
  namespace: SourceNamespace,
  signal?: AbortSignal,
): Promise<BytecodeResponse> {
  return getJson<BytecodeResponse>(`${BASE}/bytecode/${v(version)}/${cls(className)}?namespace=${namespace}`, signal);
}

/** Version that last changed each line of the class source. */
export function fetchBlame(
  version: string,
  className: string,
  namespace: SourceNamespace,
  signal?: AbortSignal,
): Promise<BlameResponse> {
  return getJson<BlameResponse>(`${BASE}/blame/${v(version)}/${cls(className)}?namespace=${namespace}`, signal);
}

export function fetchDiff(
  from: string,
  to: string,
  namespace: SourceNamespace,
  signal?: AbortSignal,
): Promise<DiffResponse> {
  return getJson<DiffResponse>(
    `${BASE}/diff?from=${v(from)}&to=${v(to)}&namespace=${namespace}&limit=5000`,
    signal,
  );
}

export function fetchDiffFiles(
  from: string,
  to: string,
  namespace: SourceNamespace,
  signal?: AbortSignal,
): Promise<FileDiffResponse> {
  return getJson<FileDiffResponse>(
    `${BASE}/diff/files?from=${v(from)}&to=${v(to)}&namespace=${namespace}`,
    signal,
  );
}

/** Unified patch text (text/x-diff). Omit `path` for the whole version-to-version patch. */
export function fetchPatch(
  from: string,
  to: string,
  namespace: SourceNamespace,
  path: string | undefined,
  signal?: AbortSignal,
): Promise<string> {
  const params = new URLSearchParams({ from, to, namespace, format: "patch", context: "3" });
  if (path) params.set("path", path);
  return getText(`${BASE}/diff/patch?${params.toString()}`, signal);
}

/** Same-origin URL for the whole git-format patch — used as an <a download> href. */
export function patchDownloadUrl(from: string, to: string, namespace: SourceNamespace): string {
  const params = new URLSearchParams({ from, to, namespace, format: "git", context: "3", limit: "50000" });
  return `${BASE}/diff/patch?${params.toString()}`;
}

// ---- Resources: the mcmeta explorer ----

export function fetchResourceVersions(signal?: AbortSignal): Promise<ResourceVersion[]> {
  return getJson<ResourceVersionListResponse>(`${BASE}/resources/versions`, signal).then((r) => r.versions);
}

export function fetchResourceTree(
  version: string,
  branch: string,
  path: string,
  signal?: AbortSignal,
): Promise<ResourceTreeResponse> {
  const params = new URLSearchParams({ version, branch, path });
  return getJson<ResourceTreeResponse>(`${BASE}/resources/tree?${params.toString()}`, signal);
}

export function fetchResourceDiff(
  from: string,
  to: string,
  branch: string,
  path: string,
  signal?: AbortSignal,
): Promise<ResourceDiffResponse> {
  const params = new URLSearchParams({ from, to, branch, path });
  return getJson<ResourceDiffResponse>(`${BASE}/resources/diff?${params.toString()}`, signal);
}

export function fetchResourceHistory(
  branch: string,
  path: string,
  signal?: AbortSignal,
): Promise<ResourceHistoryResponse> {
  const params = new URLSearchParams({ branch, path });
  return getJson<ResourceHistoryResponse>(`${BASE}/resources/history?${params.toString()}`, signal);
}

export function searchResources(
  q: string,
  type: "content" | "translation",
  version: string | undefined,
  signal?: AbortSignal,
): Promise<ResourceSearchResponse> {
  const params = new URLSearchParams({ q, type, limit: "200" });
  if (version) params.set("version", version);
  return getJson<ResourceSearchResponse>(`${BASE}/resources/search?${params.toString()}`, signal);
}

/** Same-origin URL of the raw bytes — used directly as an img or audio src. */
export function resourceFileUrl(version: string, branch: string, path: string): string {
  return `${BASE}/resources/file?${new URLSearchParams({ version, branch, path }).toString()}`;
}

/** The file as text. Returns null when the path is absent at that version. */
export function fetchResourceText(
  version: string,
  branch: string,
  path: string,
  signal?: AbortSignal,
): Promise<string | null> {
  return getText(resourceFileUrl(version, branch, path), signal).catch((err: unknown) => {
    if (err instanceof ApiRequestError && err.status === 404) return null;
    throw err;
  });
}
