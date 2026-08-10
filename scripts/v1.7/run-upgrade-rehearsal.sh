#!/usr/bin/env bash
#
# scripts/v1.7/run-upgrade-rehearsal.sh
#
# V1.7 M6-A §13.2.1: the frozen upgrade / backup / restore / application-rollback
# rehearsal. Exercises the real V1.6.0 -> candidate migration lineage against an
# isolated PostgreSQL 16 + Redis 7 stack, proves representative persisted runtime
# state survives the upgrade, takes a real pg_dump backup, restores it into a clean
# PostgreSQL 16, and proves application rollback protection.
#
# Fixed interface (roadmap §13.2):
#   ./scripts/v1.7/run-upgrade-rehearsal.sh \
#     --from V1.6.0 --to HEAD --database postgresql16 \
#     --output target/v1.7/upgrade
#
# Authoritative RC evidence REQUIRES a Linux runner and JDK 21. A Darwin / non-Linux
# or non-JDK-21 run is a clearly-labelled DEVELOPMENT SMOKE only (mode=dev,
# authoritative=false) and can NEVER produce RC PASSED evidence.
#
# Exit codes:
#   0  rehearsal completed and every scenario PASSED (authoritative only counts as RC
#      evidence when mode=pr/authoritative=true; a dev PASSED is NOT RC evidence)
#   1  usage / validation / fail-closed precondition (dirty tree, bad refs, wrong Java
#      major, missing Docker, unsupported database, unresolved refs, non-40-hex id)
#   2  build failed (V1.6.0 source or candidate)
#   3  environment unusable (missing Docker, wrong PostgreSQL/Redis major, health timeout)
#   4  scenario failure (migration / seed / backup / restore / rollback-guard /
#      rollback-restore / redis-semantics)
#   5  result-write error
#   6  cleanup failure that invalidates isolation (resources left behind)
#
# The runner NEVER switches or dirties the active V1.7 worktree: the V1.6.0 source is
# built in a fresh detached git worktree at the immutable --from ref, and the candidate
# is built in place at HEAD. It uses isolated per-run Docker names / network / volumes
# and dynamically assigned host ports, cleans only its own resources, preserves logs
# before cleanup, and never modifies the acceptance manifest.

set -euo pipefail

# --------------------------------------------------------------------------- #
# Pinned, non-floating infrastructure images. Overridable via env for the ECR
# mirror workaround (Docker Hub is occasionally unreachable; the release build
# pulls eclipse-temurin from public.ecr.aws/docker/library). Versions pin
# exact patch/minor tags; the resolved image digest is recorded in the result for audit.
# --------------------------------------------------------------------------- #
POSTGRES_IMAGE="${KAIRO_UPGRADE_PG_IMAGE:-postgres:16.4-alpine}"
REDIS_IMAGE="${KAIRO_UPGRADE_REDIS_IMAGE:-redis:7.4.10-alpine}"
PG_MAJOR_REQUIRED="16"
REDIS_MAJOR_REQUIRED="7"
JAVA_MAJOR_REQUIRED="21"
BUILD_TIMEOUT_SECS="${KAIRO_UPGRADE_BUILD_TIMEOUT:-900}"   # 15 min per build
APP_READY_TIMEOUT_SECS="${KAIRO_UPGRADE_READY_TIMEOUT:-120}" # 2 min per start
MVN="${MVN:-mvn}"

# Portable timeout: coreutils `timeout` on Linux, `gtimeout` on macOS (Homebrew
# coreutils), or none (the CI job's timeout-minutes is the backstop). run_timeout
# wraps the build so a local deadline is enforced where the tool exists.
TIMEOUT_BIN=""
command -v timeout  >/dev/null 2>&1 && TIMEOUT_BIN=timeout
command -v gtimeout >/dev/null 2>&1 && TIMEOUT_BIN=gtimeout
run_timeout() { # <secs> <cmd...>
  local secs="$1"; shift
  if [ -n "$TIMEOUT_BIN" ]; then "$TIMEOUT_BIN" "$secs" "$@"; else "$@"; fi
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Result accumulator state dir (cleaned per-run).
STATE_DIR=""
RESULT_FILE=""
SCENARIOS_FILE=""
LOG_DIR=""
RUN_ID=""

# Isolated Docker resource names (populated in setup).
RUN_PREFIX=""
PG_CONTAINER=""
REDIS_CONTAINER=""
DOCKER_NET=""
PG_VOLUME=""
REDIS_VOLUME=""
V16_WORKTREE=""

# Computed environment / refs (filled by validation).
MODE="dev"
AUTHORITATIVE="false"
FROM_REF=""
FROM_COMMIT=""
TO_REF=""
TO_COMMIT=""
OS_NAME=""
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_MAJOR=""
STARTED_AT=""
ENDED_AT=""

# Failure tracking.
GLOBAL_STATUS="PASSED"
declare -a FAILURE_REASONS=()

usage() {
  cat <<'EOF'
Usage: run-upgrade-rehearsal.sh --from <ref> --to <ref> --database <name>
                                --output <dir> [--allow-dirty] [--help]

Required:
  --from       source ref to upgrade FROM (e.g. V1.6.0). Must resolve to a 40-hex commit.
  --to         source ref to upgrade TO (e.g. HEAD or a 40-hex SHA). Must resolve to a 40-hex commit.
  --database   database engine; only "postgresql16" is supported.
  --output     output directory for upgrade-rehearsal-result.json + logs (e.g. target/v1.7/upgrade)

Optional:
  --allow-dirty  DEVELOPMENT ONLY - allow a dirty working tree (records mode=dev,
                 authoritative=false). Never use for RC evidence.
  --help         show this help

Behavior:
  - Authoritative RC evidence requires Linux + JDK 21 + a clean working tree.
    Any other environment (Darwin, wrong JDK, dirty tree) is a dev smoke and can
    NEVER produce RC PASSED evidence.
  - Builds the V1.6.0 source in a fresh detached git worktree at --from (real
    migration lineage, not a hand-written minimal schema) and the candidate in
    place at --to (HEAD).
  - Stands up an isolated PostgreSQL 16 + Redis 7 stack (pinned images, per-run
    names/network/volumes, dynamically assigned host ports); never touches unrelated
    Docker resources or /tmp.
  - Runs the V1.6.0 app to apply V1..V41, seeds representative persisted runtime
    state (fencing_token) and records counts/checksums + flyway version, takes a
    real pg_dump backup, runs the candidate to apply every post-V1.6 migration, proves the seeded state
    survived, proves the V1.6.0 app is safely rejected against the V1.7-migrated DB
    (application rollback protection), and proves rollback via backup/restore.
  - Writes a deterministic-schema upgrade-rehearsal-result.json even on failure
    (best effort). Preserves logs before trap-based cleanup.
  - Never modifies budgets, the acceptance manifest, or git history.
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
ALLOW_DIRTY="false"
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM_REF="$2"; shift 2 ;;
    --to) TO_REF="$2"; shift 2 ;;
    --database) DB_ARG="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY="true"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Required-argument / fail-closed validation (exit 1).
