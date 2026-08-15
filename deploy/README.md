# Kairo RC deployment

This directory is the server-side deployment surface used by
`.github/workflows/deploy-rc.yml`. It follows the same immutable-digest and restricted-SSH model as
the XMind converter deployment on the shared ECS host.

## Server layout

- `/opt/kairo/compose.yml` — root-owned production Compose file.
- `/opt/kairo/.env` — root-only secrets and the currently approved image digests.
- `/usr/local/sbin/deploy-kairo` — validates, pulls, backs up, deploys and health-checks a release.
- `/usr/local/sbin/kairo-deploy-entrypoint` — forced-command SSH parser.
- `/var/lib/kairo-deploy` — deployment state and bounded PostgreSQL backups.

Application images must use immutable GHCR digests and carry an
`org.opencontainers.image.revision` label matching the requested candidate SHA. PostgreSQL and
Redis have persistent named volumes. Failed upgrades restore the previous image configuration;
because Flyway migrations are forward-only, a retained PostgreSQL dump must be restored explicitly
when an old image cannot run against the migrated schema.

## Private RC access

The Platform and Web ports bind only to ECS loopback. Open an SSH tunnel from the operator Mac:

```bash
ssh -N \
  -L 18380:127.0.0.1:18380 \
  -L 18280:127.0.0.1:18280 \
  root@YOUR_ECS_HOST
```

Then open `http://127.0.0.1:18380`. The Platform API is available locally at
`http://127.0.0.1:18280`. This keeps the RC login token inside the encrypted SSH connection until a
dedicated HTTPS hostname is configured.
