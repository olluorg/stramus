# The stramus sync server.
#
# Two stages, and the `--platform=$BUILDPLATFORM` on the first one is the whole trick: Gradle runs on the
# machine doing the building, at its own speed, and what it produces — JVM bytecode — is the same for every
# architecture. Only the thin runtime layer is built per-platform. Without that pin, building an arm64 image
# on an amd64 runner would run the entire Kotlin compile under QEMU emulation, which takes the better part of
# an hour to produce identical bytes.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /src

# The Gradle wrapper and the build scripts first, on their own: they change rarely, so the layer that
# downloads Gradle and the dependency graph survives a change to the source, which is most changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY protocol/build.gradle.kts protocol/
COPY server/build.gradle.kts server/
RUN ./gradlew --no-daemon --version

COPY protocol protocol
COPY server server

# Only the server and the protocol it speaks. The browser modules (`core`, `ui-shared`, `webapp`,
# `extension`) are not built here and are not copied in: they are a Kotlin/JS build that would pull down a
# Node toolchain to produce something this image does not serve.
RUN ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:21-jre-jammy

# curl is here for the healthcheck below and nothing else; without it Docker cannot tell a server that is
# starting from one that has died, and an orchestrator cannot restart what it cannot see is broken.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Not root. A remote-code hole in a tab manager's sync server should get an attacker as far as the tab
# manager's data and no further.
RUN useradd --system --uid 10001 --home /app stramus

WORKDIR /app
COPY --from=build /src/server/build/install/server ./

# Everything that must outlive the container: the database and the file bytes. Both go under one directory
# so that a VPS has one thing to mount and one thing to back up.
ENV STRAMUS_DB=/data/stramus.db \
    STRAMUS_BLOBS=/data/blobs \
    PORT=8090
RUN mkdir -p /data && chown -R stramus:stramus /data /app
VOLUME ["/data"]

USER stramus
EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/health" || exit 1

ENTRYPOINT ["/app/bin/server"]
