import type { SearchNamespace, SearchType, VersionInfo } from "../types";

interface Props {
  versions: VersionInfo[];
  selectedVersion?: string;
  onVersion: (v: string) => void;
  showSnapshots: boolean;
  onShowSnapshots: (v: boolean) => void;
  namespace: SearchNamespace;
  onNamespace: (v: SearchNamespace) => void;
  type: SearchType;
  onType: (v: SearchType) => void;
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
  const { versions, selectedVersion, showSnapshots } = props;
  const selected = versions.find((v) => v.id === selectedVersion);

  // The API returns versions oldest -> newest in canonical semver order; show newest first.
  const ordered = [...versions].reverse();
  const visible = ordered.filter(
    (v) => showSnapshots || v.releaseType === "release" || v.id === selectedVersion,
  );

  return (
    <aside className="sidebar">
      <section>
        <h2>Version</h2>
        <select
          className="version-select"
          value={selectedVersion ?? ""}
          onChange={(e) => props.onVersion(e.target.value)}
        >
          {visible.map((v) => (
            <option key={v.id} value={v.id}>
              {v.id}
              {v.releaseType !== "release" ? `  (${v.releaseType})` : ""}
            </option>
          ))}
        </select>
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
      </section>

      <section>
        <h2>Search in</h2>
        <div className="pill-group">
          {SEARCH_NAMESPACES.map((n) => (
            <button
              key={n.id}
              type="button"
              className={`pill${props.namespace === n.id ? " active" : ""}`}
              onClick={() => props.onNamespace(n.id)}
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
    </aside>
  );
}
