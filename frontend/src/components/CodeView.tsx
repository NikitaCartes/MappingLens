import { useEffect, useRef, useState } from "react";
import { Editor } from "@monaco-editor/react";
import type { editor as MonacoEditor } from "monaco-editor";
import { App, Button, Segmented, Spin } from "antd";
import type { CodeTab } from "../tabs";
import { nameIn } from "../tabs";
import type { BlameResponse, ClassNames, SourceNamespace, VersionInfo } from "../types";
import { fetchBlame, fetchBytecode, fetchSource, fetchTokens, fetchVersions } from "../api";
import { messageOf, simpleClassName } from "../util";
import { useOpenClass, useOpenHierarchy, useOpenReferences } from "../openClass";
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

/** Blame labels each line with a release date, and the version list is the same for every tab. */
let versionsOnce: Promise<VersionInfo[]> | null = null;
const allVersions = () => (versionsOnce ??= fetchVersions());

/**
 * Blame is a view preference rather than a property of one tab: a class opened while blame is on
 * opens with blame on. Only new tabs read this — toggling it leaves the tabs already open alone.
 */
let blameDefault = false;


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

  const [codeEditor, setCodeEditor] = useState<MonacoEditor.IStandaloneCodeEditor | null>(null);
  const [blameOn, setBlameOn] = useState(blameDefault);
  const [blame, setBlame] = useState<BlameResponse | null>(null);
  const [blameError, setBlameError] = useState<string | undefined>(undefined);
  const [versions, setVersions] = useState<VersionInfo[] | null>(null);

  const openClass = useOpenClass();
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
        setError(messageOf(err));
        setContent("");
        setLoading(false);
      });
    return () => controller.abort();
  }, [tab.version, className, namespace, mode]);

  // --- blame: which version last changed each line ---

  // The release dates shown in the hover; the annotation itself needs only the blame response.
  useEffect(() => {
    if (!blameOn || versions) return;
    allVersions().then(setVersions, () => setVersions([]));
  }, [blameOn, versions]);

  useEffect(() => {
    setBlame(null);
    setBlameError(undefined);
    if (!blameOn || mode !== "source" || !className) return;
    const controller = new AbortController();
    fetchBlame(tab.version, className, namespace, controller.signal)
      .then(setBlame)
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === "AbortError") return;
        setBlameError(messageOf(err));
      });
    return () => controller.abort();
  }, [blameOn, tab.version, className, namespace, mode]);

  // The annotation column itself: injected text before each line, so it scrolls with the code and
  // stays out of anything copied from the editor. Clicking a version opens that class as it stood
  // in that version (same resolved class name blame itself follows, so history stays continuous).
  useEffect(() => {
    if (!codeEditor || !blameOn || !blame || mode !== "source") return;
    const byId = new Map(versions?.map((ver) => [ver.id, ver]));
    const collection = codeEditor.createDecorationsCollection(
      blame.lines.map((idx, i) => {
        const version = blame.versions[idx];
        const released = byId.get(version)?.releaseTime?.slice(0, 10);
        const clickable = version !== tab.version;
        return {
          range: { startLineNumber: i + 1, startColumn: 1, endLineNumber: i + 1, endColumn: 1 },
          options: {
            // Monaco drops injected text on an empty range unless showIfCollapsed is set.
            showIfCollapsed: true,
            before: { content: version, inlineClassName: "blame-anno", inlineClassNameAffectsLetterSpacing: true },
            hoverMessage: {
              value: `Last changed in **${version}**${released ? ` (${released})` : ""}${clickable ? " — click to open" : ""}`,
            },
          },
        };
      }),
    );
    const mouseUp = codeEditor.onMouseUp((e) => {
      // Monaco renders the line again when the mouse goes down, which detaches the annotation the
      // press landed on. By the time the button comes up `e.target.element` is the line container,
      // so the class is read from whatever sits under the pointer now.
      const { clientX, clientY } = e.event.browserEvent;
      if (!document.elementFromPoint(clientX, clientY)?.closest(".blame-anno")) return;
      const line = e.target.position?.lineNumber;
      if (!line) return;
      const version = blame.versions[blame.lines[line - 1]];
      if (!version || version === tab.version) return;
      const names: ClassNames = namespace === "mojmap" ? { mojmap: blame.class } : { yarn: blame.class };
      openClass({ names, version, namespace });
    });
    return () => {
      collection.clear();
      mouseUp.dispose();
    };
  }, [codeEditor, blameOn, blame, versions, mode, namespace, tab.version, openClass]);

  const hasYarn = !!tab.names.yarn;
  const hasMojmap = !!tab.names.mojmap;

  return (
    <div className="codeview">
      <div className="codeview-toolbar">
        <div className="codeview-title">
          <span className="codeview-class" title={className ?? undefined}>
            {className ? <Copyable text={className} /> : <span className="hint">unmapped</span>}
          </span>
          <span className="codeview-version">{tab.version}</span>
        </div>
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
          <Button
            size="small"
            type={blameOn ? "primary" : "default"}
            disabled={mode !== "source"}
            loading={blameOn && mode === "source" && !blame && !blameError}
            onClick={() => {
              blameDefault = !blameOn;
              setBlameOn(blameDefault);
            }}
          >
            Blame
          </Button>
          {blameError && <span className="hint error">{blameError}</span>}
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
                setCodeEditor(editor);
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
