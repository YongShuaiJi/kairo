/**
 * V1.6 §6 / §9 authoritative registry of every Platform API operation the Web
 * BFF issues. This is the shared contract artifact between the Web and the
 * Platform: the Web completeness test (`tests/web-openapi-completeness.test.ts`)
 * scans `lib/api`, `components` and `app` source to prove every Web request is
 * covered by this registry, and the Platform test
 * (`WebOpenApiCompletenessTest`) proves every registry entry exists in the live
 * OpenAPI document at `/v3/api-docs`. Together they give automatic, exhaustive
 * Web↔OpenAPI operation coverage with no hand-maintained report.
 *
 * Paths are OpenAPI-form (e.g. `automation-sessions/{id}/revert`), relative to
 * `/api/v1/`. A literal segment means the Web calls that exact operation; a
 * `{param}` segment is a path variable. Web callsites that generalise over a
 * union of literals (e.g. `instances/${id}/agent/${action}`) are expanded to
 * their concrete operations here.
 */
export type PlatformOperation = {
  method: string;
  path: string;
  /** Optional note for operations reached via a runtime-composed path variable. */
  via?: string;
};

export const PLATFORM_PATHS: readonly PlatformOperation[] = [
  // health / dashboard
  { method: "GET", path: "control/health" },
  { method: "GET", path: "dashboard/overview" },

  // deprecated generic query (retained for compat; first-class replacements exist)
  { method: "GET", path: "query/{resource}", via: "query/${activeEndpoint}, query/${field.source}, query/instances, query/environments" },
  { method: "GET", path: "details/{resource}/{id}", via: "details/${activeEndpoint}/${id}, details/operation-plans/${id}" },

  // target discovery / resolution
  { method: "GET", path: "targets/search" },
  { method: "GET", path: "targets/loaders" },

  // rules
  { method: "GET", path: "rules/{id}/detail" },
  { method: "POST", path: "rules/preview" },
  { method: "POST", path: "rules", via: "rule-workbench create endpoint variable" },
  { method: "POST", path: "rules/{id}/versions", via: "rule-workbench create endpoint variable" },
  { method: "POST", path: "rules/{id}/versions/{version}/disable", via: "rules/${ruleId}/versions/${version}/${action}" },
  { method: "POST", path: "rules/{id}/versions/{version}/enable", via: "rules/${ruleId}/versions/${version}/${action}" },

  // scripts / script sessions
  { method: "POST", path: "scripts/compile" },
  { method: "POST", path: "scripts/test" },
  { method: "GET", path: "script-sessions" },
  { method: "GET", path: "script-sessions/{id}" },
  { method: "GET", path: "script-sessions/{id}/events" },
  { method: "POST", path: "script-sessions" },
  { method: "POST", path: "script-sessions/{id}/validate" },
  { method: "POST", path: "script-sessions/{id}/apply" },
  { method: "POST", path: "script-sessions/{id}/promote" },
  { method: "DELETE", path: "script-sessions/{id}" },
  { method: "GET", path: "apps/{appId}/script-policy" },
  { method: "PUT", path: "apps/{appId}/script-policy" },

  // automation sessions (V1.6 AI)
  { method: "GET", path: "automation-sessions" },
  { method: "GET", path: "automation-sessions/{id}" },
  { method: "POST", path: "automation-sessions" },
  { method: "POST", path: "automation-sessions/{id}/resolve-targets" },
  { method: "POST", path: "automation-sessions/{id}/preview" },
  { method: "POST", path: "automation-sessions/{id}/revert" },

  // instances / agents / sidecars / fencing tokens (create via typed DTO)
  { method: "POST", path: "instances", via: "resource-page create endpoint variable" },
  { method: "POST", path: "instances/{id}/environment" },
  { method: "PATCH", path: "instances/{id}/nickname" },
  { method: "POST", path: "instances/{id}/agent/attach", via: "instances/${id}/agent/${action}" },
  { method: "POST", path: "instances/{id}/agent/deactivate", via: "instances/${id}/agent/${action}" },
  { method: "POST", path: "instances/{id}/agent/reload", via: "instances/${id}/agent/${action}" },
  { method: "GET", path: "instances", via: "listResource('instances')" },
  { method: "POST", path: "agents", via: "resource-page create endpoint variable" },
  { method: "POST", path: "sidecars", via: "resource-page create endpoint variable" },
  { method: "POST", path: "fencing-tokens", via: "resource-page create endpoint variable + fencing-tokens issue" },

  // operation plans
  { method: "POST", path: "operation-plans/{id}/transition", via: "${transitionPath[activeEndpoint]}/${detail.id}/transition" },
  { method: "POST", path: "operation-plans/{id}/unload" },

  // auth (account + session bootstrap via the BFF session route)
  { method: "GET", path: "auth/me", via: "app/api/auth/session route server-side fetch" },
  { method: "PATCH", path: "auth/me", via: "app/api/auth/session route server-side fetch" },
  { method: "POST", path: "auth/me/token/replace", via: "app/api/auth/session/token route server-side fetch" },
  { method: "GET", path: "auth/users" },
  { method: "POST", path: "auth/tokens" },
  { method: "POST", path: "auth/tokens/{id}/renew" },
  { method: "POST", path: "auth/users/{username}/token/replace" },
  { method: "POST", path: "auth/users/{username}/tokens/renew" },
  { method: "DELETE", path: "auth/users/{username}" },

  // bytecode diagnostics
  { method: "GET", path: "agents/{agentId}/classes/{classId}/transformations" },
  { method: "GET", path: "agents/{agentId}/classes/{classId}/bytecode" },
  { method: "GET", path: "agents/{agentId}/classes/{classId}/diff" },
  { method: "POST", path: "agents/{agentId}/classes/{classId}/preview" },
  { method: "POST", path: "agents/{agentId}/classes/{classId}/capture" },
];
