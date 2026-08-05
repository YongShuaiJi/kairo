# Kairo V1.6.0 -> V1.7.0 upgrade notes (roadmap §12.2 compose archive)

These notes cover an in-place upgrade of a V1.6.0 deployment to V1.7.0. They are concise and
operator-facing; full reliability semantics are in `docs/roadmap/v1.x-technical/v1.7-lts-stabilization.md`.

## 1. Release inventory (roadmap §12.2)

- `kairo-platform-server:1.7.0` and `kairo-platform-web:1.7.0` are the two central application images.
- `kairo-agent-bundle-1.7.0.tar.gz` packages the agent bootstrap API, agent bootstrap, agent core
  modern, attach CLI, ops CLI, LICENSE, and bounded launch examples.
- `kairo-cli`, `kairo-mcp`, `kairo-sdk` are standalone jars.
- `kairo-demo` and the former `kairo-sidecar` module are **not** release artifacts (M1-G converged the
  reactor from 18 to 16 modules; the demo attach executor now builds from `kairo-attach-cli`).

## 2. Database migration (PostgreSQL 16)

- On startup the Platform runs Flyway forward-only migrations automatically.
- V1.6.0 ceiling is `V41`. V1.7.0 adds:
  - `V42__agent_runtime_state_snapshot` — bounded agent runtime state (M1-C).
  - `V43__rollback_target_snapshot` — rollback target snapshot (M1).
- **Back up PostgreSQL before upgrading.** Migrations are not reversible; rollback is by DB restore
  (see §5).

## 3. Required environment (no shipped secret)

The release Compose uses `${VAR:?...}` for every secret, so deployment fails fast if a value is
missing. There is **no development/default token** in the release archive. Supply real secrets via
`kairo.env.template` -> `.env`:

| Variable | Purpose |
|---|---|
| `KAIRO_DB_PASSWORD` | PostgreSQL password |
| `KAIRO_BOOTSTRAP_TOKEN` | Platform bootstrap admin token (local-token auth) |
| `KAIRO_ATTACH_PLATFORM_TOKEN` | token used by attach CLI / agents to register |
| `KAIRO_WEB_SESSION_KEY` | web session cookie signing key |

## 4. Compose topology

Four services: `postgres`, `redis`, `platform`, `platform-web`. The development `demo-app` service
is excluded from the release Compose. Ports: Platform `18280`, Web `18380`.

Health endpoints: Platform `/actuator/health` (liveness + readiness including db/flyway/redis),
Web `/api/health`.

## 5. Rollback

1. Stop the Platform (`docker compose down platform`).
2. Restore PostgreSQL from the pre-upgrade backup.
3. Run the prior V1.6.0 Platform image against the restored DB.

Do not start the V1.6.0 application against a database whose Flyway migration head is newer than
V1.6.0's migration head (V41). The M6-A rehearsal enforces this as a fail-closed deployment preflight;
restore the pre-upgrade backup first, then start V1.6.0 only after the database reports V41.
4. On reconnect, the Platform reconciles desired vs actual agent rule state (M1-D); agents that
   were offline are not falsely reported as unloaded (M1-E compensation runs first).

## 6. Build identity

`/actuator/info`, `kairo-cli --version`, `kairo-mcp --version`, and `kairo-ops --version` all report
the unified V1.7 build version. See `docs/compatibility/v1.7.md` for the agent/JVM compatibility
matrix that applies to this release.
