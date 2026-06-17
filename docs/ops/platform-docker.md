# Platform Docker Runbook

## Start

```bash
./scripts/platform-up.sh
```

The script packages `runtime-mock-platform-server`, starts PostgreSQL, Kafka/Redpanda, Redis, MinIO and Keycloak, builds the platform image, then starts the platform service.

The compose profile enables:

- PostgreSQL as the authoritative metadata/state store.
- Redpanda Kafka for outbox publishing.
- Redis for fencing-token sequence generation.
- Local filesystem object artifacts inside the platform container.
- In-process rollout, extraction, and replay schedulers.

## Verify

```bash
curl -fsS http://127.0.0.1:18280/api/v1/control/health
./scripts/platform-smoke.sh
```

Redis fencing check:

```bash
curl -fsS -X POST http://127.0.0.1:18280/api/v1/fencing-tokens \
  -H 'Content-Type: application/json' \
  -H 'X-Actor: system' \
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
| `RUNTIME_MOCK_KAFKA_ENABLED` | `false` | Enables outbox publishing |
| `RUNTIME_MOCK_OUTBOX_FIXED_DELAY_MS` | `5000` | Outbox publish interval |
| `RUNTIME_MOCK_REDIS_HOST` | `127.0.0.1` | Redis host for fencing |
| `RUNTIME_MOCK_REDIS_PORT` | `6379` | Redis port for fencing |
| `RUNTIME_MOCK_FENCING_REDIS_ENABLED` | `false` | Enables Redis-backed fencing sequences |
| `RUNTIME_MOCK_FENCING_KEY_PREFIX` | `runtime-mock:fencing:` | Redis fencing key prefix |
| `RUNTIME_MOCK_OBJECT_STORE_ROOT` | `./data/platform-objects` | Local artifact root for extraction/replay workers |
| `RUNTIME_MOCK_ROLLOUT_SCHEDULER_ENABLED` | `true` | Enables rollout executor |
| `RUNTIME_MOCK_EXTRACTION_WORKER_ENABLED` | `true` | Enables extraction worker |
| `RUNTIME_MOCK_REPLAY_WORKER_ENABLED` | `true` | Enables replay worker |

The platform writes authoritative state to PostgreSQL. Kafka is only used for at-least-once event delivery from the database outbox.
