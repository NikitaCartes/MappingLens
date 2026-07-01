import { createContext, useContext } from "react";
import type { ClassNames, SourceNamespace } from "./types";

export interface OpenClassRequest {
  names: ClassNames;
  version?: string; // defaults to the selected version
  namespace?: SourceNamespace; // defaults to the current source namespace
}

export interface OpenHierarchyRequest {
  className: string;
  version: string;
  namespace: SourceNamespace;
}

export interface OpenReferencesRequest {
  query: string; // class or class:name:descriptor
  title: string; // human-readable target
  version: string;
  namespace: SourceNamespace;
}

export interface TabActions {
  openClass: (req: OpenClassRequest) => void;
  openHierarchy: (req: OpenHierarchyRequest) => void;
  openReferences: (req: OpenReferencesRequest) => void;
}

const TabActionsContext = createContext<TabActions>({
  openClass: () => {},
  openHierarchy: () => {},
  openReferences: () => {},
});

export const OpenClassProvider = TabActionsContext.Provider;
export const useOpenClass = (): TabActions["openClass"] => useContext(TabActionsContext).openClass;
export const useOpenHierarchy = (): TabActions["openHierarchy"] => useContext(TabActionsContext).openHierarchy;
export const useOpenReferences = (): TabActions["openReferences"] => useContext(TabActionsContext).openReferences;
