import { Segmented, Select } from "antd";
import type {
  SearchNamespace,
  SearchResultEntry,
  SearchType,
  SourceNamespace,
  VersionInfo,
} from "../types";
import { SearchBar } from "./SearchBar";
import { Results } from "./Results";
import { ClassTree } from "./ClassTree";

export type Mode = "search" | "browse" | "compare";

interface Props {
  versions: VersionInfo[];
  selectedVersion?: string;
  onVersion: (v: string) => void;
  showSnapshots: boolean;
  onShowSnapshots: (v: boolean) => void;
  sourceNamespace: SourceNamespace;
  onSourceNamespace: (ns: SourceNamespace) => void;
  mode: Mode;
  onMode: (m: Mode) => void;
  // search
  query: string;
  onQuery: (q: string) => void;
  searchNamespace: SearchNamespace;
  onSearchNamespace: (n: SearchNamespace) => void;
  type: SearchType;
  onType: (t: SearchType) => void;
  searchLoading: boolean;
  searchError?: string;
  results?: SearchResultEntry[];
}

const SEARCH_NAMESPACES: { id: SearchNamespace; label: string }[] = [
  { id: "all", label: "All" },
  { id: "mojmap", label: "Mojmap" },
  { id: "yarn", label: "Yarn" },
  { id: "intermediary", label: "Intermediary" },
];

const TYPES: { id: SearchType; label: string }[] = [
  { id: "all", label: "All" },
  { id: "class", label: "Classes" },
  { id: "method", label: "Methods" },
  { id: "field", label: "Fields" },
];

export function Sidebar(props: Props) {
  const { versions, selectedVersion, showSnapshots, sourceNamespace, mode } = props;
  const selected = versions.find((v) => v.id === selectedVersion);

  // API returns newest → oldest already.
  const visible = versions.filter(
    (v) => showSnapshots || v.releaseType === "release" || v.id === selectedVersion,
  );

  return (
    <aside className="sidebar">
      <section>
        <h2>Version</h2>
        <Select
          className="version-antd-select"
          size="small"
          showSearch
          style={{ width: "100%" }}
          value={selectedVersion}
          onChange={props.onVersion}
          optionFilterProp="label"
          options={visible.map((v) => ({
            value: v.id,
            label: v.releaseType === "release" ? v.id : `${v.id} (${v.releaseType})`,
          }))}
        />
        <label className="checkbox">
          <input
            type="checkbox"
            checked={showSnapshots}
            onChange={(e) => props.onShowSnapshots(e.target.checked)}
          />
          Show snapshots
        </label>
        {selected && (
          <>
            <div className="ns-availability">
              <span className={selected.hasMojmap ? "on" : "off"}>Mojmap</span>
              <span className={selected.hasYarn ? "on" : "off"}>Yarn</span>
              <span className={selected.hasIntermediary ? "on" : "off"}>Intermediary</span>
            </div>
            <p className="counts">
              {selected.classCount.toLocaleString()} classes · {selected.methodCount.toLocaleString()} methods ·{" "}
              {selected.fieldCount.toLocaleString()} fields
            </p>
          </>
        )}
        <div className="source-ns">
          <span className="source-ns-label">Source</span>
          <Segmented
            size="small"
            value={sourceNamespace}
            onChange={(val) => props.onSourceNamespace(val as SourceNamespace)}
            options={[
              { label: "Yarn", value: "yarn" },
              { label: "Mojmap", value: "mojmap" },
            ]}
          />
        </div>
      </section>

      <section>
        <Segmented
          block
          value={mode}
          onChange={(val) => props.onMode(val as Mode)}
          options={[
            { label: "Search", value: "search" },
            { label: "Browse", value: "browse" },
            { label: "Compare", value: "compare" },
          ]}
        />
      </section>

      {mode === "search" && (
        <>
          <section>
            <SearchBar value={props.query} onChange={props.onQuery} count={props.results?.length} />
          </section>
          <section>
            <h2>Search in</h2>
            <div className="pill-group">
              {SEARCH_NAMESPACES.map((n) => (
                <button
                  key={n.id}
                  type="button"
                  className={`pill${props.searchNamespace === n.id ? " active" : ""}`}
                  onClick={() => props.onSearchNamespace(n.id)}
                >
                  {n.label}
                </button>
              ))}
            </div>
          </section>
          <section>
            <h2>Type</h2>
            <div className="pill-group">
              {TYPES.map((t) => (
                <button
                  key={t.id}
                  type="button"
                  className={`pill${props.type === t.id ? " active" : ""}`}
                  onClick={() => props.onType(t.id)}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </section>
          <section className="sidebar-results">
            <Results
              loading={props.searchLoading}
              error={props.searchError}
              results={props.results}
              query={props.query}
              hasVersion={!!selectedVersion}
            />
          </section>
        </>
      )}

      {mode === "browse" && selectedVersion && (
        <section className="sidebar-tree">
          <ClassTree version={selectedVersion} namespace={sourceNamespace} />
        </section>
      )}

      {mode === "compare" && (
        <section>
          <p className="hint">Comparing versions — pick the two versions on the right.</p>
        </section>
      )}
    </aside>
  );
}
