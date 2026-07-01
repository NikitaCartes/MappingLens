import { useEffect, useRef, useState } from "react";
import { Editor } from "@monaco-editor/react";
import { App, Segmented, Spin } from "antd";
import type { CodeTab } from "../tabs";
import { nameIn } from "../tabs";
import type { SourceNamespace } from "../types";
import { fetchBytecode, fetchSource, fetchTokens, ApiRequestError } from "../api";
import { simpleClassName } from "../util";
import { useOpenHierarchy, useOpenReferences } from "../openClass";
import type { SourceToken } from "../types";
import { atEntry, awEntry, findTokenAtPosition, mixinEntry, referenceQuery, tokenTarget, type Target } from "../tokens";
import { Copyable } from "./Copyable";

/** Live view state read by Monaco context-menu actions (registered once, but must see current values). */
interface EditorContext {
  version: string;
  className: string | null;
  namespace: SourceNamespace;
  openHierarchy: (req: { className: string; version: string; namespace: SourceNamespace }) => void;
  openReferences: (req: { query: string; title: string; version: string; namespace: SourceNamespace }) => void;
  copy: (text: string, label: string) => void;
}

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

  const openHierarchy = useOpenHierarchy();
  const openReferences = useOpenReferences();
  const { message } = App.useApp();

  // Reset the namespace when a different class is shown in this slot.
  useEffect(() => setNamespace(tab.namespace), [tab.key, tab.namespace]);

  const className = nameIn(tab.names, namespace);

  const copy = (text: string, label: string) => {
    navigator.clipboard.writeText(text).then(
      () => message.success(`Copied ${label}`),
      () => message.error("Clipboard write failed"),
    );
  };

  // Monaco actions are registered once on mount; keep the current view state in a ref they can read.
  const ctxRef = useRef<EditorContext>({ version: tab.version, className, namespace, openHierarchy, openReferences, copy });
  ctxRef.current = { version: tab.version, className, namespace, openHierarchy, openReferences, copy };

  // Resolved tokens for the shown source, used to make context actions member-precise. Best-effort:
  // if they haven't loaded (or failed, or bytecode view), actions fall back to the whole class.
  const tokensRef = useRef<SourceToken[]>([]);
  useEffect(() => {
    tokensRef.current = [];
    if (mode !== "source" || !className) return;
    const controller = new AbortController();
    fetchTokens(tab.version, className, namespace, controller.signal)
      .then((r) => {
        tokensRef.current = r.tokens;
      })
      .catch(() => {
        // Tokens are optional; the actions degrade to class scope.
      });
    return () => controller.abort();
  }, [tab.version, className, namespace, mode]);

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
              onMount={(editor) => {
                // The class/member under the cursor, or the whole class when no token is resolved there.
                const targetAt = (): Target | null => {
                  const c = ctxRef.current;
                  const pos = editor.getPosition();
                  const tok = pos ? findTokenAtPosition(pos, tokensRef.current) : null;
                  if (tok) return tokenTarget(tok);
                  return c.className ? { className: c.className } : null;
                };
                editor.addAction({
                  id: "find_all_references",
                  label: "Find All References",
                  contextMenuGroupId: "navigation",
                  contextMenuOrder: 1,
                  run: () => {
                    const c = ctxRef.current;
                    const t = targetAt();
                    if (!t) return;
                    const { query, title } = referenceQuery(t);
                    c.openReferences({ query, title, version: c.version, namespace: c.namespace });
                  },
                });
                editor.addAction({
                  id: "view_inheritance",
                  label: "View Inheritance Hierarchy",
                  contextMenuGroupId: "navigation",
                  contextMenuOrder: 2,
                  run: () => {
                    const c = ctxRef.current;
                    const t = targetAt();
                    if (t) c.openHierarchy({ className: t.className, version: c.version, namespace: c.namespace });
                  },
                });
                editor.addAction({
                  id: "copy_aw",
                  label: "Copy Class Tweaker / Access Widener",
                  contextMenuGroupId: "9_cutcopypaste",
                  run: () => {
                    const t = targetAt();
                    if (t) ctxRef.current.copy(awEntry(t), "Access Widener entry");
                  },
                });
                editor.addAction({
                  id: "copy_at",
                  label: "Copy Access Transformer",
                  contextMenuGroupId: "9_cutcopypaste",
                  run: () => {
                    const t = targetAt();
                    if (t) ctxRef.current.copy(atEntry(t), "Access Transformer entry");
                  },
                });
                editor.addAction({
                  id: "copy_mixin",
                  label: "Copy Mixin Target",
                  contextMenuGroupId: "9_cutcopypaste",
                  run: () => {
                    const t = targetAt();
                    if (t) ctxRef.current.copy(mixinEntry(t), "Mixin target");
                  },
                });
              }}
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
