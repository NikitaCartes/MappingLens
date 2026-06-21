import type { Namespace, SearchResultEntry } from "./types";

export interface NamespaceMeta {
  id: Namespace;
  label: string;
}

/** Display order: human-readable mappings first, raw obfuscation last. */
export const NAMESPACES: NamespaceMeta[] = [
  { id: "mojmap", label: "Mojmap" },
  { id: "yarn", label: "Yarn" },
  { id: "intermediary", label: "Intermediary" },
  { id: "obfuscated", label: "Obfuscated" },
];

export function namespaceValue(entry: SearchResultEntry, ns: Namespace): string | null {
  switch (ns) {
    case "mojmap":
      return entry.mojmap ?? null;
    case "yarn":
      return entry.yarn ?? null;
    case "intermediary":
      return entry.intermediary ?? null;
    case "obfuscated":
      return entry.obfuscated ?? null;
  }
}

/** The best available namespace for a heading: prefer mapped names over raw obfuscation. */
export function optimumNamespace(entry: SearchResultEntry): Namespace | null {
  for (const { id } of NAMESPACES) {
    if (namespaceValue(entry, id)) return id;
  }
  return null;
}

/**
 * Turns a raw index value into a readable heading.
 *  - class values look like `net/minecraft/world/BossEvent`
 *  - member values look like `net/minecraft/world/BossEvent#setProgress`
 */
export function readableName(value: string): string {
  const hash = value.indexOf("#");
  const owner = hash >= 0 ? value.slice(0, hash) : value;
  const member = hash >= 0 ? value.slice(hash + 1) : null;
  const simpleOwner = owner.slice(owner.lastIndexOf("/") + 1);
  return member ? `${simpleOwner}.${member}` : simpleOwner;
}
