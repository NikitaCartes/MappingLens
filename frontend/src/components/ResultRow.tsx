import type { ClassNames, SearchResultEntry } from "../types";
import { NAMESPACES, namespaceValue, optimumNamespace, readableName } from "../util";
import { useOpenClass } from "../openClass";
import { Copyable } from "./Copyable";

const TYPE_LABEL: Record<string, string> = {
  class: "Class",
  method: "Method",
  field: "Field",
};

/** The class to open for a result: the class itself, or the owner of a method/field. */
function ownerNames(entry: SearchResultEntry): ClassNames | null {
  const names: ClassNames =
    entry.type === "class"
      ? { yarn: entry.yarn, mojmap: entry.mojmap, intermediary: entry.intermediary, obfuscated: entry.obfuscated }
      : (entry.owner ?? {});
  return names.yarn || names.mojmap || names.intermediary || names.obfuscated ? names : null;
}

export function ResultRow({ entry }: { entry: SearchResultEntry }) {
  const openClass = useOpenClass();
  const best = optimumNamespace(entry);
  const bestValue = best ? namespaceValue(entry, best) : null;
  const heading = bestValue ? readableName(bestValue) : "(unknown)";
  const names = ownerNames(entry);

  return (
    <div className="result">
      <div className="result-head">
        <span className={`badge badge-${entry.type}`}>{TYPE_LABEL[entry.type] ?? entry.type}</span>
        {names ? (
          <button
            type="button"
            className="result-name result-open"
            title="Open class source"
            onClick={() => openClass({ names })}
          >
            {heading}
          </button>
        ) : (
          <span className="result-name">{heading}</span>
        )}
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
