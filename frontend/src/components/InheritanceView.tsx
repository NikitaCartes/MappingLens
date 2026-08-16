import { useEffect, useMemo, useState } from "react";
import { Empty, Spin, Tree, Typography } from "antd";
import type { DataNode } from "antd/es/tree";
import { fetchHierarchy } from "../api";
import type { HierarchyResponse } from "../types";
import type { HierarchyTab } from "../tabs";
import { useOpenClass } from "../openClass";
import { messageOf, simpleClassName } from "../util";

// ponytail: antd Tree (already a dep) instead of ReactFlow+dagre. Add a graph canvas only if a
// visual DAG is explicitly wanted; the up/down trees already convey the hierarchy and navigate.

interface ClsNode extends DataNode {
  cls: string;
}

function label(name: string, isInterface: boolean, isAbstract: boolean) {
  const tag = isInterface ? "interface" : isAbstract ? "abstract" : null;
  return (
    <span style={{ fontStyle: isInterface || isAbstract ? "italic" : "normal" }}>
      {simpleClassName(name)}
      {tag && <span className="hint" style={{ marginLeft: 6, fontSize: "0.8em" }}>{tag}</span>}
    </span>
  );
}

/** Build a tree from `root`, expanding via `next` (parents for supertypes, children for subtypes). */
function buildTree(
  root: string,
  next: (name: string) => string[],
  info: Map<string, { isInterface: boolean; isAbstract: boolean }>,
): ClsNode[] {
  const make = (name: string, path: string, seen: Set<string>): ClsNode => {
    const meta = info.get(name);
    const kids = seen.has(name) ? [] : next(name);
    const nextSeen = new Set(seen).add(name);
    return {
      key: path,
      cls: name,
      title: label(name, meta?.isInterface ?? false, meta?.isAbstract ?? false),
      children: kids.map((k) => make(k, `${path}/${k}`, nextSeen)),
    };
  };
  return [make(root, root, new Set())];
}

export function InheritanceView({ tab }: { tab: HierarchyTab }) {
  const openClass = useOpenClass();
  const [data, setData] = useState<HierarchyResponse | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>(undefined);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(undefined);
    fetchHierarchy(tab.version, tab.className, tab.namespace, controller.signal)
      .then((r) => {
        setData(r);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === "AbortError") return;
        setError(messageOf(err));
        setLoading(false);
      });
    return () => controller.abort();
  }, [tab.version, tab.className, tab.namespace]);

  const { supertypes, subtypes } = useMemo(() => {
    if (!data) return { supertypes: [] as ClsNode[], subtypes: [] as ClsNode[] };
    const info = new Map(data.nodes.map((n) => [n.name, { isInterface: n.isInterface, isAbstract: n.isAbstract }]));
    const parentsOf = new Map<string, string[]>();
    const childrenOf = new Map<string, string[]>();
    const push = (m: Map<string, string[]>, k: string, val: string) => {
      const arr = m.get(k);
      if (arr) arr.push(val);
      else m.set(k, [val]);
    };
    for (const e of data.edges) {
      push(childrenOf, e.parent, e.child);
      push(parentsOf, e.child, e.parent);
    }
    return {
      supertypes: buildTree(data.root, (n) => parentsOf.get(n) ?? [], info),
      subtypes: buildTree(data.root, (n) => childrenOf.get(n) ?? [], info),
    };
  }, [data]);

  const open = (_: unknown, info: { node: ClsNode }) => {
    openClass({ names: { [tab.namespace]: info.node.cls }, version: tab.version, namespace: tab.namespace });
  };

  if (error) return <p className="hint error codeview-message">{error}</p>;

  const hasSubtypes = subtypes[0]?.children && subtypes[0].children.length > 0;

  return (
    <Spin spinning={loading} classNames={{ root: "codeview-spin" }}>
      <div style={{ padding: "0.75rem 1rem", overflow: "auto", height: "100%" }}>
        <Typography.Text type="secondary">Supertypes</Typography.Text>
        <Tree treeData={supertypes} defaultExpandAll selectable onSelect={open} blockNode />
        <Typography.Text type="secondary" style={{ display: "block", marginTop: "1rem" }}>
          Subtypes
        </Typography.Text>
        {hasSubtypes ? (
          <Tree treeData={subtypes} defaultExpandAll selectable onSelect={open} blockNode />
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No known subtypes" />
        )}
      </div>
    </Spin>
  );
}

export function hierarchyTabLabel(tab: HierarchyTab): string {
  return `⇕ ${simpleClassName(tab.className)}`;
}
