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

The GitHub runner pulls the approved digests with its short-lived, repository-scoped
`GITHUB_TOKEN`, verifies both revision labels, and streams a compressed Docker archive through the
forced-command SSH key. The ECS host loads the archive, verifies the labels again, and never stores
or receives a GHCR credential. This also avoids slow cross-border GHCR pulls from the ECS host.

## Public Web access

The Web container binds to ECS loopback port `18381`. Nginx terminates TLS on public port `18380`
using `nginx-kairo.conf` and proxies requests to that loopback listener. Set
`KAIRO_WEB_PUBLIC_BASE_URL` in `/opt/kairo/.env` to the externally reachable HTTPS URL.

The Platform API remains private on `127.0.0.1:18280`; PostgreSQL and Redis have no host port.
