import { useState } from "react";

/** navigator.clipboard needs a secure context (https, or localhost); a plain-http LAN address does not
 * qualify, so fall back to the old execCommand path, which has no such restriction. */
async function copyToClipboard(text: string): Promise<boolean> {
  if (navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // fall through to the legacy path below
    }
  }
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  textarea.select();
  const ok = document.execCommand("copy");
  document.body.removeChild(textarea);
  return ok;
}

/** A click-to-copy inline value. */
export function Copyable({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    if (await copyToClipboard(text)) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 900);
    }
  };

  return (
    <button type="button" className="copyable" title="Click to copy" onClick={copy}>
      <span className="copyable-text">{text}</span>
      {copied && <span className="copyable-flag">copied</span>}
    </button>
  );
}