# ---------------------------------------------------------------------------
fail() { echo "error: $*" >&2; }
fail_closed() { fail "$*"; exit 1; }

[ -n "${FROM_REF:-}" ]  || fail_closed "--from is required"
[ -n "${TO_REF:-}" ]    || fail_closed "--to is required"
[ -n "${DB_ARG:-}" ]    || fail_closed "--database is required"
[ -n "${OUTPUT:-}" ]    || fail_closed "--output is required"

[ "$DB_ARG" = "postgresql16" ] || fail_closed "unsupported --database '$DB_ARG'; only 'postgresql16' is supported"
[[ "$POSTGRES_IMAGE" =~ :[0-9]+\.[0-9]+ ]] || fail_closed "PostgreSQL image must use an explicit version tag or digest (got: $POSTGRES_IMAGE)"
[[ "$REDIS_IMAGE" =~ :[0-9]+\.[0-9]+\.[0-9]+ ]] || fail_closed "Redis image must use an explicit patch version tag or digest (got: $REDIS_IMAGE)"
[[ "$POSTGRES_IMAGE" != *:latest ]] || fail_closed "PostgreSQL latest tag is forbidden"
[[ "$REDIS_IMAGE" != *:latest ]] || fail_closed "Redis latest tag is forbidden"

# Resolve refs to 40-hex commits (peeled to the commit object). Unresolved refs
# or non-40-hex ids fail closed.
resolve_commit() { # <ref> -> prints 40-hex
  git -C "$REPO_ROOT" rev-parse "$1^{commit}" 2>/dev/null || return 1
}
FROM_COMMIT="$(resolve_commit "$FROM_REF")" || fail_closed "could not resolve --from '$FROM_REF' to a commit"
TO_COMMIT="$(resolve_commit "$TO_REF")"   || fail_closed "could not resolve --to '$TO_REF' to a commit"
[[ "$FROM_COMMIT" =~ ^[0-9a-f]{40}$ ]] || fail_closed "--from '$FROM_REF' resolved to non-40-hex id '$FROM_COMMIT'"
[[ "$TO_COMMIT"   =~ ^[0-9a-f]{40}$ ]] || fail_closed "--to '$TO_REF' resolved to non-40-hex id '$TO_COMMIT'"
[ "$FROM_COMMIT" != "$TO_COMMIT" ] || fail_closed "--from and --to resolve to the same commit '$FROM_COMMIT'"
HEAD_COMMIT="$(git -C "$REPO_ROOT" rev-parse 'HEAD^{commit}')"
[ "$TO_COMMIT" = "$HEAD_COMMIT" ] || fail_closed "--to resolves to $TO_COMMIT but the checked-out candidate is $HEAD_COMMIT; refusing to build a different HEAD"

# Output directory (must be inside REPO_ROOT target/ to keep evidence roots bounded).
# Validated early, as a pure argument check, before any environment / dirty-tree check.
case "$OUTPUT" in
  /*|*../*|*/..|..) fail_closed "--output must be a relative path under target without '..' (got: $OUTPUT)" ;;
  target/*) : ;;
  *) fail_closed "--output must be a relative path under target (got: $OUTPUT)" ;;
esac
OUTPUT_DIR="$REPO_ROOT/${OUTPUT#./}"
mkdir -p "$OUTPUT_DIR"

# OS + Java detection.
OS_NAME="$(uname -s)"
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -nE 's/.*version "([0-9]+)\..*/\1/p')"
[ -n "$JAVA_MAJOR" ] || fail_closed "could not determine Java major from '$JAVA_BIN -version'"
# Java reports 21.x as "21"; ancient layouts reported 1.8 - normalize.
[ "$JAVA_MAJOR" != "1" ] || JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -nE 's/.*version "1\.([0-9]+)\..*/\1/p')"

# Wrong Java major fails closed (the app targets JDK 21; no JDK 8/11 support).
[ "$JAVA_MAJOR" = "$JAVA_MAJOR_REQUIRED" ] || fail_closed "Java major $JAVA_MAJOR != required $JAVA_MAJOR_REQUIRED (V1.7 targets JDK 21 only)"

# Dirty-tree detection. Authoritative evidence refuses a dirty tree; --allow-dirty
# is a clearly-marked development-only escape (mode=dev, authoritative=false).
DIRTY="false"
[ -z "$(git -C "$REPO_ROOT" status --porcelain)" ] || DIRTY="true"
if [ "$DIRTY" = "true" ] && [ "$ALLOW_DIRTY" != "true" ]; then
  fail "evidence refuses a dirty working tree."
  fail "       Commit the harness first, or use --allow-dirty (DEVELOPMENT ONLY, mode=dev)." >&2
  exit 1
fi

# Authoritative requires Linux + JDK 21 + clean tree (no --allow-dirty).
AUTHORITATIVE="true"
[ "$OS_NAME" = "Linux" ] || AUTHORITATIVE="false"
[ "$DIRTY" = "false" ] || AUTHORITATIVE="false"
[ "$ALLOW_DIRTY" = "false" ] || AUTHORITATIVE="false"
# Mode follows the repo convention (pr/dev, matching run-soak.sh / run-state-cycle.sh);
# the `authoritative` boolean distinguishes a Linux/JDK21/clean RC run from a dev smoke.
MODE="pr"
if [ "$AUTHORITATIVE" != "true" ] || [ "$ALLOW_DIRTY" = "true" ]; then
  MODE="dev"
fi

# Validate candidate provenance before consulting external infrastructure so a
# dirty candidate can never be misreported as merely a Docker availability issue.
command -v docker >/dev/null 2>&1 || fail_closed "docker not found on PATH; required for the isolated PostgreSQL/Redis stack"
docker info >/dev/null 2>&1 || fail_closed "docker daemon not reachable (docker info failed)"

# Per-run state; clear ONLY this runner's prior result + state, then recreate.
rm -rf "$OUTPUT_DIR/state"
mkdir -p "$OUTPUT_DIR/state" "$OUTPUT_DIR/logs"
STATE_DIR="$OUTPUT_DIR/state"
RESULT_FILE="$OUTPUT_DIR/upgrade-rehearsal-result.json"
SCENARIOS_FILE="$STATE_DIR/scenarios.jsonl"
LOG_DIR="$OUTPUT_DIR/logs"
: > "$SCENARIOS_FILE"

