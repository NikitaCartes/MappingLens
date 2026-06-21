import type { SearchResultEntry } from "../types";
import { NAMESPACES, namespaceValue, optimumNamespace, readableName } from "../util";
import { Copyable } from "./Copyable";

const TYPE_LABEL: Record<string, string> = {
  class: "Class",
  method: "Method",
  field: "Field",
};

export function ResultRow({ entry }: { entry: SearchResultEntry }) {
  const best = optimumNamespace(entry);
  const bestValue = best ? namespaceValue(entry, best) : null;
  const heading = bestValue ? readableName(bestValue) : "(unknown)";

  return (
    <div className="result">
      <div className="result-head">
        <span className={`badge badge-${entry.type}`}>{TYPE_LABEL[entry.type] ?? entry.type}</span>
        <span className="result-name">{heading}</span>
        {entry.descriptor && <span className="result-desc">{entry.descriptor}</span>}
      </div>
      <table className="ns-table">
        <tbody>
          {NAMESPACES.map(({ id, label }) => {
            const value = namespaceValue(entry, id);
            if (!value) return null;
            return (
              <tr key={id}>
                <td className="ns-label">{label}</td>
                <td className="ns-value">
                  <Copyable text={value} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
