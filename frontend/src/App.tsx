import { useCallback, useEffect, useMemo, useState } from "react";
import { App as AntApp, ConfigProvider, Splitter, Tabs, theme } from "antd";
import { ApiRequestError, fetchVersions, search } from "./api";
import type {
  SearchNamespace,
  SearchResponse,
  SearchResultEntry,
  SearchType,
  SourceNamespace,
  VersionInfo,
} from "./types";
import { Sidebar, type Mode } from "./components/Sidebar";
import { CodeView, tabLabel } from "./components/CodeView";
import { CompareView } from "./components/CompareView";
import { InheritanceView, hierarchyTabLabel } from "./components/InheritanceView";
import { ReferencesView, referencesTabLabel } from "./components/ReferencesView";
import {
  OpenClassProvider,
  type OpenClassRequest,
  type OpenHierarchyRequest,
  type OpenReferencesRequest,
} from "./openClass";
import { classKey, type Tab } from "./tabs";

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(value), ms);
    return () => window.clearTimeout(id);
  }, [value, ms]);
  return debounced;
}

function pickDefaultVersion(versions: VersionInfo[]): string | undefined {
  // API returns newest → oldest.
  const latestRelease = versions.find((v) => v.releaseType === "release");
  return (latestRelease ?? versions[0])?.id;
}

const isAbort = (err: unknown) => err instanceof DOMException && err.name === "AbortError";
const messageOf = (err: unknown) =>
  err instanceof ApiRequestError || err instanceof Error ? err.message : String(err);

