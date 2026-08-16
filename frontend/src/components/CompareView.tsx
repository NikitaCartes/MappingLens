import { useEffect, useMemo, useState } from "react";
import { DiffEditor, Editor } from "@monaco-editor/react";
import { Button, Segmented, Select, Spin, Tabs } from "antd";
import { fetchBytecode, fetchDiff, fetchDiffFiles, fetchPatch, fetchSource, patchDownloadUrl, ApiRequestError } from "../api";
import type { DiffResponse, FileDiffResponse, SourceNamespace, VersionInfo } from "../types";
import { messageOf } from "../util";

/** Sentinel `selectedFile` meaning "the whole version-to-version patch, all files". */
const WHOLE_PATCH = "\0whole";

const SUMMARY_ROWS: { label: string; keys: [keyof DiffResponse["summary"], keyof DiffResponse["summary"], keyof DiffResponse["summary"]] }[] = [
  { label: "Classes", keys: ["classesAdded", "classesRemoved", "classesRenamed"] },
  { label: "Methods", keys: ["methodsAdded", "methodsRemoved", "methodsRenamed"] },
  { label: "Fields", keys: ["fieldsAdded", "fieldsRemoved", "fieldsRenamed"] },
];

interface Props {
  versions: VersionInfo[];
  initialTo?: string;
  initialNamespace: SourceNamespace;
}

