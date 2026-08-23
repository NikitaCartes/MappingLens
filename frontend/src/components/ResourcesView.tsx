import { useEffect, useMemo, useState } from "react";
import { DiffEditor, Editor } from "@monaco-editor/react";
import { Input, Segmented, Select, Spin, Splitter } from "antd";
import {
  fetchResourceDiff,
  fetchResourceText,
  fetchResourceTree,
  fetchResourceVersions,
  resourceFileUrl,
  searchResources,
} from "../api";
import type {
  ResourceChange,
  ResourceEntry,
  ResourceHit,
  ResourceVersion,
} from "../types";
import { messageOf } from "../util";

const BRANCHES = ["assets", "diff", "registries", "atlas"];

/** How a file is shown. The extension decides; the server sends the matching Content-Type. */
type Kind = "text" | "image" | "audio" | "binary";

function kindOf(path: string): Kind {
  const ext = path.slice(path.lastIndexOf(".") + 1).toLowerCase();
  if (["png", "jpg", "jpeg", "gif"].includes(ext)) return "image";
  if (["ogg", "wav", "mp3"].includes(ext)) return "audio";
  if (["json", "mcmeta", "snbt", "txt", "fsh", "vsh", "glsl"].includes(ext)) return "text";
  return "binary";
}

function languageOf(path: string): string {
  const ext = path.slice(path.lastIndexOf(".") + 1).toLowerCase();
  return ext === "json" || ext === "mcmeta" ? "json" : "plaintext";
}

const isAbort = (err: unknown) => err instanceof DOMException && err.name === "AbortError";

/** `assets/minecraft/lang` -> ["", "assets", "assets/minecraft", "assets/minecraft/lang"]. */
function crumbs(dir: string): string[] {
  const parts = dir.split("/").filter(Boolean);
  return ["", ...parts.map((_, i) => parts.slice(0, i + 1).join("/"))];
}