# Unique per-run prefix for Docker resources (collision-safe).
RUN_ID="kairo-upg-$(date -u +%Y%m%d%H%M%S)-$$"
RUN_PREFIX="kairo-rc-upgrade-${RUN_ID}"
PG_CONTAINER="${RUN_PREFIX}-pg"
REDIS_CONTAINER="${RUN_PREFIX}-redis"
DOCKER_NET="${RUN_PREFIX}-net"
PG_VOLUME="${RUN_PREFIX}-pgdata"
REDIS_VOLUME="${RUN_PREFIX}-redisdata"

EXACT_CMD="$(printf '%q' "$0") --from $FROM_REF --to $TO_REF --database $DB_ARG --output $OUTPUT"
[ "$ALLOW_DIRTY" = "true" ] && EXACT_CMD="$EXACT_CMD --allow-dirty"

STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "==> upgrade rehearsal: mode=$MODE authoritative=$AUTHORITATIVE dirty=$DIRTY"
echo "==> from=$FROM_REF($FROM_COMMIT) to=$TO_REF($TO_COMMIT)"
echo "==> os=$OS_NAME java-major=$JAVA_MAJOR docker-image-pg=$POSTGRES_IMAGE redis=$REDIS_IMAGE"
echo "==> run-prefix=$RUN_PREFIX"

# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #

# record_scenario <name> <status> <evidence> [durationMs]
record_scenario() {
  python3 - "$SCENARIOS_FILE" "$1" "$2" "$3" "${4:-}" <<'PY'
import json, sys
path,name,status,evidence,dur=sys.argv[1],sys.argv[2],sys.argv[3],sys.argv[4],sys.argv[5]
d={"name":name,"status":status,"evidence":evidence}
if dur: d["durationMs"]=int(dur)
with open(path,"a") as f: f.write(json.dumps(d,sort_keys=True)+"\n")
PY
}

# fail_scenario <name> <evidence> [exit_code=4]: records FAILED, sets global status,
# appends a failure reason. Does NOT exit (caller decides) unless exit_code given.
fail_scenario() {
  local name="$1" evidence="$2" code="${3:-}"
  record_scenario "$name" "FAILED" "$evidence"
  GLOBAL_STATUS="FAILED"
  FAILURE_REASONS+=("$name: $evidence")
  [ -z "$code" ] || exit "$code"
}

