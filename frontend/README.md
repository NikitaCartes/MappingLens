# MappingLens frontend

A linkie-style Minecraft mappings explorer for the MappingLens API. Search a class,
method, or field for any indexed version and see its name in every namespace
(Mojmap, Yarn, Intermediary, Obfuscated) side by side — each result row *is* the
cross-namespace comparison.

The frontend is backed entirely by the MappingLens REST API; it does no decompilation
of its own. (The in-browser decompiler/code-viewer is a separate project, `mcsrc`.)

## Develop

In dev, Vite proxies `/api` → `http://localhost:8080` (see `vite.config.ts`).

```sh
# 1. build the read-only index and start the API (from the repo root)
./gradlew run --args="index"
./gradlew run --args="serve"      # listens on :8080

# 2. start the frontend
cd frontend
npm install
npm run dev                        # http://localhost:5173
```

## Build

```sh
npm run build       # type-checks (tsc), then emits a static bundle to dist/
```

Deploy `dist/` as static files and route `/api` to a running `mappinglens serve`
instance (reverse proxy, or have Ktor serve the static bundle).
