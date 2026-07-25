# M1-G Module Convergence Inventory

Record for §8.7 (M1-G) of `docs/roadmap/v1.x-technical/v1.7-lts-stabilization.md`.
Captures the pre-migration reactor inventory, the `rg` proof that the two deletion
targets have no in-repo production consumers, and the fixed post-migration topology.

## Pre-migration Maven reactor (18 modules)

`mainJava`/`testJava` count `.java` files under `src/main/java` / `src/test/java`.

| # | Module | Type | mainJava | testJava | Dockerfile | Main class | Internal deps |
|---|---|---|---|---|---|---|---|
| 1 | kairo-bootstrap-api | internal lib (bootstrap ABI) | 7 | 1 | no | - | - |
| 2 | kairo-api | internal lib (public model/script API) | 93 | 12 | no | - | - |
| 3 | **kairo-object** | internal lib (4 classes) | 4 | 1 | no | - | kairo-api, jackson-databind |
| 4 | kairo-groovy | internal lib (script backend) | 20 | 7 | no | - | kairo-api, kairo-core |
| 5 | kairo-core | internal lib (rule kernel) | 24 | 5 | no | - | kairo-bootstrap-api, kairo-api, kairo-object, kairo-groovy |
| 6 | kairo-agent-core | embedded runtime core (Byte Buddy) | 74 | 22 | no | - | kairo-bootstrap-api, kairo-core, byte-buddy |
| 7 | kairo-agent-server | agent local mgmt + Platform protocol | 14 | 11 | no | - | kairo-agent-core, kairo-bootstrap-api, kairo-api |
| 8 | kairo-agent-core-modern | shaded assembly (0 main class) | 0 | 0 | no | - | kairo-agent-core |
| 9 | kairo-agent-bootstrap | thin premain/agentmain entrypoint | 4 | 0 | no | - | kairo-bootstrap-api |
| 10 | **kairo-attach-cli** | attach tool (JDK Attach API) | 3 | 1 | no | AttachCommand | - |
| 11 | kairo-ops | loopback emergency CLI | 2 | 1 | no | OpsCommand | kairo-api |
| 12 | **kairo-sidecar** | attach executor + orphan WAL/masking | 8 | 2 | yes | AttachSidecarServer | jackson-databind |
| 13 | kairo-platform-server | central application #1 | 114 | 77 | yes | KairoPlatformApplication | many |
| 14 | kairo-sdk | Java SDK | 3 | 1 | no | - | kairo-api |
| 15 | kairo-cli | platform automation CLI | 1 | 1 | no | KairoCli | kairo-sdk |
| 16 | kairo-mcp | MCP stdio server | 2 | 1 | no | KairoMcpServer | kairo-sdk |
| 17 | kairo-demo | demo fixture | 6 | 0 | yes | DemoApplication | kairo-bootstrap-api |
| 18 | kairo-integration-tests | test-only (0 main class) | 0 | 7 | no | - | several |

`kairo-platform-web` is an independent Node workspace and is not counted in the Maven
reactor. It is the second central application.

## Deletion targets

### kairo-object (merge into kairo-core)

- Production classes (package `com.example.kairo.object`): `RuntimeObjectFactory`,
  `DefaultRuntimeObjectFactory`, `TypeConverter`, `PropertyPathAccessor`.
- Test: `PropertyPathAccessorSecurityTest`.
- Direct dependencies: `kairo-api`, `jackson-databind` (only
  `DefaultRuntimeObjectFactory` uses Jackson; no class imports `kairo-api`, so that
  dependency is unused by the object sources themselves).
- Java consumers of package `com.example.kairo.object` (rg):
  - `kairo-core`: `DefaultMockApi`, `RuleDispatcher`, `DecisionValidator`,
    `DefaultInvocationContext` (main), `RuleChainDispatcherTest`, `RuleDispatcherTest`
    (test).
  - `kairo-agent-core`: `AgentRuntime`.
- All consumers keep their `import com.example.kairo.object.*` unchanged because the
  package is preserved inside `kairo-core`; `kairo-agent-core` receives the classes
  transitively through its existing `kairo-core` dependency.
- Not a §12.2 release artifact; no standalone image or main class.

### kairo-sidecar (merge attach executor into kairo-attach-cli, delete orphan code)

Production classes (package `com.example.kairo.sidecar`):