export function CompareView({ versions, initialTo, initialNamespace }: Props) {
  // API returns newest → oldest already.
  const options = versions.map((v) => ({
    value: v.id,
    label: v.releaseType === "release" ? v.id : `${v.id} (${v.releaseType})`,
  }));

  const defaultTo = initialTo ?? versions[0]?.id;
  const defaultFrom = useMemo(() => {
    const idx = versions.findIndex((v) => v.id === defaultTo);
    return idx >= 0 && idx + 1 < versions.length ? versions[idx + 1].id : versions[0]?.id;
  }, [versions, defaultTo]);

  const [from, setFrom] = useState<string | undefined>(defaultFrom);
  const [to, setTo] = useState<string | undefined>(defaultTo);
  const [namespace, setNamespace] = useState<SourceNamespace>(initialNamespace);

  const [diff, setDiff] = useState<DiffResponse | undefined>(undefined);
  const [files, setFiles] = useState<FileDiffResponse | undefined>(undefined);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  // Default to the whole-version patch so the pane always shows something (and never sits on a
  // stale "loading" when the selection is cleared mid-flight).
  const [selectedFile, setSelectedFile] = useState<string>(WHOLE_PATCH);
  const [viewMode, setViewMode] = useState<"patch" | "source" | "bytecode">("patch");
  const [patch, setPatch] = useState("");
  const [patchStatus, setPatchStatus] = useState<"idle" | "loading" | "ready" | "empty" | "error">("idle");
  const [patchError, setPatchError] = useState<string | undefined>(undefined);
  // Full-file side-by-side comparison (source or bytecode of the class in both versions).
  const [pair, setPair] = useState<{ from: string; to: string }>({ from: "", to: "" });
  const [pairStatus, setPairStatus] = useState<"idle" | "loading" | "ready" | "empty" | "error">("idle");
  const [pairError, setPairError] = useState<string | undefined>(undefined);

  useEffect(() => {
    if (!from || !to || from === to) {
      setDiff(undefined);
      setFiles(undefined);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(undefined);
    setSelectedFile(WHOLE_PATCH);
    Promise.all([
      fetchDiff(from, to, namespace, controller.signal),
      fetchDiffFiles(from, to, namespace, controller.signal),
    ])
      .then(([d, f]) => {
        setDiff(d);
        setFiles(f);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === "AbortError") return;
        setError(messageOf(err));
        setLoading(false);
      });
    return () => controller.abort();
  }, [from, to, namespace]);

  const openFile = (path: string) => setSelectedFile(path);

  // Load the selected file in whichever mode is active. "patch" is the unified diff; "source" and
  // "bytecode" fetch the whole class in both versions for a side-by-side Monaco diff (compare two
  // files in full, not just the patch). A 404 on one side (added/removed class) becomes an empty
  // side so the diff shows a full add/remove.
  const isWhole = selectedFile === WHOLE_PATCH;

  useEffect(() => {
    if (!selectedFile || !from || !to) return;
    const controller = new AbortController();
    // The whole-version patch is always a unified-diff view (no single class for source/bytecode).
    if (viewMode === "patch" || isWhole) {
      setPatchStatus("loading");
      setPatchError(undefined);
      fetchPatch(from, to, namespace, isWhole ? undefined : selectedFile, controller.signal)
        .then((text) => {
          setPatch(text);
          setPatchStatus(text.trim() ? "ready" : "empty");
        })
        .catch((err: unknown) => {
          if (err instanceof DOMException && err.name === "AbortError") return;
          setPatchError(messageOf(err));
          setPatchStatus("error");
        });
    } else {
      setPairStatus("loading");
      setPairError(undefined);
      const className = selectedFile.replace(/\.java$/, "");
      const emptyOn404 = (err: unknown): string => {
        if (err instanceof ApiRequestError && err.status === 404) return "";
        throw err;
      };
      const side =
        viewMode === "source"
          ? (vv: string) => fetchSource(vv, className, namespace, controller.signal).then((r) => r.source).catch(emptyOn404)
          : (vv: string) => fetchBytecode(vv, className, namespace, controller.signal).then((r) => r.bytecode).catch(emptyOn404);
      Promise.all([side(from), side(to)])
        .then(([a, b]) => {
          setPair({ from: a, to: b });
          setPairStatus(a || b ? "ready" : "empty");
        })
        .catch((err: unknown) => {
          if (err instanceof DOMException && err.name === "AbortError") return;
          setPairError(messageOf(err));
          setPairStatus("error");
        });
    }
    return () => controller.abort();
  }, [selectedFile, viewMode, from, to, namespace]);

  const fileList = files?.files;

  const filesTab = (
    <div className="compare-files">
      <div className="compare-filelist">
        <ul>
          <li
            className={`filerow modified${isWhole ? " active" : ""}`}
            onClick={() => openFile(WHOLE_PATCH)}
          >
            <span className="filemark">≡</span> Whole patch (all files)
          </li>
        </ul>
        {!fileList ? (
          <p className="hint">Pick two different versions.</p>
        ) : fileList.added.length + fileList.removed.length + fileList.modified.length === 0 ? (
          <p className="hint">No changed source files (or source store unavailable).</p>
        ) : (
          <ul>
            {fileList.modified.map((f) => (
              <li
                key={`m:${f.path}`}
                className={`filerow modified${selectedFile === f.path ? " active" : ""}`}
                onClick={() => openFile(f.path)}
              >
                <span className="filemark">~</span> {f.path}
              </li>
            ))}
            {fileList.added.map((p) => (
              <li
                key={`a:${p}`}
                className={`filerow added${selectedFile === p ? " active" : ""}`}
                onClick={() => openFile(p)}
              >
                <span className="filemark">+</span> {p}
              </li>
            ))}
            {fileList.removed.map((p) => (
              <li
                key={`r:${p}`}
                className={`filerow removed${selectedFile === p ? " active" : ""}`}
                onClick={() => openFile(p)}
              >
                <span className="filemark">−</span> {p}
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="compare-patch">
        <div style={{ padding: "6px 8px" }}>
          <Segmented
            size="small"
            value={isWhole ? "patch" : viewMode}
            onChange={(val) => setViewMode(val as "patch" | "source" | "bytecode")}
            options={[
              { label: "Patch", value: "patch" },
              { label: "Source", value: "source", disabled: isWhole },
              { label: "Bytecode", value: "bytecode", disabled: isWhole },
            ]}
          />
        </div>
        {viewMode === "patch" || isWhole ? (
          patchStatus === "idle" ? (
            <p className="hint">Select a file to view its patch.</p>
          ) : patchStatus === "loading" ? (
            <Spin />
          ) : patchStatus === "error" ? (
            <p className="hint error">{patchError}</p>
          ) : patchStatus === "empty" ? (
            <p className="hint">No textual patch for this file (source store may be unavailable).</p>
          ) : (
            <Editor
              theme="ml-dark"
              language="diffpatch"
              path={`patch|${from}|${to}|${namespace}|${selectedFile}`}
              value={patch}
              options={{ readOnly: true, domReadOnly: true, minimap: { enabled: false }, automaticLayout: true, fontSize: 13 }}
            />
          )
        ) : pairStatus === "idle" ? (
          <p className="hint">Select a file to compare it in full.</p>
        ) : pairStatus === "loading" ? (
          <Spin />
        ) : pairStatus === "error" ? (
          <p className="hint error">{pairError}</p>
        ) : pairStatus === "empty" ? (
          <p className="hint">No {viewMode} for this class (store/jar may be unavailable).</p>
        ) : (
          <DiffEditor
            theme="ml-dark"
            language={viewMode === "source" ? "java" : "bytecode"}
            original={pair.from}
            modified={pair.to}
            options={{ readOnly: true, renderSideBySide: true, minimap: { enabled: false }, automaticLayout: true, fontSize: 13 }}
          />
        )}
      </div>
    </div>
  );

  const symbolsTab = diff ? (
    <div className="compare-symbols">
      {(["added", "removed", "renamed"] as const).map((kind) => (
        <div key={kind} className="symbol-col">
          <h3>{kind}</h3>
          <ul>
            {diff.changes[kind].slice(0, 500).map((c, i) => (
              <li key={i} className={`sym sym-${c.type}`}>
                <span className={`badge badge-${c.type}`}>{c.type}</span>{" "}
                {kind === "renamed" ? `${c.oldName} → ${c.newName}` : c.name}
              </li>
            ))}
            {diff.changes[kind].length === 0 && <li className="hint">none</li>}
          </ul>
        </div>
      ))}
    </div>
  ) : (
    <p className="hint">Pick two different versions.</p>
  );

  return (
    <div className="compareview">
      <div className="compare-toolbar">
        <Select
          size="small"
          showSearch
          style={{ minWidth: 180 }}
          placeholder="from"
          value={from}
          options={options}
          onChange={setFrom}
          optionFilterProp="label"
        />
        <span className="compare-arrow">→</span>
        <Select
          size="small"
          showSearch
          style={{ minWidth: 180 }}
          placeholder="to"
          value={to}
          options={options}
          onChange={setTo}
          optionFilterProp="label"
        />
        <Segmented
          size="small"
          value={namespace}
          onChange={(val) => setNamespace(val as SourceNamespace)}
          options={[
            { label: "Yarn", value: "yarn" },
            { label: "Mojmap", value: "mojmap" },
          ]}
        />
        {from && to && from !== to && (
          <Button
            size="small"
            href={patchDownloadUrl(from, to, namespace)}
            download={`${from}_to_${to}_${namespace}.patch`}
          >
            Download .patch
          </Button>
        )}
        {diff && (
          <div className="compare-summary">
            {SUMMARY_ROWS.map((row) => (
              <span key={row.label} className="summary-cell">
                {row.label}: <b className="added">+{diff.summary[row.keys[0]]}</b>{" "}
                <b className="removed">−{diff.summary[row.keys[1]]}</b>{" "}
                <b className="renamed">~{diff.summary[row.keys[2]]}</b>
              </span>
            ))}
          </div>
        )}
      </div>
      {from === to ? (
        <p className="hint compare-message">Choose two different versions to compare.</p>
      ) : error ? (
        <p className="hint error compare-message">{error}</p>
      ) : (
        <Spin spinning={loading} classNames={{ root: "compare-spin" }}>
          <Tabs
            className="compare-tabs"
            items={[
              { key: "files", label: "Changed files", children: filesTab },
              { key: "symbols", label: "Symbol changes", children: symbolsTab },
            ]}
          />
        </Spin>
      )}
    </div>
  );
}
