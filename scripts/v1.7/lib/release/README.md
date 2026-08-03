# Kairo V1.7 release Compose archive (roadmap §12.2)

This archive deploys the official V1.7 release topology: PostgreSQL 16, Redis 7, the Platform
server, and the Web UI. It carries no development/default token or secret.

## Prerequisites

- Docker Engine and Docker Compose v2+.

## Deploy

1. Copy `kairo.env.template` to `.env` and replace every `__SET_...__` value with a real secret.
2. Validate the resolved configuration (no secrets are printed in full):
   ```
   docker compose -f docker-compose.yml --env-file .env config >/dev/null
   ```
3. Start:
   ```
   docker compose -f docker-compose.yml --env-file .env up -d
   ```
4. Wait for health:
   ```
   docker compose -f docker-compose.yml ps
   ```

## Ports

- Platform API/scheduler: `18280`
- Web UI: `18380` -> container `3000`

## Contents

- `docker-compose.yml` - sanitized release topology (version substituted at build time).
- `kairo.env.template` - required environment variables (no shipped secret).
- `UPGRADE.md` - concise V1.6.0 -> V1.7.0 upgrade notes.

For reliability semantics, recovery, and rollback see `docs/ops/v1.7-lts-runbook.md` and
`docs/roadmap/v1.x-technical/v1.7-lts-stabilization.md`.
