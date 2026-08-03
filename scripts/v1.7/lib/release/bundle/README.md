# Kairo V1.7 agent bundle (roadmap §12.2)

This bundle contains everything needed to attach the Kairo agent to a target JVM and to run the
ops diagnostic CLI against the Platform. It is the official agent-side release artifact.

## Layout

```
lib/
  kairo-bootstrap-api-<version>.jar   versioned bootstrap API
  kairo-agent-bootstrap.jar           shaded agent bootstrap
  kairo-agent-core-modern.jar         shaded modern agent core
  kairo-attach.jar                    shaded attach CLI
  kairo-ops.jar                       shaded ops CLI
LICENSE
examples/
  attach-list.sh      list attachable target JVMs (safe, no attach)
  attach-launch.sh    attach the agent to one target JVM (operator supplies pid + token)
  ops-version.sh      print the ops CLI build version (safe, no network)
```

The shaded jars carry their dependencies and report the unified V1.7 build version via `--version`.

## Usage

Run any shaded jar with `--version` to confirm the build identity, e.g.:
```
java -jar lib/kairo-attach.jar --version
java -jar lib/kairo-ops.jar --version
```

See `examples/` for bounded launch invocations. `KAIRO_AGENT_TOKEN` protects the attached Agent's
loopback API; it is distinct from any Platform credential and is always operator-supplied. This
bundle ships no default/development token.

## Excluded

`kairo-demo`, test fixtures, unshaded internal modules, `node_modules`, source trees, target
caches, and local credentials are explicitly excluded from this bundle (roadmap §12.2 / §12.6).
