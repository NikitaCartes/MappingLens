# MappingLens + GitCraft in one image. Build context is the MappingLens project root:
#   docker build -f docker/Dockerfile -t mappinglens .

# MappingLens builds against a Java 21 toolchain, GitCraft requires Java 25, so the fat jar is
# built in its own stage.
FROM eclipse-temurin:21-jdk AS mappinglens-build
WORKDIR /src
COPY gradle ./gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
# Bundled into the jar as a classpath resource and served at /skill.md.
COPY .github/skills ./.github/skills
# The checkout carries CRLF line endings, which /bin/sh refuses to run.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon buildFatJar

FROM eclipse-temurin:25-jdk

# git: repository checkouts. jq/curl: the update checks in the entrypoint.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git jq curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ARG GITCRAFT_REPO=https://github.com/NikitaCartes-forks/GitCraft.git
ARG GITCRAFT_REF=my-changes-v2
RUN git clone --depth 1 --branch "${GITCRAFT_REF}" "${GITCRAFT_REPO}" /opt/gitcraft \
    && chown -R 1000:1000 /opt/gitcraft

# Mapping repositories. GitCraft reads intermediary from these checkouts instead of the GitHub
# API, and MappingLens indexes the same tiny files. The entrypoint updates all four each cycle and
# hashes their refs: a commit in any of them can unblock a version whose build failed. The yarn
# mappings themselves are not read from the checkouts, because GitCraft downloads yarn from
# maven.fabricmc.net and from the RelativityMC maven.
RUN git clone --depth 1 https://github.com/FabricMC/intermediary /opt/mappings/intermediary \
    && git clone --depth 1 https://github.com/RelativityMC/intermediary /opt/mappings/relativity-intermediary \
    && git clone --depth 1 https://github.com/FabricMC/yarn /opt/mappings/yarn \
    && git clone --depth 1 https://github.com/RelativityMC/yarn /opt/mappings/relativity-yarn \
    && chown -R 1000:1000 /opt/mappings

COPY --from=mappinglens-build /src/build/libs/mappinglens-all.jar /opt/mappinglens/mappinglens.jar
COPY docker/presets /opt/presets
COPY docker/entrypoint.sh /opt/entrypoint.sh
RUN sed -i 's/\r$//' /opt/entrypoint.sh && chmod +x /opt/entrypoint.sh

# MappingLens reads every path from the environment; the config file only adds the version
# filter for a scoped re-index.
ENV MAPPINGLENS_DB_PATH=/data/index/mappinglens.db \
    MAPPINGLENS_ARTIFACT_STORE=/data/artifact-store \
    MAPPINGLENS_YARN_REPO=/data/repos/yarn \
    MAPPINGLENS_MOJMAP_REPO=/data/repos/mojmap \
    MAPPINGLENS_INTERMEDIARY=/opt/mappings/intermediary/mappings \
    MAPPINGLENS_UNOBFUSCATED_INTERMEDIARY=/opt/mappings/relativity-intermediary/mappings \
    GRADLE_USER_HOME=/data/gradle \
    MAPPINGS="mojmap yarn" \
    ONLY_RELEASES=false \
    REFS=all \
    UPDATE_INTERVAL_SECONDS=3600 \
    PORT=8080 \
    GITCRAFT_JAVA_OPTS= \
    INDEX_JAVA_OPTS= \
    SERVE_JAVA_OPTS=

EXPOSE 8080
# GitCraft resolves config.json and its temporary files against the working directory.
WORKDIR /opt/gitcraft
ENTRYPOINT ["/opt/entrypoint.sh"]
