# V1.6 API Completeness Audit - Web ↔ OpenAPI

Per V1.6 §6 / §9 ("Web 请求与 OpenAPI operation 100% 对照报告"). This report is now
**automatically enforced** by two tests rather than hand-maintained:

- **Web side** — `kairo-platform-web/tests/web-openapi-completeness.test.ts` scans the
  actual Web source (`lib/api`, `components`, `app`) for every Platform request the BFF
  issues and proves each callsite is covered by the authoritative registry
  `kairo-platform-web/lib/api/paths.ts`. Matching is structural, so a Web callsite that
  generalises over a union (e.g. `instances/${id}/agent/${action}`) is covered by the
  concrete operations it expands to.
- **Platform side** — `WebOpenApiCompletenessTest` reads `paths.ts` and proves every
  registry entry exists in the live OpenAPI document at `/v3/api-docs` (method + path).

Together: **every Web request maps to a real OpenAPI operation**, with no manual table
to keep in sync. Adding a Web request without registering it, or removing a Platform
operation the Web relies on, fails the build.

## OpenAPI source of truth

`GET /v3/api-docs` (springdoc-openapi, generated from controllers + DTOs). The contract
test `OpenApiContractTest` guards the committed V1.6 operation set, the `ApiError`
schema, and the typed request DTO schemas against breaking changes. The auth filter
permits unauthenticated access to `/v3/api-docs` and `/api/v1/schemas` so AI/SDK clients
can discover the contract.

## Web request → OpenAPI operation coverage

The registry (`lib/api/paths.ts`) is the exhaustive list of Platform operations the Web
issues. It is grouped by resource family:

| Family | Operations (method + path template) |
|---|---|
| health / dashboard | `GET control/health`, `GET dashboard/overview` |
| deprecated query (compat) | `GET query/{resource}`, `GET details/{resource}/{id}` |
| target discovery | `GET targets/search`, `GET targets/loaders` |
| rules | `GET rules/{id}/detail`, `POST rules/preview`, `POST rules`, `POST rules/{id}/versions`, `POST rules/{id}/versions/{version}/disable\|enable` |
| scripts / script sessions | `POST scripts/compile\|test`, `GET/POST script-sessions`, `POST script-sessions/{id}/validate\|apply\|promote`, `DELETE script-sessions/{id}`, `GET script-sessions/{id}/events`, `GET/PUT apps/{appId}/script-policy` |
| automation sessions (AI) | `GET/POST automation-sessions`, `GET automation-sessions/{id}`, `POST automation-sessions/{id}/resolve-targets\|preview\|revert` |
| instances / agents / sidecars / fencing | `POST instances\|agents\|sidecars\|fencing-tokens`, `POST instances/{id}/environment\|agent/attach\|deactivate\|reload`, `PATCH instances/{id}/nickname`, `GET instances` |
| operation plans | `POST operation-plans/{id}/transition\|unload` |
| auth | `GET/PATCH auth/me`, `POST auth/me/token/replace`, `GET auth/users`, `POST auth/tokens\|tokens/{id}/renew\|users/{username}/token/replace\|tokens/renew`, `DELETE auth/users/{username}` |
| bytecode diagnostics | `GET agents/{agentId}/classes/{classId}/transformations\|bytecode\|diff`, `POST .../preview\|capture` |

Callsites that compose a path at runtime from a variable (e.g. the create-form
`endpoint` variable, `${transitionPath[...]}`) are annotated with `via:` in the
registry; their concrete target operations are all present and Platform-verified.

## V1.6 §6 audit checklist

1. **Web requests ↔ OpenAPI operations**: automatically enforced — every Web callsite
   is proven to be covered by the registry, and every registry entry is proven to exist
   in the live OpenAPI document. No hand-maintained mapping.
2. **Permission / idempotency / error / audit coverage**: every write endpoint requires
   a capability (RBAC), is covered by the cross-cutting `IdempotencyFilter`
   (`V16WriteApiIdempotencyOptimisticLockTest` proves replay + conflict on the V1.6
   paths), returns the structured `ApiError`, and is recorded in `audit_record`. The
   client (`lib/api/client.ts`) always sends an `Idempotency-Key` for writes and
   surfaces `code`/`category`/`retryable`/`suggestedActions` on errors
   (`lib/api/error.ts`); components branch on `code`, not on message text.
3. **CLI/MCP no agent-direct or DB bypass**: the CLI (`kairo-cli`) and MCP (`kairo-mcp`)
   modules depend only on `kairo-sdk` → `KairoClient`, a pure HTTP client of the
   Platform API. Neither depends on `kairo-agent-*` or `kairo-platform-server`
   (verified by dependency graph).
4. **High-risk ops have preview + revert**: `trial`/`promote` are gated behind
   `preview` (MCP requires `previewToken`+`previewRevision`); `revert` provides one-click
   revert. The Operation resource carries `revertOperationId`. The full
   preview → trial → promote → revert chain is proven end-to-end by
   `V16AiFullLifecycleE2eTest`.
5. **Deprecated APIs have replacement + compat test**: `/api/v1/query/{resource}` and
   `/api/v1/details/{resource}/{id}` are `@Deprecated` (OpenAPI `deprecated:true`) with
   first-class replacements (`/applications`, `/instances`, `/rules`, ...);
   `ResourceFamilyCompatTest` exhaustively proves every deprecated query resource
   remains backward compatible AND every first-class resource-family endpoint publishes
   camelCase JSON (no snake_case leak).

## Automation-session UI

The AI automation-session management panel **is wired into the sidebar**
(`components/layout/app-shell.tsx` → `/automation-sessions`) and renders
`components/automation/automation-sessions-page.tsx`. Its data layer
(`lib/api/automation.ts`) and sidebar wiring are covered by
`tests/automation-sessions.test.ts` (list/get/resolve/preview/revert + structured-error
branching + idempotency-key propagation).

## Rule assembly (no client-side business logic)

There is no client-side rule-payload assembly: the workbench calls the server-side
canonical `POST /api/v1/rules/preview` (`lib/api/rules.ts`), which owns the business
defaults (status, risk, capabilities, target/matcher shape) and returns the exact typed
payload to persist plus a preview token/revision, structured impact/risk and revert
guidance. `lib/rules/assembly.ts` was removed; the platform is the single source of
truth for rule assembly (`RulePreviewService` + `RulePreviewIntegrationTest`).
