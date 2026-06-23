import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import * as monaco from "monaco-editor";
import { loader } from "@monaco-editor/react";
import MonacoWorker from "monaco-editor/esm/vs/editor/editor.worker.js?worker";
import App from "./App";
import { registerLanguages } from "./monaco/languages";
import "./index.css";

// Bundle Monaco locally instead of loading it from a CDN.
loader.config({ monaco });
globalThis.MonacoEnvironment = {
  getWorker() {
    return new MonacoWorker();
  },
};
registerLanguages();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
