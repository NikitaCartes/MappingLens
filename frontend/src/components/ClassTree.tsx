import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Empty, Input, Spin, Tree } from "antd";
import type { TreeDataNode } from "antd";
import { fetchClasses, ApiRequestError } from "../api";
import type { ClassEntry, SourceNamespace } from "../types";
import { useOpenClass } from "../openClass";

const displayName = (e: ClassEntry, ns: SourceNamespace): string | null =>
  (ns === "mojmap" ? e.mojmap : e.yarn) ?? e.intermediary ?? e.yarn ?? e.mojmap ?? e.obfuscated ?? null;

function buildTree(classes: ClassEntry[], ns: SourceNamespace, filter: string) {
  const needle = filter.trim().toLowerCase();
  const entryByKey = new Map<string, ClassEntry>();
  const root: TreeDataNode[] = [];
  const dirByPrefix = new Map<string, TreeDataNode>();

  classes.forEach((entry, i) => {
    const full = displayName(entry, ns);
    if (!full) return;
    if (needle && !full.toLowerCase().includes(needle)) return;

    const segments = full.split("/");
    let prefix = "";
    let level = root;
    for (let s = 0; s < segments.length - 1; s++) {
      prefix = prefix ? `${prefix}/${segments[s]}` : segments[s];
      let node = dirByPrefix.get(prefix);
      if (!node) {
        node = { key: `d:${prefix}`, title: segments[s], children: [], selectable: false };
        dirByPrefix.set(prefix, node);
        level.push(node);
      }
      level = node.children!;
    }
    const leafKey = `c:${i}`;
    entryByKey.set(leafKey, entry);
    level.push({ key: leafKey, title: segments[segments.length - 1], isLeaf: true });
  });

  const sort = (nodes: TreeDataNode[]) => {
    nodes.sort((a, b) => {
      const ad = !a.isLeaf;
      const bd = !b.isLeaf;
      if (ad !== bd) return ad ? -1 : 1;
      return String(a.title).localeCompare(String(b.title));
    });
    nodes.forEach((n) => n.children && sort(n.children));
  };
  sort(root);
  return { treeData: root, entryByKey };
}

const collectDirKeys = (nodes: TreeDataNode[], acc: React.Key[] = []): React.Key[] => {
  for (const n of nodes) {
    if (n.children?.length) {
      acc.push(n.key);
      collectDirKeys(n.children, acc);
    }
  }
  return acc;
};

export function ClassTree({ version, namespace }: { version: string; namespace: SourceNamespace }) {
  const openClass = useOpenClass();
  const [classes, setClasses] = useState<ClassEntry[] | null>(null);
  const [error, setError] = useState<string | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState("");
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);

  const boxRef = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState(400);
  useLayoutEffect(() => {
    const measure = () => boxRef.current && setHeight(Math.max(120, boxRef.current.clientHeight));
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(undefined);
    fetchClasses(version, controller.signal)
      .then((cs) => {
        setClasses(cs);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === "AbortError") return;
        setError(err instanceof ApiRequestError || err instanceof Error ? err.message : String(err));
        setLoading(false);
      });
    return () => controller.abort();
  }, [version]);

  const { treeData, entryByKey } = useMemo(
    () => buildTree(classes ?? [], namespace, filter),
    [classes, namespace, filter],
  );

  // Expand everything while filtering (the matched set is small); collapse when cleared.
  useEffect(() => {
    setExpandedKeys(filter.trim() ? collectDirKeys(treeData) : []);
  }, [filter, treeData]);

  return (
    <div className="classtree">
      <Input.Search
        size="small"
        allowClear
        placeholder="Filter classes…"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
      />
      <div className="classtree-body" ref={boxRef}>
        {error ? (
          <p className="hint error">{error}</p>
        ) : loading ? (
          <Spin />
        ) : treeData.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No classes" />
        ) : (
          <Tree
            treeData={treeData}
            height={height}
            blockNode
            showLine
            expandedKeys={expandedKeys}
            onExpand={(keys) => setExpandedKeys(keys)}
            onSelect={(_, info) => {
              const entry = entryByKey.get(String(info.node.key));
              if (entry) openClass({ names: entry, version, namespace });
            }}
          />
        )}
      </div>
    </div>
  );
}
