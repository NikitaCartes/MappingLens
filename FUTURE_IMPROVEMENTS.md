# Future Improvements

Known trade-offs and deferred work. Nothing here is a bug — these are deliberate
simplifications with a recorded upgrade path.

## In-memory service caches are eternal (unbounded)

The on-demand caches in the services are plain `ConcurrentHashMap`s with **no TTL and
no eviction** — they live for the whole server process and only reset on restart:

- `TokenService.tokenCache` — per (version, class, namespace). Holds the **full decompiled
  source string** plus its resolved tokens. This is the heaviest per-entry cache.
- `TokenService.solverCache` — per (version, namespace). Holds a `JavaSymbolSolver` /
  `JarTypeSolver` (keeps a per-jar type index).
- `HierarchyService.cache` — per (version, namespace). All class headers of the version (~7k).
- `ReferenceService.cache` — per (version, namespace). The full reverse-reference index of the
  version (can be tens of MB per version).
- `BytecodeService.nameMapCache` — per (version, namespace). Pre-existing.

**Why it's fine:** the index is immutable for the server's lifetime, so cached results are
always valid and a warm cache turns expensive scans/parses into map lookups.

**The downside:** memory grows without bound as more distinct classes/versions are opened —
nothing is ever released. A long-running server that serves many classes across many versions
will accumulate large caches (dominated by `tokenCache` source strings and `ReferenceService`
indexes).

**Decision (2026-07-02):** left as-is on purpose. A size-bounded LRU per cache (evict least-
recently-used entries past a cap) is the right upgrade if/when memory becomes a concern — a TTL
would not help memory and servers rarely run long enough for a time bound to matter. Precision is
unaffected by eviction: a miss simply recomputes.

> Note: this server-side in-memory cache is separate from the HTTP `Cache-Control: max-age`
> (one month) set on successful `/api/v1` responses, which is a client/CDN cache.

## Token resolution is on-demand, not precomputed at index time

Source tokens (`/api/v1/tokens`) are resolved lazily at request time and cached, rather than
precomputed during `index`. On-demand gives identical precision without a full re-index or index
growth, and fits the stateless-server-reads-jars-lazily model. If serve latency ever matters, the
same `TokenService.tokensFor` logic can move offline into the indexer and persist a token map per
class — a pure relocation of where the cost is paid.
