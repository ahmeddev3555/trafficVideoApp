# TrafficWatch Server

A Spring Boot (Kotlin) backend for the TrafficWatch Android app: user
registration/login, multipart video-report submission, and an async stub
"analysis" job that resolves each report to `CONFIRMED`/`REJECTED`.

## Prerequisites

- **JDK 17** — the Gradle wrapper (`./gradlew`) will use whatever JDK your
  `JAVA_HOME` points at, or auto-provision one via Gradle's toolchain support.
- **Docker** (with Docker Compose) — only needed to run the app for real
  (`bootRun`) against Postgres. **Not** needed to build or run the test suite
  (see [Running tests](#running-tests) below, which uses an in-memory H2
  database instead).

## Setup (local run against Postgres)

All commands below assume you're inside `server/` (this directory).

1. **Start Postgres:**

   ```
   docker compose up -d
   ```

   This brings up a `postgres:16-alpine` container (service name `postgres`,
   container name `trafficwatch-postgres`) on `localhost:5432`, database
   `trafficwatch`, user/password `trafficwatch` / `trafficwatch_dev_password`,
   with a named volume (`trafficwatch-db`) for persistence and a healthcheck.

2. **Create your local config:**

   ```
   cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
   ```

   `application-local.yml` is gitignored (never committed) so you can safely
   put real secrets in it. At minimum, replace the placeholder
   `app.jwt.secret` with a real random value, e.g.:

   ```
   openssl rand -base64 32
   ```

   The example file's datasource block already matches the `docker-compose.yml`
   credentials above, so if you used `docker compose up -d` unmodified you
   shouldn't need to touch the datasource section.

3. **Run the app:**

   ```
   SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
   ```

   (On Windows PowerShell: `$env:SPRING_PROFILES_ACTIVE="local"; ./gradlew bootRun`.)

   On startup, Flyway runs its migrations against the Postgres database
   automatically (`spring.flyway.enabled: true` in `application.yml`) — no
   separate migration step needed. The app then listens on `:8080` with all
   endpoints under the `/v1` context path (e.g. `POST /v1/auth/register`).

   > **Verification note:** this setup path (steps 1–3, and the manual `curl`
   > smoke test of register → login → submit → poll status → list, described
   > in the project's task-14 brief) has **not** been executed end-to-end in
   > every environment this project was built in, because Docker is not
   > always available there. It has been verified by static review of the
   > compose file, config, and migrations, and by the automated test suite
   > below (which covers the same code paths against H2). Treat a fresh
   > checkout's first `docker compose up -d && ./gradlew bootRun` as worth a
   > manual sanity check before you rely on it.

### Video storage

Uploaded videos are written to `storage/videos/` (relative to this module's
working directory, i.e. `server/storage/videos/`), configured via
`app.storage.video-directory`. That directory is gitignored except for a
`.gitkeep` placeholder, so the folder exists in a fresh checkout but its
contents are never committed.

## Running tests

```
./gradlew test
```

This does **not** require Docker or Postgres — the test suite runs entirely
against an in-memory H2 database (see "Open questions" below for the
tradeoff this implies). It covers unit tests, `@DataJpaTest` repository
tests, `@WebMvcTest` controller tests, and a full in-process end-to-end HTTP
test (register → login → submit a report → poll status → list reports).

As of this writing, the full suite is **75/75 tests passing**, verified in
this environment via:

```
./gradlew clean test --rerun-tasks
```

Other standalone commands that work without Docker (also verified in this
environment):

```
./gradlew --version       # confirms the wrapper itself runs
./gradlew compileKotlin   # compiles main sources
```

## Architecture overview

- **Auth** (`auth/`) — `POST /v1/auth/register` and `POST /v1/auth/login`.
  Passwords are BCrypt-hashed; successful auth returns a JWT bearer token
  (`app.jwt.secret` / `app.jwt.expiration-days`, 30 days by default) plus a
  `UserDto` (`id`, `name`, `email` — phone/CNIC/password are never returned).
  All other endpoints require `Authorization: Bearer <token>`; a missing or
  invalid token yields `401` (see `SecurityConfig`, `JwtAuthFilter`).

- **Reports** (`reports/`) — `POST /v1/reports` accepts
  `multipart/form-data` (a `video` file part plus scalar parts: `latitude`,
  `longitude`, `accuracy`, `altitude`, `bearing`, `speed`, `recorded_at`,
  `duration_ms`, `device_id`). It stores the video to disk, persists a
  `Report` row as `PENDING`, and hands off to the async analysis job.
  `GET /v1/reports/{reportId}/status` and `GET /v1/reports` (paginated,
  1-indexed `page`/`page_size`, optional `status` filter) read reports back
  — **scoped to the authenticated user** (a report belonging to another user
  404s rather than leaking its existence).

- **Stub analysis job** (`ReportAnalysisJob`) — after a report is submitted,
  a background task (`@Async` on a dedicated executor, never the request
  thread) waits `app.analysis.delay-ms` (10 seconds by default) and then
  flips the report to `CONFIRMED` (~80% of the time, with a placeholder
  license plate/confidence) or `REJECTED` (~20%). This simulates "real
  computer-vision analysis happening" without doing any — see Open
  Questions below. A server restart mid-delay silently drops the job,
  leaving that report `PENDING` forever; accepted as a stub limitation.

- **Storage** (`storage/`) — `LocalDiskVideoStorageService` writes uploaded
  video bytes under `app.storage.video-directory`
  (`server/storage/videos/`), naming files so the DB's `reports.video_path`
  can be matched back to the file on disk.

- **Database** — Postgres in production/local dev, Flyway-migrated; H2 in
  tests. Schema lives under `src/main/resources/db/migration/`.

## Open questions / assumptions

These were flagged during the build as deliberate, non-blocking judgment
calls a future maintainer should know about rather than be surprised by:

- **Reports are scoped per-user.** The Android client contract doesn't say
  this explicitly, but it's the only sane behavior for a real multi-user
  backend — a user only ever sees/queries their own reports.
- **Several constants are arbitrary placeholders**, chosen for
  "plausible and easy to change," not tuned to any real requirement:
  - `500MB` multipart upload limit (`server.servlet.multipart.max-file-size`
    / `max-request-size` in `application.yml`)
  - `10s` stub-analysis delay (`app.analysis.delay-ms`)
  - `80% CONFIRMED / 20% REJECTED` split in `ReportAnalysisJob`
- **Tests run against H2, not Testcontainers-Postgres.** This trades some
  dialect fidelity (H2 isn't Postgres) for a suite that needs no Docker at
  all — useful in CI/sandboxed environments where Docker isn't available.
  Revisit with Testcontainers if a Postgres-specific behavior ever needs
  covering that H2 can't faithfully emulate.
- **No admin/moderation endpoints.** Out of scope — the API surface matches
  only what the existing Android client contract requires.
