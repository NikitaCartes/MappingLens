import { useEffect, useState } from "react";
import { Editor } from "@monaco-editor/react";
import { Segmented, Spin } from "antd";
import type { CodeTab } from "../tabs";
import { nameIn } from "../tabs";
import type { SourceNamespace } from "../types";
import { fetchBytecode, fetchSource, ApiRequestError } from "../api";
import { simpleClassName } from "../util";
import { Copyable } from "./Copyable";

type Mode = "source" | "bytecode";

const EDITOR_OPTIONS = {
  readOnly: true,
  domReadOnly: true,
  minimap: { enabled: true },
  scrollBeyondLastLine: false,
  fontSize: 13,
  tabSize: 3,
  automaticLayout: true,
} as const;

export function CodeView({ tab }: { tab: CodeTab }) {
  const [mode, setMode] = useState<Mode>("source");
  const [namespace, setNamespace] = useState<SourceNamespace>(tab.namespace);
  const [content, setContent] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>(undefined);

  // Reset the namespace when a different class is shown in this slot.
  useEffect(() => setNamespace(tab.namespace), [tab.key, tab.namespace]);

  const className = nameIn(tab.names, namespace);

  useEffect(() => {
    if (!className) {
      setError(`No ${namespace} mapping for this class`);
      setContent("");
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(undefined);
    const req =
      mode === "source"
        ? fetchSource(tab.version, className, namespace, controller.signal).then((r) => r.source)
        : fetchBytecode(tab.version, className, namespace, controller.signal).then((r) => r.bytecode);
    req
      .then((text) => {
        setContent(text);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === "AbortError") return;
        setError(err instanceof ApiRequestError || err instanceof Error ? err.message : String(err));
        setContent("");
        setLoading(false);
      });
    return () => controller.abort();
  }, [tab.version, className, namespace, mode]);

  const hasYarn = !!tab.names.yarn;
  const hasMojmap = !!tab.names.mojmap;

  return (
    <div className="codeview">
      <div className="codeview-toolbar">
        <span className="codeview-class" title={className ?? undefined}>
          {className ? <Copyable text={className} /> : <span className="hint">unmapped</span>}
        </span>
        <div className="codeview-controls">
          <Segmented
            size="small"
            value={namespace}
            onChange={(val) => setNamespace(val as SourceNamespace)}
            options={[
              { label: "Yarn", value: "yarn", disabled: !hasYarn },
              { label: "Mojmap", value: "mojmap", disabled: !hasMojmap },
            ]}
          />
          <Segmented
            size="small"
            value={mode}
            onChange={(val) => setMode(val as Mode)}
            options={[
              { label: "Source", value: "source" },
              { label: "Bytecode", value: "bytecode" },
            ]}
          />
        </div>
      </div>
      <div className="codeview-body">
        {error ? (
          <p className="hint error codeview-message">{error}</p>
        ) : (
          <Spin spinning={loading} classNames={{ root: "codeview-spin" }}>
            <Editor
              theme="ml-dark"
              language={mode === "bytecode" ? "bytecode" : "java"}
              path={`${tab.key}|${namespace}|${mode}`}
              value={content}
              options={EDITOR_OPTIONS}
            />
          </Spin>
        )}
      </div>
    </div>
  );
}

export function tabLabel(tab: CodeTab): string {
  const name = nameIn(tab.names, tab.namespace);
  return name ? simpleClassName(name) : "class";
}
