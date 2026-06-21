import type { SearchResultEntry } from "../types";
import { ResultRow } from "./ResultRow";

interface Props {
  loading: boolean;
  error?: string;
  results?: SearchResultEntry[];
  query: string;
  hasVersion: boolean;
}

export function Results({ loading, error, results, query, hasVersion }: Props) {
  if (!hasVersion) return <p className="hint">Loading versions…</p>;
  if (!query.trim()) return <p className="hint">Type a class, method, or field name to search.</p>;
  if (error) return <p className="hint error">{error}</p>;
  if (loading && !results) return <p className="hint">Searching…</p>;
  if (results && results.length === 0) return <p className="hint">No results for “{query}”.</p>;
  if (!results) return null;

  return (
    <div className="results">
      {results.map((entry, i) => (
        <ResultRow key={`${entry.type}:${entry.intermediary ?? entry.obfuscated ?? ""}:${i}`} entry={entry} />
      ))}
    </div>
  );
}
