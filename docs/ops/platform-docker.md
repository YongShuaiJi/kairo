# Platform Docker Runbook

## Start

```bash
./scripts/platform-up.sh
```

The script packages `runtime-mock-platform-server`, starts PostgreSQL and Redis, then starts the
Platform API, the independent Next.js Platform Web process, and the demo application.

The compose profile enables:

- PostgreSQL as the authoritative metadata/state store.
- Redis for fencing-token sequence generation.
- In-process rollout scheduling for rule publish and unload execution.
- Database-backed opaque Bearer Tokens instead of Keycloak or trusted identity headers.
- Next.js Platform Web at `http://127.0.0.1:18380`.
- Demo application plus attach executor when running `./scripts/platform-up.sh`.

## Verify

```bash
curl -fsS http://127.0.0.1:18280/api/v1/control/health
./scripts/platform-smoke.sh
```

冒烟脚本只依赖 Python 3。它依次验证健康检查、实例与 Agent 注册、规则创建、发布计划、
调度器、Agent 命令下发和确认。

Open the central console:

```text
http://127.0.0.1:18380/
```

The browser talks only to the Next.js same-origin BFF. The BFF stores the submitted Platform Token
in an encrypted HttpOnly session cookie and forwards authenticated requests to `platform:18280`.

The Compose development administrator token is:

```text
runtime-mock-dev-admin-token-change-me
```

It is intentionally a local development value and must be replaced outside Compose.

Redis fencing check:

```bash
curl -fsS -X POST http://127.0.0.1:18280/api/v1/fencing-tokens \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer runtime-mock-dev-admin-token-change-me' \
  -d '{"resourceType":"rule","resourceId":"docker-check-rule","purpose":"check","ttlSeconds":300,"reason":"check"}'

docker exec runtime-mock-redis redis-cli keys 'runtime-mock:fencing:*'
```

## Stop

```bash
./scripts/platform-down.sh
```

## Runtime Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `RUNTIME_MOCK_DB_URL` | `jdbc:postgresql://127.0.0.1:5432/runtime_mock` | PostgreSQL JDBC URL |
| `RUNTIME_MOCK_DB_USER` | `runtime_mock` | PostgreSQL user |
| `RUNTIME_MOCK_DB_PASSWORD` | `runtime_mock` | PostgreSQL password |
| `RUNTIME_MOCK_API_ENABLED` | `true` | Enables business API controllers |
| `RUNTIME_MOCK_REDIS_HOST` | `127.0.0.1` | Redis host for fencing |
| `RUNTIME_MOCK_REDIS_PORT` | `6379` | Redis port for fencing |
| `RUNTIME_MOCK_FENCING_REDIS_ENABLED` | `false` | Enables Redis-backed fencing sequences |
| `RUNTIME_MOCK_FENCING_KEY_PREFIX` | `runtime-mock:fencing:` | Redis fencing key prefix |
| `RUNTIME_MOCK_AUTH_MODE` | `local-token` | `local-token` or loopback-only `header-dev` |
| `RUNTIME_MOCK_BOOTSTRAP_TOKEN` | empty | Initial administrator token |
| `RUNTIME_MOCK_ROLLOUT_SCHEDULER_ENABLED` | `true` | Enables rollout executor |
| `RUNTIME_MOCK_PLATFORM_API_URL` | `http://127.0.0.1:18280` | Platform API used by Platform Web BFF |
| `RUNTIME_MOCK_WEB_SESSION_KEY` | none | At least 32 characters; encrypts the Web session cookie |
| `RUNTIME_MOCK_WEB_DEMO_MODE` | `false` | Enables explicit isolated frontend demo data |

The API writes authoritative state to PostgreSQL. Redis is only used for fencing-token sequence
coordination; it is not the source of truth.