| Class | Bucket | Kept? |
|---|---|---|
| `AttachSidecarServer` | (A) attach executor (register/poll ATTACH_AGENT,RELOAD_AGENT/ACK/health) | move to kairo-attach-cli |
| `EncryptedWalWriter` | (B) orphan WAL | delete |
| `WalAppendResult` | (B) orphan WAL model | delete |
| `WalRecord` | (B) orphan WAL model | delete |
| `PayloadMasker` | (B) orphan masking | delete |
| `MaskingPolicy` | (B) orphan masking | delete |
| `MaskingAction` | (B) orphan masking | delete |
| `StableTokenizer` | (B) orphan tokenizer | delete |

Tests: `EncryptedWalWriterTest`, `PayloadMaskerTest` (both target orphan code; deleted
with the code, not mechanically relocated).

- `AttachSidecarServer` is self-contained (Jackson + JDK `HttpServer`/`HttpClient` only)
  and references none of the orphan classes.
- The orphan WAL/masking classes form two self-contained clusters
  (`EncryptedWalWriter`→`WalAppendResult`/`WalRecord`;
  `PayloadMasker`→`MaskingPolicy`/`StableTokenizer`→`MaskingAction`), neither referenced
  by the attach executor.

## rg proof (no production consumers)

Run from the repository root, excluding each target's own directory and `target/`:

### kairo-object

- `rg 'kairo-object'` (artifact references outside `kairo-object/`):
  `pom.xml` (root module list), `kairo-core/pom.xml` (dependency), `README.md`,
  `docs/architecture/module-boundary-governance.md`,
  `docs/developer/platform-technical-guide.md`, and the V1.7 plan doc (not modified).
- `rg 'com\.example\.kairo\.object'` (Java import consumers outside `kairo-object/`):
  only `kairo-core` (4 main + 2 test files) and `kairo-agent-core` (`AgentRuntime`).
  No other module imports the package. Both modules keep working after the move because
  the package is preserved and `kairo-agent-core` already depends on `kairo-core`.

### kairo-sidecar

- `rg 'kairo-sidecar'` (outside `kairo-sidecar/`): `pom.xml` (root module list),
  `docker-compose.attach.yml` (build dockerfile), `scripts/up-demo-attach.sh`
  (`-pl` build list), `README.md`, `docs/architecture/module-boundary-governance.md`,
  `docs/developer/platform-technical-guide.md`,
  `kairo-platform-server/.../freeze/ConfigCatalogCoverageTest.java` (scans
  `kairo-sidecar/src/main/java` for the `sidecar` config component; repointed to
  `kairo-attach-cli/src/main/java`), and the V1.7 plan doc.
- `rg 'com\.example\.kairo\.sidecar'` (Java import consumers outside `kairo-sidecar/`):
  **none**.
- `rg 'AttachSidecarServer'` (outside `kairo-sidecar/`): only the V1.7 plan doc.
- `rg 'EncryptedWalWriter|PayloadMasker|WalWriter|WalReader|Tokenizer|Masker'`
  (outside `kairo-sidecar/`): only the V1.7 plan doc. The orphan recording/WAL/masking
  code has **no production consumers** outside `kairo-sidecar`'s own isolated tests.
- The orphan classes reference **zero** `KAIRO_*` environment variables, so deleting
  them does not shrink the config-coverage discovered set; `AttachSidecarServer`
  references exactly the 21 `KAIRO_*` keys catalogued under the `sidecar` component, so
  repointing the coverage scan to `kairo-attach-cli/src/main/java` keeps
  `ConfigCatalogCoverageTest` green with no catalog change.

## Post-migration fixed topology (16 modules)

```
kairo-bootstrap-api
kairo-api
kairo-groovy
kairo-core                    # absorbs kairo-object (package com.example.kairo.object preserved)
kairo-agent-core
kairo-agent-server
kairo-agent-core-modern       # pure assembly, 0 main class
kairo-agent-bootstrap
kairo-attach-cli              # absorbs demo attach executor (AttachExecutorServer)
kairo-ops
kairo-platform-server
kairo-sdk
kairo-cli
kairo-mcp
kairo-demo                    # fixture
kairo-integration-tests       # test-only, 0 main class
```

Central applications (2): Platform Server, Platform Web (Node workspace).
Embedded runtime (1): Runtime Agent.
Independent tools: Java SDK, Platform CLI, MCP stdio server, Attach CLI (incl. demo
attach executor entrypoint), loopback Ops CLI.
Non-product units: Demo fixture, integration-tests, shaded assembly modules.

`kairo-object` and `kairo-sidecar` no longer exist; no empty-shell compatibility
modules are retained.
