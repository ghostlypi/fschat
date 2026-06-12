# fschat server image. Multi-stage: build the distribution with a JDK, run it on a JRE.
#
#   docker build -t fschat-server .
#   docker run -p 8443:8443 -p 8444:8444 -e FSCHAT_JWT_SECRET=... -v fschat-data:/data fschat-server
#
# (Works the same with `podman build` / `podman run`.)

# ---- build stage ----------------------------------------------------------
FROM docker.io/library/eclipse-temurin:21-jdk AS build
WORKDIR /src
# Build context is trimmed by .dockerignore (no build/, .gradle/, etc.).
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon :fschat-server:installDist

# ---- runtime stage --------------------------------------------------------
FROM docker.io/library/eclipse-temurin:21-jre AS runtime
# Non-root user; the SQLite DB lives on a volume at /data.
RUN useradd --uid 10001 --create-home --home-dir /home/fschat fschat \
    && mkdir -p /data && chown fschat:fschat /data
COPY --from=build /src/fschat-server/build/install/fschat-server /opt/fschat-server
USER fschat
WORKDIR /opt/fschat-server

# Set FSCHAT_JWT_SECRET to a stable 32+ byte value (a built-in dev secret is used
# otherwise, and the server warns). For TLS, mount a PKCS12 keystore and append
# `--keystore /certs/dev-keystore.p12 --keystore-pass <pw>` to the command.
EXPOSE 8443 8444
VOLUME ["/data"]

ENTRYPOINT ["bin/fschat-server"]
CMD ["--host", "0.0.0.0", "--db", "/data/fschat.db", "--https-port", "8443", "--ws-port", "8444"]
