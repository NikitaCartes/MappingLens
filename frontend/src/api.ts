import type {
  SearchNamespace,
  SearchResponse,
  SearchType,
  VersionInfo,
  VersionListResponse,
} from "./types";

const BASE = "/api/v1";

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