export function ResourcesView() {
  const [versions, setVersions] = useState<ResourceVersion[] | null>(null);
  const [fatal, setFatal] = useState<string | null>(null);

  const [branch, setBranch] = useState("assets");
  const [version, setVersion] = useState<string | undefined>(undefined);
  const [compare, setCompare] = useState<string | undefined>(undefined);
  const [panel, setPanel] = useState<"browse" | "changes" | "search">("browse");

  const [dir, setDir] = useState("");
  const [entries, setEntries] = useState<ResourceEntry[]>([]);
  const [treeError, setTreeError] = useState<string | undefined>(undefined);

  const [changes, setChanges] = useState<ResourceChange[] | null>(null);
  const [changesLoading, setChangesLoading] = useState(false);

  const [query, setQuery] = useState("");
  const [searchType, setSearchType] = useState<"content" | "translation">("content");
  const [hits, setHits] = useState<ResourceHit[] | null>(null);
  const [searchLoading, setSearchLoading] = useState(false);

  const [selected, setSelected] = useState<string | undefined>(undefined);
  const [text, setText] = useState<string | null>(null);
  const [textBefore, setTextBefore] = useState<string | null>(null);
  const [textLoading, setTextLoading] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetchResourceVersions(controller.signal)
      .then((vs) => {
        setVersions(vs);
        setVersion((cur) => cur ?? vs[0]?.mcmetaId);
      })
      .catch((err: unknown) => {
        if (!isAbort(err)) setFatal(messageOf(err));
      });
    return () => controller.abort();
  }, []);

  const options = useMemo(
    () =>
      (versions ?? []).map((v) => ({
        value: v.mcmetaId,
        label: v.releaseType === "release" ? v.name : `${v.name} (${v.releaseType ?? "?"})`,
      })),
    [versions],
  );

  // The directory listing of the current version.
  useEffect(() => {
    if (!version || panel !== "browse") return;
    const controller = new AbortController();
    setTreeError(undefined);
    fetchResourceTree(version, branch, dir, controller.signal)
      .then((r) => setEntries(r.entries))
      .catch((err: unknown) => {
        if (isAbort(err)) return;
        setEntries([]);
        setTreeError(messageOf(err));
      });
    return () => controller.abort();
  }, [version, branch, dir, panel]);

  // The changed paths between the two selected versions.
  useEffect(() => {
    if (!version || !compare || panel !== "changes") return;
    const controller = new AbortController();
    setChangesLoading(true);
    fetchResourceDiff(compare, version, branch, dir, controller.signal)
      .then((r) => {
        setChanges(r.changes);
        setChangesLoading(false);
      })
      .catch((err: unknown) => {
        if (isAbort(err)) return;
        setChanges([]);
        setChangesLoading(false);
      });
    return () => controller.abort();
  }, [version, compare, branch, dir, panel]);

  useEffect(() => {
    const q = query.trim();
    if (!q || panel !== "search") return;
    const controller = new AbortController();
    setSearchLoading(true);
    const id = window.setTimeout(() => {
      searchResources(q, searchType, undefined, controller.signal)
        .then((r) => {
          setHits(r.results);
          setSearchLoading(false);
        })
        .catch((err: unknown) => {
          if (isAbort(err)) return;
          setHits([]);
          setSearchLoading(false);
        });
    }, 250);
    return () => {
      window.clearTimeout(id);
      controller.abort();
    };
  }, [query, searchType, panel]);

  // The selected file, at the current version and at the one it is compared against.
  useEffect(() => {
    if (!selected || !version || kindOf(selected) !== "text") {
      setText(null);
      setTextBefore(null);
      return;
    }
    const controller = new AbortController();
    setTextLoading(true);
    const both = Promise.all([
      fetchResourceText(version, branch, selected, controller.signal),
      compare ? fetchResourceText(compare, branch, selected, controller.signal) : Promise.resolve(null),
    ]);
    both
      .then(([now, before]) => {
        setText(now);
        setTextBefore(before);
        setTextLoading(false);
      })
      .catch((err: unknown) => {
        if (isAbort(err)) return;
        setText(`// ${messageOf(err)}`);
        setTextLoading(false);
      });
    return () => controller.abort();
  }, [selected, version, compare, branch]);

  if (fatal) {
    return (
      <div className="resources-empty">
        <p className="hint error">{fatal}</p>
        <p className="hint">
          Point <code>mappinglens.resources.repo</code> at a clone of <code>misode/mcmeta</code> and run{" "}
          <code>mappinglens index-resources</code>.
        </p>
      </div>
    );
  }
  if (!versions) return <div className="resources-empty"><Spin /></div>;

  const list = (() => {
    if (panel === "search") {
      if (searchLoading) return <Spin />;
      if (!hits) return <p className="hint">Type to search the file contents or the translations.</p>;
      if (hits.length === 0) return <p className="hint">No match.</p>;
      return (
        <ul className="resource-list">
          {hits.map((hit, i) => (
            <li
              key={i}
              className="resource-hit"
              onClick={() => {
                setBranch(hit.branch);
                setSelected(hit.path);
                setDir(hit.path.slice(0, Math.max(0, hit.path.lastIndexOf("/"))));
              }}
            >
              {hit.key && <span className="resource-key">{hit.key}</span>}
              {hit.value && <span className="resource-value">{hit.value}</span>}
              <span className="resource-path">
                {hit.branch}: {hit.path}
              </span>
              <span className="resource-range">
                {hit.fromVersion} … {hit.toVersion}
              </span>
            </li>
          ))}
        </ul>
      );
    }
    if (panel === "changes") {
      if (!compare) return <p className="hint">Pick a version to compare against.</p>;
      if (changesLoading) return <Spin />;
      if (!changes || changes.length === 0) return <p className="hint">Nothing changed under this path.</p>;
      return (
        <ul className="resource-list">
          {changes.map((change) => (
            <li
              key={change.path}
              className={`resource-change resource-${change.changeType}${change.path === selected ? " selected" : ""}`}
              onClick={() => setSelected(change.path)}
            >
              <span className={`badge badge-${change.changeType}`}>{change.changeType[0].toUpperCase()}</span>
              <span className="resource-path">{change.path}</span>
            </li>
          ))}
        </ul>
      );
    }
    if (treeError) return <p className="hint error">{treeError}</p>;
    return (
      <ul className="resource-list">
        {dir && (
          <li className="resource-dir" onClick={() => setDir(dir.slice(0, Math.max(0, dir.lastIndexOf("/"))))}>
            ..
          </li>
        )}
        {entries.map((entry) =>
          entry.directory ? (
            <li key={entry.path} className="resource-dir" onClick={() => setDir(entry.path.replace(/\/$/, ""))}>
              {entry.name}/
            </li>
          ) : (
            <li
              key={entry.path}
              className={`resource-file${entry.path === selected ? " selected" : ""}`}
              onClick={() => setSelected(entry.path)}
            >
              <span className="resource-path">{entry.name}</span>
              <span className="resource-size">{entry.size}</span>
            </li>
          ),
        )}
      </ul>
    );
  })();

  const viewer = (() => {
    if (!selected || !version) return <p className="hint">Select a file.</p>;
    const kind = kindOf(selected);
    const url = resourceFileUrl(version, branch, selected);
    if (kind === "image") {
      return (
        <div className="resource-media">
          {compare && <img alt={`${selected} at ${compare}`} src={resourceFileUrl(compare, branch, selected)} />}
          <img alt={selected} src={url} />
        </div>
      );
    }
    if (kind === "audio") {
      return (
        <div className="resource-media">
          {compare && <audio controls src={resourceFileUrl(compare, branch, selected)} />}
          <audio controls src={url} />
        </div>
      );
    }
    if (kind === "binary") {
      return (
        <p className="hint">
          Binary file. <a href={url}>Open the raw bytes</a>.
        </p>
      );
    }
    if (textLoading) return <Spin />;
    if (compare) {
      return (
        <DiffEditor
          theme="ml-dark"
          language={languageOf(selected)}
          original={textBefore ?? ""}
          modified={text ?? ""}
          options={{ readOnly: true, renderSideBySide: true, minimap: { enabled: false }, automaticLayout: true, fontSize: 13 }}
        />
      );
    }
    return (
      <Editor
        theme="ml-dark"
        language={languageOf(selected)}
        path={`resource|${branch}|${version}|${selected}`}
        value={text ?? ""}
        options={{ readOnly: true, domReadOnly: true, minimap: { enabled: false }, automaticLayout: true, fontSize: 13 }}
      />
    );
  })();

  return (
    <div className="resourcesview">
      <div className="compare-toolbar">
        <Select
          size="small"
          style={{ minWidth: 110 }}
          value={branch}
          options={BRANCHES.map((b) => ({ value: b, label: b }))}
          onChange={(b) => {
            setBranch(b);
            setDir("");
            setSelected(undefined);
          }}
        />
        <Select
          size="small"
          showSearch
          style={{ minWidth: 200 }}
          placeholder="version"
          value={version}
          options={options}
          onChange={setVersion}
          optionFilterProp="label"
        />
        <span className="compare-arrow">vs</span>
        <Select
          size="small"
          showSearch
          allowClear
          style={{ minWidth: 200 }}
          placeholder="compare against…"
          value={compare}
          options={options}
          onChange={(value) => setCompare(value ?? undefined)}
          optionFilterProp="label"
        />
        <Segmented
          size="small"
          value={panel}
          options={[
            { value: "browse", label: "Browse" },
            { value: "changes", label: "Changes" },
            { value: "search", label: "Search" },
          ]}
          onChange={(value) => setPanel(value as typeof panel)}
        />
        {panel === "search" && (
          <>
            <Input
              size="small"
              allowClear
              style={{ width: 220 }}
              placeholder={searchType === "translation" ? "translated text" : "file contents"}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <Segmented
              size="small"
              value={searchType}
              options={[
                { value: "content", label: "Content" },
                { value: "translation", label: "Translations" },
              ]}
              onChange={(value) => setSearchType(value as typeof searchType)}
            />
          </>
        )}
      </div>
      <div className="resource-body">
        <Splitter>
          <Splitter.Panel defaultSize="380" min="240" max="60%">
            <div className="resource-panel">
              {panel === "browse" && (
                <div className="resource-crumbs">
                  {crumbs(dir).map((crumb) => (
                    <span key={crumb} onClick={() => setDir(crumb)}>
                      {crumb === "" ? branch : crumb.slice(crumb.lastIndexOf("/") + 1)}
                    </span>
                  ))}
                </div>
              )}
              <div className="resource-scroll">{list}</div>
            </div>
          </Splitter.Panel>
          <Splitter.Panel>
            <div className="resource-viewer">
              {selected && <div className="resource-title">{selected}</div>}
              <div className="resource-content">{viewer}</div>
            </div>
          </Splitter.Panel>
        </Splitter>
      </div>
    </div>
  );
}
