import { createContext, useContext } from "react";
import type { ClassNames, SourceNamespace } from "./types";

export interface OpenClassRequest {
  names: ClassNames;
  version?: string; // defaults to the selected version
  namespace?: SourceNamespace; // defaults to the current source namespace
}

export type OpenClassFn = (req: OpenClassRequest) => void;

const OpenClassContext = createContext<OpenClassFn>(() => {});

export const OpenClassProvider = OpenClassContext.Provider;
export const useOpenClass = (): OpenClassFn => useContext(OpenClassContext);
