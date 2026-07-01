import { useEffect, useState } from "react";
import { List, Spin, Tag, Typography } from "antd";
import { fetchReferences, ApiRequestError } from "../api";
import type { ReferenceItem, ReferenceResponse } from "../types";
import type { ReferencesTab } from "../tabs";
import { useOpenClass } from "../openClass";

const KIND_COLOR: Record<string, string> = { class: "blue", method: "green", field: "gold" };

export function ReferencesView({ tab }: { tab: ReferencesTab }) {
  const openClass = useOpenClass();
  const [data, setData] = useState<ReferenceResponse | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>(undefined);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(undefined);
    fetchReferences(tab.version, tab.query, tab.namespace, controller.signal)
      .then((r) => {
        setData(r);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === "AbortError") return;
        setError(err instanceof ApiRequestError || err instanceof Error ? err.message : String(err));
        setLoading(false);
      });
    return () => controller.abort();
  }, [tab.version, tab.query, tab.namespace]);

  const open = (item: ReferenceItem) =>
    openClass({ names: { [tab.namespace]: item.owner }, version: tab.version, namespace: tab.namespace });

  if (error) return <p className="hint error codeview-message">{error}</p>;

  return (
    <Spin spinning={loading} classNames={{ root: "codeview-spin" }}>
      <div style={{ padding: "0.5rem 1rem", overflow: "auto", height: "100%" }}>
        <Typography.Text type="secondary">
          {data ? `${data.references.length} reference${data.references.length === 1 ? "" : "s"} to ` : "References to "}
          <code>{tab.title}</code>
        </Typography.Text>
        <List
          size="small"
          dataSource={data?.references ?? []}
          renderItem={(item) => (
            <List.Item
              style={{ cursor: "pointer" }}
              onClick={() => open(item)}
              actions={[<Tag key="k" color={KIND_COLOR[item.kind]}>{item.kind}</Tag>]}
            >
              <span>
                <strong>{item.ownerSimple}</strong>
                {item.member && (
                  <span className="hint">
                    .{item.member}
                    {item.descriptor}
                  </span>
                )}
              </span>
            </List.Item>
          )}
        />
      </div>
    </Spin>
  );
}

export function referencesTabLabel(tab: ReferencesTab): string {
  return `↗ ${tab.title.split("/").pop() ?? tab.title}`;
}
