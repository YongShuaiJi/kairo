#!/bin/zsh
# Switch the one active labelled Kairo soak container between the documented daytime and
# nighttime CPU profiles. Intended for the com.kairo.soak-load-profile LaunchAgent.
set -euo pipefail

DOCKER="${KAIRO_DOCKER_BIN:-/Applications/Docker.app/Contents/Resources/bin/docker}"
DAY_CPUS="${KAIRO_SOAK_DAY_CPUS:-4}"
NIGHT_CPUS="${KAIRO_SOAK_NIGHT_CPUS:-0.25}"
HOUR="$(date +%H)"

containers=("${(@f)$($DOCKER ps \
  --filter 'label=com.example.kairo.soak.profile-managed=true' \
  --format '{{.Names}}')}")
if [[ ${#containers[@]} -eq 1 && -z "${containers[1]}" ]]; then
  containers=()
fi
if [[ ${#containers[@]} -eq 0 ]]; then
  exit 0
fi
if [[ ${#containers[@]} -ne 1 ]]; then
  print -u2 "error: expected one managed soak container, found ${#containers[@]}"
  exit 1
fi

CONTAINER="${containers[1]}"
EVIDENCE="$($DOCKER inspect --format '{{ index .Config.Labels "com.example.kairo.soak.evidence-dir" }}' "$CONTAINER")"
if [[ -z "$EVIDENCE" || "$EVIDENCE" != "$HOME/kairo-m6b/evidence-"* ]]; then
  print -u2 "error: unsafe or missing evidence directory label for $CONTAINER"
  exit 1
fi

if (( 10#$HOUR >= 8 && 10#$HOUR < 20 )); then
  PROFILE=day
  CPUS="$DAY_CPUS"
else
  PROFILE=night
  CPUS="$NIGHT_CPUS"
fi

$DOCKER update --cpus "$CPUS" "$CONTAINER" >/dev/null
mkdir -p "$EVIDENCE"
printf '{"timestamp":"%s","timezone":"Asia/Shanghai","profile":"%s","cpuLimit":%s,"container":"%s"}\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$PROFILE" "$CPUS" "$CONTAINER" \
  >> "$EVIDENCE/load-profile.jsonl"
