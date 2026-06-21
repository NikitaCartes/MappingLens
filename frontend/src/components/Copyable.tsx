import { useState } from "react";

/** A click-to-copy inline value. Falls back silently when the clipboard API is unavailable. */
export function Copyable({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 900);
    } catch {
      // clipboard unavailable (e.g. insecure context) — ignore
    }
  };

  return (
    <button type="button" className="copyable" title="Click to copy" onClick={copy}>
      <span className="copyable-text">{text}</span>
      {copied && <span className="copyable-flag">copied</span>}
    </button>
  );
}