export default function App() {
  const [versions, setVersions] = useState<VersionInfo[] | null>(null);
  const [versionsError, setVersionsError] = useState<string | null>(null);
  const [selectedVersion, setSelectedVersion] = useState<string | undefined>(undefined);
  const [showSnapshots, setShowSnapshots] = useState(false);
  // ponytail: mojmap default — new versions are mojmap-only (no recent yarn). Derive per-version if yarn-only versions matter.
  const [sourceNamespace, setSourceNamespace] = useState<SourceNamespace>("mojmap");
  const [mode, setMode] = useState<Mode>("search");

  const [searchNamespace, setSearchNamespace] = useState<SearchNamespace>("all");
  const [type, setType] = useState<SearchType>("all");
  const [query, setQuery] = useState("");
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState<string | undefined>(undefined);
  const [response, setResponse] = useState<SearchResponse | undefined>(undefined);

  const [tabs, setTabs] = useState<Tab[]>([]);
  const [activeKey, setActiveKey] = useState<string | undefined>(undefined);

  const debouncedQuery = useDebounced(query, 150);

  // Load versions once.
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

  // Search on query/filter change.
  useEffect(() => {
    const q = debouncedQuery.trim();
    if (!q || !selectedVersion) {
      setResponse(undefined);
      setSearchError(undefined);
      setSearchLoading(false);
      return;
    }
    const controller = new AbortController();
    setSearchLoading(true);
    setSearchError(undefined);
    search({ q, version: selectedVersion, type, namespace: searchNamespace }, controller.signal)
      .then((res) => {
        setResponse(res);
        setSearchLoading(false);
      })
      .catch((err: unknown) => {
        if (isAbort(err)) return;
        setSearchError(messageOf(err));
        setResponse(undefined);
        setSearchLoading(false);
      });
    return () => controller.abort();
  }, [debouncedQuery, selectedVersion, type, searchNamespace]);

  const openClass = useCallback(
    (req: OpenClassRequest) => {
      const version = req.version ?? selectedVersion;
      if (!version) return;
      const namespace = req.namespace ?? sourceNamespace;
      const key = classKey(version, req.names);
      setTabs((prev) =>
        prev.some((t) => t.key === key) ? prev : [...prev, { kind: "code", key, version, names: req.names, namespace }],
      );
      setActiveKey(key);
    },
    [selectedVersion, sourceNamespace],
  );

  const openHierarchy = useCallback((req: OpenHierarchyRequest) => {
    const key = `hierarchy ${req.version} ${req.className} ${req.namespace}`;
    setTabs((prev) =>
      prev.some((t) => t.key === key)
        ? prev
        : [...prev, { kind: "hierarchy", key, version: req.version, className: req.className, namespace: req.namespace }],
    );
    setActiveKey(key);
  }, []);

  const openReferences = useCallback((req: OpenReferencesRequest) => {
    const key = `references ${req.version} ${req.query} ${req.namespace}`;
    setTabs((prev) =>
      prev.some((t) => t.key === key)
        ? prev
        : [
            ...prev,
            { kind: "references", key, version: req.version, query: req.query, title: req.title, namespace: req.namespace },
          ],
    );
    setActiveKey(key);
  }, []);

  const tabActions = useMemo(
    () => ({ openClass, openHierarchy, openReferences }),
    [openClass, openHierarchy, openReferences],
  );

  const closeTab = useCallback((key: string) => {
    setTabs((prev) => {
      const idx = prev.findIndex((t) => t.key === key);
      const next = prev.filter((t) => t.key !== key);
      setActiveKey((cur) => {
        if (cur !== key) return cur;
        const neighbour = next[idx] ?? next[idx - 1];
        return neighbour?.key;
      });
      return next;
    });
  }, []);

  const main = (() => {
    if (versionsError) {
      return (
        <p className="hint error" style={{ padding: "1rem" }}>
          Could not reach the MappingLens API: {versionsError}. Is <code>mappinglens serve</code> running on port 8080?
        </p>
      );
    }
    if (mode === "compare" && versions) {
      return <CompareView versions={versions} initialTo={selectedVersion} initialNamespace={sourceNamespace} />;
    }
    if (tabs.length === 0) {
      return (
        <div className="empty-state">
          <p>Open a class from Search or Browse to view its source and bytecode.</p>
        </div>
      );
    }
    return (
      <div className="editor-area">
        <Tabs
          type="editable-card"
          hideAdd
          size="small"
          activeKey={activeKey}
          onChange={setActiveKey}
          onEdit={(key, action) => {
            if (action === "remove") closeTab(key as string);
          }}
          items={tabs.map((t) => ({
            key: t.key,
            label:
              t.kind === "hierarchy" ? hierarchyTabLabel(t) : t.kind === "references" ? referencesTabLabel(t) : tabLabel(t),
          }))}
        />
        <div className="editor-content">
          {/* Keep every tab mounted (hide inactive ones) so switching tabs preserves each
              view's scroll position and its local toggles instead of remounting from scratch. */}
          {tabs.map((t) => (
            <div key={t.key} className="tab-pane" style={{ display: t.key === activeKey ? undefined : "none" }}>
              {t.kind === "hierarchy" ? (
                <InheritanceView tab={t} />
              ) : t.kind === "references" ? (
                <ReferencesView tab={t} />
              ) : (
                <CodeView tab={t} />
              )}
            </div>
          ))}
        </div>
      </div>
    );
  })();

  return (
    <ConfigProvider theme={{ algorithm: theme.darkAlgorithm, token: { colorPrimary: "#5b9dff" } }}>
      <AntApp>
        <OpenClassProvider value={tabActions}>
          <div className="app">
          <header className="topbar">
            <h1>MappingLens</h1>
            <span className="tagline">Minecraft mappings explorer</span>
          </header>
          <div className="layout">
            <Splitter>
              <Splitter.Panel defaultSize="340" min="220" max="65%">
                {versions ? (
                  <Sidebar
                    versions={versions}
                    selectedVersion={selectedVersion}
                    onVersion={setSelectedVersion}
                    showSnapshots={showSnapshots}
                    onShowSnapshots={setShowSnapshots}
                    sourceNamespace={sourceNamespace}
                    onSourceNamespace={setSourceNamespace}
                    mode={mode}
                    onMode={setMode}
                    query={query}
                    onQuery={setQuery}
                    searchNamespace={searchNamespace}
                    onSearchNamespace={setSearchNamespace}
                    type={type}
                    onType={setType}
                    searchLoading={searchLoading}
                    searchError={searchError}
                    results={response?.results as SearchResultEntry[] | undefined}
                  />
                ) : (
                  <div className="sidebar">
                    <p className="hint">{versionsError ?? "Loading versions…"}</p>
                  </div>
                )}
              </Splitter.Panel>
              <Splitter.Panel>
                <main className="content">{main}</main>
              </Splitter.Panel>
            </Splitter>
          </div>
          </div>
        </OpenClassProvider>
      </AntApp>
    </ConfigProvider>
  );
}
