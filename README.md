# MappingLens

MappingLens indexes Minecraft's Yarn, Mojmap, Intermediary, and Obfuscated mappings, then
serves them over a read-only REST API: search, name translation, cross-version diffs, source
and bytecode lookup, inheritance hierarchies, references, and a browser explorer. It also
serves the game's resources — assets, the integrated datapack, registries and translations —
and how each of them changed between versions.

Four parts make up the project:

- **`index`**: offline indexer. Builds a SQLite index from a GitCraft artifact store and the
  Yarn/Mojmap source repositories.
- **`index-resources`**: offline indexer for the resource explorer. Builds a second index from a
  clone of [`misode/mcmeta`](https://github.com/misode/mcmeta). Optional.
- **`serve`**: stateless HTTP server (Ktor). Opens the index read-only and answers the REST API.
- **`frontend/`**: browser explorer (React + Vite) for the API, with a tab for each of the two.

## Quick start (Docker)

```sh
docker compose -f docker/docker-compose.yml up -d --build
```

This builds the index, the server, and the frontend, and keeps the data current on a
schedule. The API listens on `:8080` (Swagger UI at `/docs`), the UI on `:3000`.

## Quick start (manual)

```sh
./gradlew run --args="index"   # build the index
./gradlew run --args="serve"   # serve it, :8080

cd frontend && npm install && npm run dev   # UI at :5173
```

The resource explorer needs a clone of mcmeta and an index of its own:

```sh
git clone --no-checkout --no-tags --single-branch -b assets https://github.com/misode/mcmeta data/mcmeta
git -C data/mcmeta remote set-branches --add origin diff registries atlas
git -C data/mcmeta fetch --no-tags origin
MAPPINGLENS_MCMETA_REPO=data/mcmeta ./gradlew run --args="index-resources"
```

## Documentation

- [SERVICE.md](SERVICE.md) ([RU](SERVICE.ru.md)): full API reference, configuration, and Docker details.
- [CHANGELOG.md](CHANGELOG.md): version history.
