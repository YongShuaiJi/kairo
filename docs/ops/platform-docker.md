# Platform Docker Runbook

## Start

```bash
./scripts/platform-up.sh
```

The script packages `runtime-mock-platform-server`, starts PostgreSQL, Kafka/Redpanda, Redis and MinIO,
then starts separate API and Worker processes from the same platform image.
Compose also builds and starts the independent Next.js Platform Web process.

The compose profile enables:

- PostgreSQL as the authoritative metadata/state store.
- Redpanda Kafka for outbox publishing.
- Redis for fencing-token sequence generation.
- MinIO-backed encrypted Worker artifacts.
- Authenticated recording-batch ingestion with redaction, indexing, and encrypted MinIO objects.
- A dedicated Worker process for rollout, extraction, replay, and outbox publishing.
- Database-backed opaque Bearer Tokens instead of Keycloak or trusted identity headers.
- Next.js Platform Web at `http://127.0.0.1:18380`.

## Verify

```bash
curl -fsS http://127.0.0.1:18280/api/v1/control/health
./scripts/platform-smoke.sh
```

冒烟脚本只依赖 Python 3、curl 和 Docker，不依赖 jq。它依次验证认证与脚本工作台、
Agent 命令、发布、录制上传与数据集、Extraction、Replay、Web 查询契约、Kafka/Redis/
MinIO/PostgreSQL，以及 MinIO 原始对象不包含明文敏感数据。

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

Outbox/Kafka check:

```bash
curl -fsS http://127.0.0.1:18280/api/v1/outbox
```

Events should move to `PUBLISHED` when `RUNTIME_MOCK_KAFKA_ENABLED=true`.

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
| `RUNTIME_MOCK_KAFKA_BOOTSTRAP_SERVERS` | `127.0.0.1:9092` | Kafka bootstrap servers |
| `RUNTIME_MOCK_API_ENABLED` | `true` | Enables business API controllers |
| `RUNTIME_MOCK_WORKER_ENABLED` | `false` | Enables Worker-only infrastructure |
| `RUNTIME_MOCK_KAFKA_ENABLED` | `false` | Enables Worker outbox publishing |
| `RUNTIME_MOCK_OUTBOX_FIXED_DELAY_MS` | `5000` | Outbox publish interval |
| `RUNTIME_MOCK_REDIS_HOST` | `127.0.0.1` | Redis host for fencing |
| `RUNTIME_MOCK_REDIS_PORT` | `6379` | Redis port for fencing |
| `RUNTIME_MOCK_FENCING_REDIS_ENABLED` | `false` | Enables Redis-backed fencing sequences |
| `RUNTIME_MOCK_FENCING_KEY_PREFIX` | `runtime-mock:fencing:` | Redis fencing key prefix |
| `RUNTIME_MOCK_OBJECT_STORAGE_ENDPOINT` | `http://127.0.0.1:9000` | MinIO endpoint |
| `RUNTIME_MOCK_OBJECT_STORAGE_ACCESS_KEY` | `runtime_mock` | MinIO access key |
| `RUNTIME_MOCK_OBJECT_STORAGE_SECRET_KEY` | empty | MinIO secret key |
| `RUNTIME_MOCK_OBJECT_STORAGE_BUCKET` | `runtime-mock` | Object bucket |
| `RUNTIME_MOCK_MASTER_KEY_BASE64` | empty | Base64 256-bit local KEK |
| `RUNTIME_MOCK_MASTER_KEY_VERSION` | `local-v1` | KEK version |
| `RUNTIME_MOCK_AUTH_MODE` | `local-token` | `local-token` or loopback-only `header-dev` |
| `RUNTIME_MOCK_BOOTSTRAP_TOKEN` | empty | Initial administrator token |
| `RUNTIME_MOCK_ROLLOUT_SCHEDULER_ENABLED` | `true` | Enables rollout executor |
| `RUNTIME_MOCK_EXTRACTION_WORKER_ENABLED` | `true` | Enables extraction worker |
| `RUNTIME_MOCK_REPLAY_WORKER_ENABLED` | `true` | Enables replay worker |
| `RUNTIME_MOCK_RECORDING_INGESTION_ENABLED` | `false` | Enables authenticated recording-batch ingestion and encrypted object storage |
| `RUNTIME_MOCK_PLATFORM_API_URL` | `http://127.0.0.1:18280` | Platform API used by Platform Web BFF |
| `RUNTIME_MOCK_WEB_SESSION_KEY` | none | At least 32 characters; encrypts the Web session cookie |
| `RUNTIME_MOCK_WEB_DEMO_MODE` | `false` | Enables explicit isolated frontend demo data |

The API writes authoritative state to PostgreSQL. Kafka is only used for at-least-once event delivery
from the database outbox. Worker artifacts and recording batches are encrypted before they are sent
to MinIO; PostgreSQL stores indexes and encryption metadata, not large plaintext payloads.
