# ═══════════════════════════════════════════════════════════════════════════
#  Bofma Ventures — one image containing the whole system.
#
#  The till, the back office and the API ship as a single jar: the web build is
#  copied into the server's static resources, so the shop runs one process on
#  one port with no reverse proxy, no second web host and no CORS. That is not
#  a packaging preference — §3 says there is no developer on site, and every
#  extra moving part is something that can be found broken on a Monday morning
#  by somebody whose job is selling cement.
#
#  PostgreSQL is deliberately NOT in here. It is a separate service in
#  docker-compose.prod.yml with its own volume, because the shop's records must
#  outlive any rebuild of this image.
#
#  Build:  docker compose -f docker-compose.prod.yml build
#  Start:  start-counterweight.bat  (or: docker compose -f docker-compose.prod.yml up -d)
# ═══════════════════════════════════════════════════════════════════════════

# ── 1. The till ────────────────────────────────────────────────────────────
FROM node:20-alpine AS web

WORKDIR /web
# package.json and the lockfile first, so a change to a component does not
# re-download every dependency.
COPY counterweight-web/package.json counterweight-web/package-lock.json ./
RUN npm ci

COPY counterweight-web/ ./
RUN npm run build


# ── 2. The server, with the till baked in ──────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS server

WORKDIR /build
COPY counterweight-server/pom.xml ./pom.xml
RUN mvn -B -DskipTests dependency:go-offline

COPY counterweight-server/src ./src
# This is what makes it one process. Spring Boot serves `static/` from the
# classpath, and SecurityConfig permits exactly these paths unauthenticated —
# the bundle is the sign-in screen, so refusing it until you are signed in
# would be a closed door with the handle behind it.
COPY --from=web /web/dist ./src/main/resources/static

# Tests are skipped here on purpose, and it is not a shortcut. They are
# Testcontainers against a real PostgreSQL 16, so they need a Docker daemon
# this build does not have. Run them on a workstation: ./mvnw test
RUN mvn -B -DskipTests clean package


# ── 3. What actually runs ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# postgresql16-client, matching the server version, and not optional: §14 makes
# the nightly dump and the weekly restore drill part of the product, and
# BackupService shells out to pg_dump, pg_restore and psql. A client of the
# wrong major version refuses the dump format, which would leave the shop
# believing it had backups it could not restore. Client only — no server.
RUN apk add --no-cache postgresql16-client tzdata

ENV TZ=Africa/Accra
WORKDIR /app

COPY --from=server /build/target/counterweight-server-*.jar /app/counterweight.jar

# Not root. The one thing this process writes outside the database is the
# backup directory, which is a mounted volume.
RUN addgroup -S shop && adduser -S -G shop shop \
    && mkdir -p /app/backups && chown -R shop:shop /app
USER shop

EXPOSE 8080

# `/actuator/health` is unauthenticated by design (SecurityConfig): "is it up"
# should not need a credential. wget is busybox's, already present.
# Checked often and early on purpose: `start-counterweight.bat` waits on this
# status rather than making its own HTTP call, so a 90-second start period
# would leave somebody watching a console long after the shop was serving.
# The app is up in about ten seconds.
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

# MaxRAMPercentage rather than a fixed -Xmx: the shop PC is whatever the shop
# PC is, and a hardcoded heap is wrong on both a 4GB machine and a 16GB one.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/counterweight.jar"]
