import { useEffect, useState } from "react";
import { ApiRequestError, fetchVersions, search } from "./api";
import type { SearchNamespace, SearchResponse, SearchType, VersionInfo } from "./types";
import { Sidebar } from "./components/Sidebar";
import { SearchBar } from "./components/SearchBar";
import { Results } from "./components/Results";

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(value), ms);
    return () => window.clearTimeout(id);
  }, [value, ms]);
  return debounced;
}

function pickDefaultVersion(versions: VersionInfo[]): string | undefined {
  // versions arrive oldest -> newest in canonical semver order
  const newestFirst = [...versions].reverse();
  const latestRelease = newestFirst.find((v) => v.releaseType === "release");
  return (latestRelease ?? newestFirst[0])?.id;
}

function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

function messageOf(err: unknown): string {
  if (err instanceof ApiRequestError || err instanceof Error) return err.message;
  return String(err);
}

export default function App() {
  const [versions, setVersions] = useState<VersionInfo[] | null>(null);
  const [versionsError, setVersionsError] = useState<string | null>(null);
  const [selectedVersion, setSelectedVersion] = useState<string | undefined>(undefined);
  const [showSnapshots, setShowSnapshots] = useState(false);
  const [namespace, setNamespace] = useState<SearchNamespace>("all");
  const [type, setType] = useState<SearchType>("all");
  const [query, setQuery] = useState("");

  const [loading, setLoading] = useState(false);
  const [searchError, setSearchError] = useState<string | undefined>(undefined);
  const [response, setResponse] = useState<SearchResponse | undefined>(undefined);

  const debouncedQuery = useDebounced(query, 150);

  // Load the version list once.
  useEffect(() => {
    const controller = new AbortController();
    fetchVersions(controller.signal)
      .then((vs) => {
        setVersions(vs);
        setSelectedVersion((cur) => cur ?? pickDefaultVersion(vs));
      })
      .catch((err: unknown) => {
        if (isAbort(err)) return;
        setVersionsError(messageOf(err));
      });
    return () => controller.abort();
  }, []);

  // Run a search whenever the query or any filter changes.
  useEffect(() => {
    const q = debouncedQuery.trim();
    if (!q || !selectedVersion) {
      setResponse(undefined);
      setSearchError(undefined);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setSearchError(undefined);
    search({ q, version: selectedVersion, type, namespace }, controller.signal)
      .then((res) => {
        setResponse(res);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (isAbort(err)) return;
        setSearchError(messageOf(err));
        setResponse(undefined);
        setLoading(false);
      });
    return () => controller.abort();
  }, [debouncedQuery, selectedVersion, type, namespace]);

  return (
    <div className="app">
      <header className="topbar">
        <h1>MappingLens</h1>
        <span className="tagline">Minecraft mappings explorer</span>
      </header>
      <div className="layout">
        {versions && (
          <Sidebar
            versions={versions}
            selectedVersion={selectedVersion}
            onVersion={setSelectedVersion}
            showSnapshots={showSnapshots}
            onShowSnapshots={setShowSnapshots}
            namespace={namespace}
            onNamespace={setNamespace}
            type={type}
            onType={setType}
          />
        )}
        <main className="content">
          {versionsError ? (
            <p className="hint error">
              Could not reach the MappingLens API: {versionsError}. Is <code>mappinglens serve</code> running on
              port 8080?
            </p>
          ) : (
            <>
              <SearchBar value={query} onChange={setQuery} count={response?.totalResults} />
              <Results
                loading={loading}
                error={searchError}
                results={response?.results}
                query={debouncedQuery}
                hasVersion={!!selectedVersion}
              />
            </>
          )}
        </main>
      </div>
    </div>
  );
}
