# syntax=docker/dockerfile:1

# Base images pinned by digest (Dependabot proposes updates).
FROM clojure:temurin-27-tools-deps@sha256:2ee1c2fa687fe84cfde31beaf39f3a47f7502aa675b2cc98a5ab8a091f227f0d AS build
WORKDIR /src
COPY deps.edn build.clj ./
RUN clojure -P && clojure -T:build clean
COPY src src
COPY resources resources
RUN clojure -T:build uber

# Litestream release binary, verified against its published SHA-256.
FROM alpine:3@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a AS litestream
ARG TARGETARCH
ARG LITESTREAM_VERSION=0.5.17
RUN set -eu; \
    # TARGETARCH is set by BuildKit; the classic builder builds for the host.
    case "${TARGETARCH:-$(uname -m)}" in \
      amd64|x86_64) arch=x86_64; sha=cfb371176d164437ae869f8351cfde49bd1804ae71c61923f75c9cba9c9c006d ;; \
      arm64|aarch64) arch=arm64; sha=f8ca4a050095c1efbda2c4365172e61bf9d955ea0d9ac42f448b52e51819baa5 ;; \
      *) echo "unsupported architecture: ${TARGETARCH:-$(uname -m)}" >&2; exit 1 ;; \
    esac; \
    file="litestream-${LITESTREAM_VERSION}-linux-${arch}.tar.gz"; \
    wget -qO "/tmp/$file" "https://github.com/benbjohnson/litestream/releases/download/v${LITESTREAM_VERSION}/$file"; \
    echo "$sha  /tmp/$file" | sha256sum -c -; \
    tar -C /usr/local/bin -xzf "/tmp/$file" litestream

# glibc base: sqlite-jdbc's native library is built against it.
FROM eclipse-temurin:25-jre-noble@sha256:ce8543ddd3070ccbeaac2106a74c9387ddeb8d47a1c43348345715c1ef245c7f
# libvips' vips command processes uploaded photos; libheif's HEVC decoder,
# a plugin of its own, reads HEIC, as phones save photos (AVIF it reads as is).
RUN apt-get update \
    && apt-get install -y --no-install-recommends libvips-tools libheif-plugin-libde265 \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system --gid 10001 jumble \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app jumble \
    && mkdir -p /app /data \
    && chown 10001:10001 /data
COPY --from=litestream /usr/local/bin/litestream /usr/local/bin/litestream
COPY --from=build /src/target/jumble.jar /app/jumble.jar
COPY litestream.yml /etc/litestream.yml
COPY docker/entrypoint.sh /app/entrypoint.sh
# The license travels with the program.
COPY LICENSE NOTICE THIRD-PARTY-NOTICES.md /app/

ENV JUMBLE_HOST=0.0.0.0 \
    JUMBLE_PORT=8080 \
    JUMBLE_DB_PATH=/data/jumble.db \
    JUMBLE_BACKUP_PATH=jumble \
    JUMBLE_LITESTREAM_METRICS_URL=http://127.0.0.1:9090/metrics

# Numeric, so platforms requiring a non-root user can check it.
USER 10001:10001
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
