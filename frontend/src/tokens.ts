import type { SourceToken } from "./types";
import { simpleClassName } from "./util";

/** The token whose range contains the cursor, or null. Ranges are Monaco 1-based, endColumn exclusive. */
export function findTokenAtPosition(
  pos: { lineNumber: number; column: number },
  tokens: SourceToken[],
): SourceToken | null {
  for (const t of tokens) {
    const afterStart =
      pos.lineNumber > t.startLine || (pos.lineNumber === t.startLine && pos.column >= t.startColumn);
    const beforeEnd = pos.lineNumber < t.endLine || (pos.lineNumber === t.endLine && pos.column <= t.endColumn);
    if (afterStart && beforeEnd) return t;
  }
  return null;
}

/** A class or member to act on — from a resolved token, or a bare class (class-scope fallback). */
export interface Target {
  className: string;
  member?: { type: "method" | "field"; name: string; descriptor: string };
}

export function tokenTarget(t: SourceToken): Target {
  if (t.type === "class" || !t.name || !t.descriptor) return { className: t.className };
  return { className: t.className, member: { type: t.type, name: t.name, descriptor: t.descriptor } };
}

const dotted = (c: string) => c.replace(/\//g, ".");

/** Class Tweaker / Access Widener entry. */
export function awEntry(t: Target): string {
  return t.member
    ? `accessible ${t.member.type} ${t.className} ${t.member.name} ${t.member.descriptor}`
    : `accessible class ${t.className}`;
}

/** Forge Access Transformer entry. */
export function atEntry(t: Target): string {
  if (!t.member) return `public ${dotted(t.className)}`;
  return t.member.type === "field"
    ? `public ${dotted(t.className)} ${t.member.name}`
    : `public ${dotted(t.className)} ${t.member.name}${t.member.descriptor}`;
}

/** Mixin `@At` target string. */
export function mixinEntry(t: Target): string {
  if (!t.member) return t.className;
  const sep = t.member.type === "field" ? ":" : "";
  return `L${t.className};${t.member.name}${sep}${t.member.descriptor}`;
}

/** Reference-index query key + human title, matching the backend key format `owner[:name:desc]`. */
export function referenceQuery(t: Target): { query: string; title: string } {
  return t.member
    ? { query: `${t.className}:${t.member.name}:${t.member.descriptor}`, title: `${simpleClassName(t.className)}.${t.member.name}` }
    : { query: t.className, title: t.className };
}