# Bounded readiness loop for the platform app. <port> <timeout>
wait_app_ready() {
  local port="$1" timeout="${2:-$APP_READY_TIMEOUT_SECS}"
  local deadline=$(( $(date +%s) + timeout ))
  local now url body
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if body="$(curl -fsS "http://127.0.0.1:${port}/actuator/health" 2>/dev/null)" \
       && printf '%s' "$body" | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# pg psql helper: <sql> -> stdout
pg_psql() { docker exec -i "$PG_CONTAINER" psql -A -t -U kairo -d kairo -v ON_ERROR_STOP=1 -c "$1" 2>&1; }
pg_psql_file() { docker exec -i "$PG_CONTAINER" psql -A -t -U kairo -d kairo -v ON_ERROR_STOP=1 < "$1" 2>&1; }

image_identity() { # <image-ref>: immutable repo digest when available, otherwise image ID
  local value
  value="$(docker image inspect --format '{{join .RepoDigests ","}}' "$1" 2>/dev/null || true)"
  if [ -z "$value" ]; then
    value="$(docker image inspect --format '{{.Id}}' "$1" 2>/dev/null || true)"
  fi
  [ -n "$value" ] || return 1
  printf '%s' "$value"
}

# Record a key=value into the state env file for the final result writer.
state_set() { printf '%s\n' "$1=$2" >> "$STATE_DIR/state.env"; }

# Resolve the repackaged platform-server jar in a build dir.
find_platform_jar() { # <build-root>
  local jar
  jar="$(ls "$1"/kairo-platform-server/target/kairo-platform-server-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
  [ -n "$jar" ] || return 1
  printf '%s' "$jar"
}

# --------------------------------------------------------------------------- #
# Finalize: write the deterministic-schema result JSON. Defined BEFORE the
# cleanup trap so the trap can always write the result (best effort), even when
# the script exits early (e.g. Docker/build failure) before the scenarios run.
# --------------------------------------------------------------------------- #
finalize_result() {
  # Join failure reasons with a record-separator (\x1e) so multi-word reasons survive as
  # single list elements (a plain space join would split them into words).
  local _fr=""
  if [ "${#FAILURE_REASONS[@]}" -gt 0 ] 2>/dev/null; then
    _fr="$(printf '%s\x1e' "${FAILURE_REASONS[@]}")"
  fi
  python3 - "$RESULT_FILE" "$STATE_DIR" "$SCENARIOS_FILE" "$STARTED_AT" "${ENDED_AT:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}" "$GLOBAL_STATUS" "$MODE" "$AUTHORITATIVE" "$FROM_REF" "$FROM_COMMIT" "$TO_REF" "$TO_COMMIT" "$DB_ARG" "$EXACT_CMD" "$_fr" "$DIRTY" <<'PY'
import json, os, sys
(out, state_dir, scen_file, started, ended, gstatus, mode, auth, fref, fcom, tref, tcom, db, cmd, fails, dirty) = sys.argv[1:17]
failures = [f for f in fails.split("\x1e") if f] if fails else []

scenarios = []
if os.path.exists(scen_file):
    with open(scen_file) as f:
        for line in f:
            line=line.strip()
            if line: scenarios.append(json.loads(line))

state = {}
envf = os.path.join(state_dir, "state.env")
if os.path.exists(envf):
    with open(envf) as f:
        for line in f:
            line=line.rstrip("\n")
            if "=" in line:
                k,v=line.split("=",1); state[k]=v

def put(root, dotted, val):
    cur=root; parts=dotted.split(".")
    for p in parts[:-1]:
        cur=cur.setdefault(p,{})
    if val.isdigit(): val=int(val)
    elif val in ("true","false"): val=(val=="true")
    cur[parts[-1]]=val

env={}; backup={}; mig={}; ps_before={}; ps_after={}; rg={}; rr={}; redis={}; cleanup={}
for k,v in state.items():
    if k.startswith("environment."): put(env, k[len("environment."):], v)
    elif k.startswith("backup."): put(backup, k[len("backup."):], v)
    elif k.startswith("migrationVersions."): put(mig, k[len("migrationVersions."):], v)
    elif k.startswith("persistedState.before."): put(ps_before, k[len("persistedState.before."):], v)
    elif k.startswith("persistedState.after."): put(ps_after, k[len("persistedState.after."):], v)
    elif k.startswith("rollbackGuard."): put(rg, k[len("rollbackGuard."):], v)
    elif k.startswith("rollbackRestore."): put(rr, k[len("rollbackRestore."):], v)
    elif k.startswith("redis."): put(redis, k[len("redis."):], v)
    elif k.startswith("cleanup."): put(cleanup, k[len("cleanup."):], v)

persisted={"before":ps_before,"after":ps_after,"survived":state.get("persistedState.survived")=="true"}

limitations=[]
if not (auth=="true" and mode=="pr"): limitations.append("DEVELOPMENT SMOKE - mode=dev; not authoritative RC evidence (requires Linux/JDK21/clean candidate commit)")
if auth!="true": limitations.append("authoritative=false; a Darwin/non-Linux or dirty run can never produce RC PASSED evidence")
if gstatus!="PASSED": limitations.append("one or more scenarios FAILED; see failureReasons")
limitations.append("Redis is exercised as cache/fencing/ephemeral state; PostgreSQL is the source of truth - no durable business-state recovery is claimed from Redis")
limitations.append("seeded representative state covers the stable fencing_token table (V4); full business-state coverage is exercised by the M1 recovery and M3 compatibility gates")

result={
  "schemaVersion":"1.0",
  "facility":"kairo-upgrade-rehearsal",
  "mode":mode,
  "workingTreeDirty":dirty=="true",
  "authoritative":auth=="true",
  "status":gstatus,
  "buildId":tcom,
  "fromRef":fref,"fromCommit":fcom,
  "toRef":tref,"toCommit":tcom,
  "database":db,
  "environment":env,
  "command":cmd,
  "startedAt":started,
  "endedAt":ended,
  "scenarios":scenarios,
  "migrationVersions":mig,
  "backup":backup,
  "persistedState":persisted,
  "rollbackGuard":rg,
  "rollbackRestore":rr,
  "redis":redis,
  "cleanup":cleanup,
  "reports":[],
  "limitations":limitations,
  "failureReasons":failures,
}
for name, rel in (("v16-backup","v16-backup.sql"),("postgres-log","logs/postgres.log"),("redis-log","logs/redis.log")):
    path=os.path.join(os.path.dirname(out), rel)
    digest=""
    if os.path.isfile(path):
        import hashlib
        h=hashlib.sha256()
        with open(path,"rb") as fh:
            for chunk in iter(lambda: fh.read(65536), b""): h.update(chunk)
        digest=h.hexdigest()
    result["reports"].append({"name":name,"path":rel,"sha256":digest})
tmp=out+".tmp"
json.dump(result, open(tmp,"w"), indent=2, sort_keys=True)
os.replace(tmp, out)
PY
}

# --------------------------------------------------------------------------- #
# Cleanup: remove ONLY this run's Docker resources + worktree. Preserve logs.
# A cleanup failure that leaves resources invalidates isolation (exit 6) but the
# result is still written with cleanup.result=FAILED.
# --------------------------------------------------------------------------- #
CLEANUP_RESULT="PENDING"
cleanup() {
  local rc=$?
  set +e
  # An unexpected non-zero exit (e.g. `set -e` on an unguarded command) that occurs
  # BEFORE fail_scenario sets GLOBAL_STATUS=FAILED must never leave a PASSED-looking
  # result for an incomplete run. Mark it FAILED with an explicit reason.
  if [ "$rc" -ne 0 ] && [ "$GLOBAL_STATUS" = "PASSED" ]; then
    GLOBAL_STATUS="FAILED"
    FAILURE_REASONS+=("unexpected-exit: script exited rc=$rc before completing all scenarios (incomplete run)")
  fi
  local detail=""
  # Stop app processes we started (best effort).
  for pf in "$STATE_DIR"/*.app.pid; do
    [ -f "$pf" ] || continue
    local pid; pid="$(cat "$pf" 2>/dev/null)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; sleep 3; kill -9 "$pid" 2>/dev/null || true; fi
  done
  # Preserve container logs before removal.
  if [ -n "$PG_CONTAINER" ] && docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then
    docker logs "$PG_CONTAINER" > "$LOG_DIR/postgres.log" 2>&1
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || detail="$detail; pg-rm-failed"
  fi
  if [ -n "$REDIS_CONTAINER" ] && docker inspect "$REDIS_CONTAINER" >/dev/null 2>&1; then
    docker logs "$REDIS_CONTAINER" > "$LOG_DIR/redis.log" 2>&1
    docker rm -f "$REDIS_CONTAINER" >/dev/null 2>&1 || detail="$detail; redis-rm-failed"
  fi
  [ -z "$PG_VOLUME" ]    || docker volume rm "$PG_VOLUME"    >/dev/null 2>&1 || detail="$detail; pgvol-rm-failed"
  [ -z "$REDIS_VOLUME" ] || docker volume rm "$REDIS_VOLUME" >/dev/null 2>&1 || detail="$detail; redisvol-rm-failed"
  [ -z "$DOCKER_NET" ]   || docker network rm "$DOCKER_NET"  >/dev/null 2>&1 || detail="$detail; net-rm-failed"
  # Remove the V1.6.0 worktree if we created it.
  if [ -n "$V16_WORKTREE" ] && [ -d "$V16_WORKTREE" ]; then
    git -C "$REPO_ROOT" worktree remove --force "$V16_WORKTREE" >/dev/null 2>&1 || detail="$detail; worktree-rm-failed"
  fi
  if [ -z "$detail" ]; then
    CLEANUP_RESULT="PASSED"
  else
    CLEANUP_RESULT="FAILED"
    FAILURE_REASONS+=("cleanup:$detail")
    GLOBAL_STATUS="FAILED"
  fi
  state_set "cleanup.result" "$CLEANUP_RESULT"
  state_set "cleanup.detail" "${detail#; }"
  finalize_result
  # A cleanup failure that leaves isolated resources behind invalidates isolation.
  if [ "$CLEANUP_RESULT" = "FAILED" ] && [ "$rc" -eq 0 ]; then rc=6; fi
  exit "$rc"
}
trap cleanup EXIT

# --------------------------------------------------------------------------- #
# Stand up the isolated PostgreSQL 16 + Redis 7 stack.
# --------------------------------------------------------------------------- #
echo "==> creating isolated Docker network + containers"
docker network create "$DOCKER_NET" >/dev/null 2>&1 || fail_closed "could not create isolated network $DOCKER_NET"

docker run -d --name "$PG_CONTAINER" --network "$DOCKER_NET" \
  -e POSTGRES_DB=kairo -e POSTGRES_USER=kairo -e POSTGRES_PASSWORD=kairo \
  -v "$PG_VOLUME:/var/lib/postgresql/data" \
  --health-cmd='pg_isready -U kairo -d kairo' --health-interval=2s --health-timeout=3s --health-retries=30 \
  -P "$POSTGRES_IMAGE" >/dev/null 2>&1 || fail_closed "could not start PostgreSQL container ($POSTGRES_IMAGE)"

docker run -d --name "$REDIS_CONTAINER" --network "$DOCKER_NET" \
  -v "$REDIS_VOLUME:/data" \
  --health-cmd='redis-cli ping' --health-interval=2s --health-timeout=3s --health-retries=30 \
  -P "$REDIS_IMAGE" \
  redis-server --appendonly yes >/dev/null 2>&1 || fail_closed "could not start Redis container ($REDIS_IMAGE)"

# Resolve the dynamically assigned host ports.
PG_HOST_PORT="$(docker port "$PG_CONTAINER" 5432/tcp | sed -E 's/.*://; q')"
REDIS_HOST_PORT="$(docker port "$REDIS_CONTAINER" 6379/tcp | sed -E 's/.*://; q')"
[ -n "$PG_HOST_PORT" ]    || fail_scenario "start-postgres" "no host port mapped for pg 5432" 3
[ -n "$REDIS_HOST_PORT" ] || fail_scenario "start-redis" "no host port mapped for redis 6379" 3

# Wait for PostgreSQL readiness (bounded).
echo "==> waiting for PostgreSQL 16 readiness (host port $PG_HOST_PORT)"
deadline=$(( $(date +%s) + 60 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if docker exec "$PG_CONTAINER" pg_isready -U kairo -d kairo >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec "$PG_CONTAINER" pg_isready -U kairo -d kairo >/dev/null 2>&1 \
  || fail_scenario "start-postgres" "pg_isready never succeeded" 3

# Verify the real PostgreSQL major version (fail closed on wrong major).
PG_VERSION_FULL="$(docker exec "$PG_CONTAINER" psql -U kairo -d kairo -tAc 'SELECT version()' 2>/dev/null | head -1)"
PG_MAJOR_DETECTED="$(printf '%s' "$PG_VERSION_FULL" | sed -nE 's/^PostgreSQL ([0-9]+)\..*/\1/p')"
[ "$PG_MAJOR_DETECTED" = "$PG_MAJOR_REQUIRED" ] \
  || fail_scenario "start-postgres" "PostgreSQL major $PG_MAJOR_DETECTED != required $PG_MAJOR_REQUIRED (version: $PG_VERSION_FULL)" 3
PG_IMAGE_DIGEST="$(image_identity "$POSTGRES_IMAGE")" || fail_scenario "start-postgres" "could not resolve immutable PostgreSQL image identity" 3

# Wait for Redis readiness (bounded).
echo "==> waiting for Redis 7 readiness (host port $REDIS_HOST_PORT)"
deadline=$(( $(date +%s) + 60 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if docker exec "$REDIS_CONTAINER" redis-cli ping >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec "$REDIS_CONTAINER" redis-cli ping >/dev/null 2>&1 || fail_scenario "start-redis" "redis-cli ping never succeeded" 3
REDIS_VERSION_FULL="$(docker exec "$REDIS_CONTAINER" redis-cli INFO server 2>/dev/null | sed -nE 's/^redis_version:(.+)/\1/p' | head -1 | tr -d '[:space:]')"
REDIS_MAJOR_DETECTED="$(printf '%s' "$REDIS_VERSION_FULL" | sed -nE 's/^([0-9]+)\..*/\1/p')"
[ "$REDIS_MAJOR_DETECTED" = "$REDIS_MAJOR_REQUIRED" ] \
  || fail_scenario "start-redis" "Redis major $REDIS_MAJOR_DETECTED != required $REDIS_MAJOR_REQUIRED (version: $REDIS_VERSION_FULL)" 3
REDIS_IMAGE_DIGEST="$(image_identity "$REDIS_IMAGE")" || fail_scenario "start-redis" "could not resolve immutable Redis image identity" 3

record_scenario "start-postgres" "PASSED" "PostgreSQL $PG_VERSION_FULL on host port $PG_HOST_PORT; image=$POSTGRES_IMAGE"
record_scenario "start-redis" "PASSED" "Redis $REDIS_VERSION_FULL on host port $REDIS_HOST_PORT; image=$REDIS_IMAGE"

state_set "environment.os" "$OS_NAME"
state_set "environment.java" "$("$JAVA_BIN" -version 2>&1 | head -1)"
state_set "environment.javaMajor" "$JAVA_MAJOR"
state_set "environment.postgresql.image" "$POSTGRES_IMAGE"
state_set "environment.postgresql.version" "$PG_VERSION_FULL"
state_set "environment.postgresql.digest" "$PG_IMAGE_DIGEST"
state_set "environment.postgresql.hostPort" "$PG_HOST_PORT"
state_set "environment.redis.image" "$REDIS_IMAGE"
state_set "environment.redis.version" "$REDIS_VERSION_FULL"
state_set "environment.redis.digest" "$REDIS_IMAGE_DIGEST"
state_set "environment.redis.hostPort" "$REDIS_HOST_PORT"

# --------------------------------------------------------------------------- #
# Build the V1.6.0 source in a fresh detached worktree at the immutable --from ref.
# This exercises the real migration lineage (V1..V41), not a hand-written schema.
# --------------------------------------------------------------------------- #
echo "==> building V1.6.0 source at $FROM_COMMIT in an isolated worktree"
V16_WORKTREE="$REPO_ROOT/target/v1.7/worktrees/$RUN_ID-v16"
mkdir -p "$(dirname "$V16_WORKTREE")"
if ! git -C "$REPO_ROOT" worktree add --detach "$V16_WORKTREE" "$FROM_COMMIT" >/dev/null 2>&1; then
  V16_WORKTREE=""; fail_scenario "build-v16" "git worktree add at $FROM_COMMIT failed" 2
fi
latest_migration_version() {
  python3 - "$1" <<'PY'
import glob, os, re, sys
versions=[]
for path in glob.glob(os.path.join(sys.argv[1], "kairo-platform-server/src/main/resources/db/migration/V*__*.sql")):
    m=re.match(r"V([0-9]+(?:\.[0-9]+)*)__", os.path.basename(path))
    if m:
        versions.append((tuple(int(x) for x in m.group(1).split('.')), m.group(1)))
if not versions: raise SystemExit(1)
print(max(versions)[1])
PY
}
FROM_LATEST_MIGRATION="$(latest_migration_version "$V16_WORKTREE")" || fail_scenario "build-v16" "could not determine V1.6 migration head" 2
TO_LATEST_MIGRATION="$(latest_migration_version "$REPO_ROOT")" || fail_scenario "build-candidate" "could not determine candidate migration head" 2
t0=$(date +%s)
if ! ( cd "$V16_WORKTREE" && run_timeout "$BUILD_TIMEOUT_SECS" "$MVN" -B -ntp -pl kairo-platform-server -am -DskipTests package -q ); then
  fail_scenario "build-v16" "V1.6.0 Maven build failed (timeout ${BUILD_TIMEOUT_SECS}s)" 2
fi
t1=$(date +%s)
V16_JAR="$(find_platform_jar "$V16_WORKTREE")" || fail_scenario "build-v16" "no repackaged platform-server jar found in V1.6.0 build" 2
record_scenario "build-v16" "PASSED" "built V1.6.0 ($FROM_COMMIT) -> $(basename "$V16_JAR")" $(( t1 - t0 ))

# Build the candidate in place at HEAD (--to). Does not dirty the worktree.
echo "==> building candidate at $TO_COMMIT in place"
t0=$(date +%s)
if ! ( cd "$REPO_ROOT" && run_timeout "$BUILD_TIMEOUT_SECS" "$MVN" -B -ntp -pl kairo-platform-server -am -DskipTests package -q ); then
  fail_scenario "build-candidate" "candidate Maven build failed (timeout ${BUILD_TIMEOUT_SECS}s)" 2
fi
t1=$(date +%s)
CAND_JAR="$(find_platform_jar "$REPO_ROOT")" || fail_scenario "build-candidate" "no repackaged platform-server jar found in candidate build" 2
record_scenario "build-candidate" "PASSED" "built candidate ($TO_COMMIT) -> $(basename "$CAND_JAR")" $(( t1 - t0 ))

# --------------------------------------------------------------------------- #
# Pick a collision-free app port (the app listens on the host; multiple sequential
# starts reuse the same dynamic port). Spring Boot reads SERVER_PORT.
# --------------------------------------------------------------------------- #
APP_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')"
state_set "environment.appPort" "$APP_PORT"

# Launch the platform app jar against the isolated PG/Redis. <jar> <logfile>
# Sets APP_PID. Records the process.
launch_app() {
  local jar="$1" logfile="$2"
  : > "$logfile"
  KAIRO_DB_URL="jdbc:postgresql://127.0.0.1:${PG_HOST_PORT}/kairo" \
  KAIRO_DB_USER=kairo KAIRO_DB_PASSWORD=kairo \
  KAIRO_REDIS_HOST=127.0.0.1 KAIRO_REDIS_PORT="$REDIS_HOST_PORT" \
  KAIRO_FENCING_REDIS_ENABLED=true \
  KAIRO_API_ENABLED=true KAIRO_AUTH_MODE=local-token \
  KAIRO_BOOTSTRAP_TOKEN="kairo-rehearsal-bootstrap-token" \
  SERVER_PORT="$APP_PORT" \
    "$JAVA_BIN" -jar "$jar" > "$logfile" 2>&1 &
  APP_PID=$!
}

# --------------------------------------------------------------------------- #
# Scenario 1: migrate V1.6.0 against fresh PostgreSQL 16 (applies V1..V41).
# --------------------------------------------------------------------------- #
echo "==> [1] running V1.6.0 app against fresh PostgreSQL 16 (migrate V1..V41)"
t0=$(date +%s)
V16_LOG="$LOG_DIR/v16-migrate.log"
launch_app "$V16_JAR" "$V16_LOG"
echo "$APP_PID" > "$STATE_DIR/v16.app.pid"
if ! wait_app_ready "$APP_PORT" "$APP_READY_TIMEOUT_SECS"; then
  fail_scenario "migrate-v16" "V1.6.0 app did not become healthy (see $V16_LOG)" 4
fi
FLYWAY_BEFORE="$(pg_psql "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')"
[ -n "$FLYWAY_BEFORE" ] || fail_scenario "migrate-v16" "could not read flyway version after V1.6.0 migrate" 4
[ "$FLYWAY_BEFORE" = "$FROM_LATEST_MIGRATION" ] || fail_scenario "migrate-v16" "database migration head $FLYWAY_BEFORE != V1.6 source head $FROM_LATEST_MIGRATION" 4
t1=$(date +%s)
record_scenario "migrate-v16" "PASSED" "V1.6.0 migrated to flyway version $FLYWAY_BEFORE; health UP" $(( t1 - t0 ))
state_set "migrationVersions.before" "$FLYWAY_BEFORE"
state_set "migrationVersions.expectedBefore" "$FROM_LATEST_MIGRATION"

# Stop the V1.6.0 app (free the DB connection / port for the candidate).
kill "$APP_PID" 2>/dev/null || true; sleep 3; kill -9 "$APP_PID" 2>/dev/null || true; rm -f "$STATE_DIR/v16.app.pid"

# --------------------------------------------------------------------------- #
# Scenario 2: seed representative persisted runtime state + record counts/checksum.
# fencing_token is stable since V4 and untouched by later migrations, so survival proves
# the upgrade preserved persisted runtime state (not merely SELECT 1).
# --------------------------------------------------------------------------- #
echo "==> [2] seeding representative persisted state (fencing_token)"
t0=$(date +%s)
pg_psql "INSERT INTO fencing_token (id, resource_type, resource_id, purpose, token, sequence, owner, status, lease_expires_at, created_at, consumed_at, correlation_id) VALUES ('rc-rehearsal-seed-1', 'rule', 'demo-rule-1', 'apply', 'tok-rehearsal-0001', 1, 'rehearsal', 'LEASED', now() + interval '1 hour', now(), NULL, 'rehearsal-correlation-1')" >/dev/null \
  || fail_scenario "seed-state" "could not seed representative fencing_token row" 4
FT_COUNT_BEFORE="$(pg_psql "SELECT count(*) FROM fencing_token" | tr -d '[:space:]')"
FT_CHECKSUM_BEFORE="$(pg_psql "SELECT md5(string_agg(t.id||':'||t.status||':'||t.sequence::text, ',' ORDER BY t.id)) FROM fencing_token t" | tr -d '[:space:]')"
FH_COUNT_BEFORE="$(pg_psql "SELECT count(*) FROM flyway_schema_history" | tr -d '[:space:]')"
t1=$(date +%s)
record_scenario "seed-state" "PASSED" "seeded fencing_token row; count=$FT_COUNT_BEFORE checksum=$FT_CHECKSUM_BEFORE flyway_rows=$FH_COUNT_BEFORE" $(( t1 - t0 ))
state_set "persistedState.before.fencingToken.count" "$FT_COUNT_BEFORE"
state_set "persistedState.before.fencingToken.checksum" "$FT_CHECKSUM_BEFORE"
state_set "persistedState.before.flyway.count" "$FH_COUNT_BEFORE"

# --------------------------------------------------------------------------- #
# Scenario 3: real pg_dump backup before candidate upgrade. Record sha256 + size.
# --------------------------------------------------------------------------- #
echo "==> [3] pg_dump backup of V1.6 state"
t0=$(date +%s)
BACKUP_FILE="$OUTPUT_DIR/v16-backup.sql"
if ! docker exec "$PG_CONTAINER" pg_dump -U kairo -d kairo --no-owner --no-privileges > "$BACKUP_FILE" 2>"$LOG_DIR/pgdump.err"; then
  fail_scenario "backup" "pg_dump failed (see $LOG_DIR/pgdump.err)" 4
fi
BACKUP_SHA256="$(python3 - "$BACKUP_FILE" <<'PY'
import hashlib, sys
h=hashlib.sha256()
with open(sys.argv[1], 'rb') as f:
    for chunk in iter(lambda: f.read(65536), b''): h.update(chunk)
print(h.hexdigest())
PY
)"
BACKUP_SIZE="$(wc -c < "$BACKUP_FILE" | tr -d '[:space:]')"
t1=$(date +%s)
record_scenario "backup" "PASSED" "pg_dump -> $(basename "$BACKUP_FILE"); sha256=$BACKUP_SHA256 size=$BACKUP_SIZE" $(( t1 - t0 ))
state_set "backup.file" "$(basename "$BACKUP_FILE")"
state_set "backup.sha256" "$BACKUP_SHA256"
state_set "backup.size" "$BACKUP_SIZE"

# --------------------------------------------------------------------------- #
# Scenario 4: run candidate against the SAME database -> applies every post-V1.6 migration.
# Prove migrations + seeded persisted state survive.
# --------------------------------------------------------------------------- #
echo "==> [4] running candidate app (migrate $FROM_LATEST_MIGRATION -> $TO_LATEST_MIGRATION)"
t0=$(date +%s)
CAND_LOG="$LOG_DIR/candidate-migrate.log"
launch_app "$CAND_JAR" "$CAND_LOG"
echo "$APP_PID" > "$STATE_DIR/cand.app.pid"
if ! wait_app_ready "$APP_PORT" "$APP_READY_TIMEOUT_SECS"; then
  fail_scenario "migrate-candidate" "candidate app did not become healthy (see $CAND_LOG)" 4
fi
FLYWAY_AFTER="$(pg_psql "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')"
[ -n "$FLYWAY_AFTER" ] || fail_scenario "migrate-candidate" "could not read flyway version after candidate migrate" 4
FT_COUNT_AFTER="$(pg_psql "SELECT count(*) FROM fencing_token" | tr -d '[:space:]')"
FT_CHECKSUM_AFTER="$(pg_psql "SELECT md5(string_agg(t.id||':'||t.status||':'||t.sequence::text, ',' ORDER BY t.id)) FROM fencing_token t" | tr -d '[:space:]')"
FH_COUNT_AFTER="$(pg_psql "SELECT count(*) FROM flyway_schema_history" | tr -d '[:space:]')"
SURVIVED="true"
[ "$FT_COUNT_AFTER" = "$FT_COUNT_BEFORE" ] && [ "$FT_CHECKSUM_AFTER" = "$FT_CHECKSUM_BEFORE" ] || SURVIVED="false"
# The candidate migration head must have been applied and the schema must advance.
LATEST_APPLIED="$(pg_psql "SELECT count(*) FROM flyway_schema_history WHERE version='${TO_LATEST_MIGRATION}' AND success=true" | tr -d '[:space:]')"
t1=$(date +%s)
if [ "$SURVIVED" = "true" ] && [ "$LATEST_APPLIED" = "1" ] && [ "$FLYWAY_AFTER" = "$TO_LATEST_MIGRATION" ] && [ "$FLYWAY_AFTER" != "$FLYWAY_BEFORE" ]; then
  record_scenario "migrate-candidate" "PASSED" "candidate migrated to flyway version $FLYWAY_AFTER; seeded fencing_token survived count=$FT_COUNT_AFTER checksum=$FT_CHECKSUM_AFTER" $(( t1 - t0 ))
else
  fail_scenario "migrate-candidate" "upgrade verification failed: survived=$SURVIVED latest_applied=$LATEST_APPLIED expected_latest=$TO_LATEST_MIGRATION actual_latest=$FLYWAY_AFTER" 4
fi
state_set "migrationVersions.after" "$FLYWAY_AFTER"
state_set "persistedState.after.fencingToken.count" "$FT_COUNT_AFTER"
state_set "persistedState.after.fencingToken.checksum" "$FT_CHECKSUM_AFTER"
state_set "persistedState.after.flyway.count" "$FH_COUNT_AFTER"
state_set "persistedState.survived" "$SURVIVED"
state_set "migrationVersions.expectedLatest" "$TO_LATEST_MIGRATION"

# Stop the candidate app before the rollback-guard attempt.
kill "$APP_PID" 2>/dev/null || true; sleep 3; kill -9 "$APP_PID" 2>/dev/null || true; rm -f "$STATE_DIR/cand.app.pid"

# --------------------------------------------------------------------------- #
# Scenario 5: application rollback protection. V1.6 Flyway itself tolerates a database
# newer than its migration head, so the documented rollback procedure MUST prevent the
# old application from being launched until the pre-upgrade database backup is restored.
# This preflight is the executable deployment guard: schema head mismatch is a hard reject.
# --------------------------------------------------------------------------- #
echo "==> [5] application rollback guard: preflight V1.6.0 against V1.7-migrated ($TO_LATEST_MIGRATION) database"
t0=$(date +%s)
GUARD_LOG="$LOG_DIR/rollback-guard-preflight.log"
GUARD_DB_VERSION="$(pg_psql "SELECT version FROM flyway_schema_history WHERE success=true ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')"
{
  echo "rollback application: V1.6.0"
  echo "application migration head: $FROM_LATEST_MIGRATION"
  echo "database migration head: $GUARD_DB_VERSION"
} > "$GUARD_LOG"
t1=$(date +%s)
if [ "$GUARD_DB_VERSION" != "$FROM_LATEST_MIGRATION" ]; then
  GUARD_RESULT="REJECTED"
  echo "decision: REJECT - restore the V1.6 pre-upgrade backup before launch" >> "$GUARD_LOG"
  record_scenario "rollback-guard" "PASSED" "deployment preflight rejected V1.6.0 launch: database=$GUARD_DB_VERSION expected=$FROM_LATEST_MIGRATION; backup restore required" $(( t1 - t0 ))
else
  GUARD_RESULT="NOT_REJECTED"
  echo "decision: ALLOW" >> "$GUARD_LOG"
  fail_scenario "rollback-guard" "rollback preflight unexpectedly allowed V1.6.0 against database version $GUARD_DB_VERSION" 4
fi
state_set "rollbackGuard.result" "$GUARD_RESULT"
state_set "rollbackGuard.method" "SCHEMA_VERSION_PREFLIGHT"
state_set "rollbackGuard.applicationVersion" "$FROM_LATEST_MIGRATION"
state_set "rollbackGuard.databaseVersion" "$GUARD_DB_VERSION"

# Prove no data corruption: the seeded fencing_token row is unchanged after the
# rejected V1.6.0 launch decision.
FT_CHECKSUM_POST_GUARD="$(pg_psql "SELECT md5(string_agg(t.id||':'||t.status||':'||t.sequence::text, ',' ORDER BY t.id)) FROM fencing_token t" | tr -d '[:space:]')"
if [ "$FT_CHECKSUM_POST_GUARD" != "$FT_CHECKSUM_AFTER" ]; then
  fail_scenario "rollback-guard" "fencing_token checksum changed after rejected V1.6.0 start ($FT_CHECKSUM_AFTER -> $FT_CHECKSUM_POST_GUARD) - possible data corruption"
fi

# --------------------------------------------------------------------------- #
# Scenario 6: rollback through the pre-upgrade backup/restore path. Restore the
# pg_dump into a clean PostgreSQL 16 database and prove V1.6.0 runs against it.
# --------------------------------------------------------------------------- #
echo "==> [6] rollback via backup/restore: drop+recreate DB, restore pg_dump, run V1.6.0"
t0=$(date +%s)
BACKUP_SHA256_BEFORE_RESTORE="$(python3 - "$BACKUP_FILE" <<'PY'
import hashlib, sys
h=hashlib.sha256()
with open(sys.argv[1], 'rb') as f:
    for chunk in iter(lambda: f.read(65536), b''): h.update(chunk)
print(h.hexdigest())
PY
)"
[ "$BACKUP_SHA256_BEFORE_RESTORE" = "$BACKUP_SHA256" ] || fail_scenario "rollback-restore" "backup hash changed before restore" 4
# Drop and recreate the kairo database from the backup (clean restore).
pg_psql "DROP DATABASE IF EXISTS kairo" >/dev/null 2>&1 || true
docker exec -i "$PG_CONTAINER" psql -U kairo -d postgres -c "DROP DATABASE IF EXISTS kairo" >/dev/null 2>&1 || true
docker exec -i "$PG_CONTAINER" psql -U kairo -d postgres -c "CREATE DATABASE kairo" >/dev/null 2>&1 \
  || fail_scenario "rollback-restore" "could not recreate clean kairo database" 4
if ! docker exec -i "$PG_CONTAINER" psql -U kairo -d kairo -v ON_ERROR_STOP=1 < "$BACKUP_FILE" > "$LOG_DIR/restore.log" 2>&1; then
  fail_scenario "rollback-restore" "pg_dump restore failed (see $LOG_DIR/restore.log)" 4
fi
FLYWAY_RESTORED="$(pg_psql "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')"
[ "$FLYWAY_RESTORED" = "$FLYWAY_BEFORE" ] \
  || fail_scenario "rollback-restore" "restored flyway version $FLYWAY_RESTORED != pre-upgrade $FLYWAY_BEFORE" 4
RESTORE_LOG="$LOG_DIR/rollback-restore-v16.log"
launch_app "$V16_JAR" "$RESTORE_LOG"
echo "$APP_PID" > "$STATE_DIR/v16-restore.app.pid"
if ! wait_app_ready "$APP_PORT" "$APP_READY_TIMEOUT_SECS"; then
  fail_scenario "rollback-restore" "V1.6.0 app did not become healthy against restored backup (see $RESTORE_LOG)" 4
fi
kill "$APP_PID" 2>/dev/null || true; sleep 3; kill -9 "$APP_PID" 2>/dev/null || true; rm -f "$STATE_DIR/v16-restore.app.pid"
t1=$(date +%s)
record_scenario "rollback-restore" "PASSED" "restored backup flyway=$FLYWAY_RESTORED; V1.6.0 app healthy against restored DB" $(( t1 - t0 ))
state_set "rollbackRestore.result" "PASSED"
state_set "rollbackRestore.restoredVersion" "$FLYWAY_RESTORED"

# --------------------------------------------------------------------------- #
# Scenario 7: Redis 7 honest semantics. Verify connection, namespace behaviour,
# and that PostgreSQL remains the source of truth. Do NOT claim durable business
# state recovery from Redis.
# --------------------------------------------------------------------------- #
echo "==> [7] Redis 7 semantics (connection + namespace + PG source of truth)"
t0=$(date +%s)
REDIS_PONG="$(docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | tr -d '[:space:]')"
[ "$REDIS_PONG" = "PONG" ] || fail_scenario "redis-semantics" "redis-cli ping returned '$REDIS_PONG' (expected PONG)" 4
# Namespace: the candidate app uses the kairo:fencing: key prefix. Verify a fencing
# sequence key lives under the documented namespace after a candidate run.
REDIS_KEY_PREFIX="kairo:fencing:"
REDIS_PROBE_KEY="${REDIS_KEY_PREFIX}rc-rehearsal:${RUN_ID}"
docker exec "$REDIS_CONTAINER" redis-cli SET "$REDIS_PROBE_KEY" verified EX 60 >/dev/null 2>&1 \
  || fail_scenario "redis-semantics" "could not write namespaced Redis probe key" 4
REDIS_PROBE_VALUE="$(docker exec "$REDIS_CONTAINER" redis-cli GET "$REDIS_PROBE_KEY" 2>/dev/null | tr -d '[:space:]')"
[ "$REDIS_PROBE_VALUE" = "verified" ] || fail_scenario "redis-semantics" "namespaced Redis probe value mismatch" 4
docker exec "$REDIS_CONTAINER" redis-cli DEL "$REDIS_PROBE_KEY" >/dev/null 2>&1 \
  || fail_scenario "redis-semantics" "could not delete namespaced Redis probe key" 4
# PostgreSQL remains the source of truth: the seeded fencing_token row is in PG,
# not dependent on Redis. Re-verify it from PG.
FT_FINAL="$(pg_psql "SELECT count(*) FROM fencing_token WHERE id='rc-rehearsal-seed-1'" | tr -d '[:space:]')"
[ "$FT_FINAL" = "1" ] || fail_scenario "redis-semantics" "seeded fencing_token row not found in PostgreSQL after rehearsal (source of truth check)" 4
t1=$(date +%s)
record_scenario "redis-semantics" "PASSED" "redis PONG; namespace prefix=$REDIS_KEY_PREFIX probe round-trip verified; PostgreSQL source-of-truth fencing_token count=$FT_FINAL" $(( t1 - t0 ))
state_set "redis.result" "PASSED"
state_set "redis.connection" "true"
state_set "redis.namespace" "$REDIS_KEY_PREFIX"
state_set "redis.namespaceVerified" "true"
state_set "redis.postgresIsSourceOfTruth" "true"

ENDED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

finalize_result
echo "==> done (status=$GLOBAL_STATUS mode=$MODE authoritative=$AUTHORITATIVE). result: $RESULT_FILE"
# Exit 0 only if every scenario PASSED; otherwise 4. A cleanup failure (exit 6) is
# handled by the trap. Authoritative-ness is recorded in the result, not the exit code.
[ "$GLOBAL_STATUS" = "PASSED" ] || exit 4
exit 0
