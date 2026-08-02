#!/usr/bin/env bash
#
# V1.7 M4-D canonical-runbook verifier. It validates local links and the structure of explicitly
# marked command blocks. Only AUTOMATED-SAFE blocks are executed; OPERATOR-CONFIRMED blocks are
# never executed. The automated grammar is deliberately small and fail-closed:
#
#   bash|sh -n <repository .sh files>
#   python3 -m json.tool <repository .json file>
#   mvn ... test ... -Dtest=<focused pattern> (only the documented safe options)
#   git diff --check
#
# Commands are executed without a shell after validation, so redirection, expansion, chaining and
# command substitution cannot acquire meaning even if a parser defect is introduced later.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEFAULT_DOC="$REPO_ROOT/docs/ops/v1.7-lts-runbook.md"

usage() {
  cat <<'EOF'
Usage: verify-runbooks.sh [--doc <runbook.md>] [--self-test] [--help]

  (default)   Validate docs/ops/v1.7-lts-runbook.md and execute its AUTOMATED-SAFE blocks.
  --doc       Validate a different runbook.
  --self-test Run deterministic positive and negative verifier tests.
  --help      Show this help.

Exit codes: 0 ok | 1 usage/self-test | 2 validation | 3 automated command failed.
EOF
}

parse_records() {
  local doc="$1"
  python3 - "$doc" "$REPO_ROOT" <<'PY'
import os
import re
import shlex
import sys

doc_path = os.path.abspath(sys.argv[1])
repo_root = os.path.realpath(sys.argv[2])
doc_dir = os.path.dirname(doc_path)
with open(doc_path, encoding="utf-8") as fh:
    text = fh.read()

OPEN_RE = re.compile(r'^\s*<!--\s*(AUTOMATED-SAFE|OPERATOR-CONFIRMED)\s+id="([A-Za-z0-9][A-Za-z0-9-]*)"\s*-->\s*$')
CLOSE_RE = re.compile(r'^\s*<!--\s*/(AUTOMATED-SAFE|OPERATOR-CONFIRMED)\s*-->\s*$')
LOOSE_RE = re.compile(r'^\s*<!--\s*/?(?:AUTOMATED-SAFE|OPERATOR-CONFIRMED)\b')
FENCE_RE = re.compile(r'```(?:bash|sh)\s*\n(.*?)```', re.DOTALL)

def emit(*parts):
    print("\t".join(str(p).replace("\t", " ").replace("\n", " ") for p in parts))

def repo_file(token, suffix):
    if not token or token.startswith("-"):
        return False
    path = token if os.path.isabs(token) else os.path.join(repo_root, token)
    real = os.path.realpath(path)
    return (real == repo_root or real.startswith(repo_root + os.sep)) and real.endswith(suffix) and os.path.isfile(real)

def grammar(line):
    try:
        argv = shlex.split(line, posix=True)
    except ValueError as exc:
        return "malformed quoting: %s" % exc
    if not argv:
        return "empty command"

    # Even though execution is shell=False, keep the source representation unambiguous and easy to audit.
    if any(c in line for c in ("\n", "\r", "\0", "`", "$", ";", "|", ">", "<", "&", "\\")):
        return "shell metacharacter not allowed"
    if '"' in line:
        return "double quotes not allowed"

    cmd = argv[0]
    if cmd in ("bash", "sh"):
        if len(argv) < 3 or argv[1] != "-n":
            return "%s is restricted to -n syntax checks" % cmd
        if not all(repo_file(p, ".sh") for p in argv[2:]):
            return "syntax-check paths must be existing repository .sh files"
        return "OK"

    if cmd == "python3":
        if len(argv) != 4 or argv[1:3] != ["-m", "json.tool"]:
            return "python3 is restricted to -m json.tool <repository-json>"
        if not repo_file(argv[3], ".json"):
            return "json path must be an existing repository .json file"
        return "OK"

    if cmd == "git":
        return "OK" if argv == ["git", "diff", "--check"] else "git is restricted to diff --check"

    if cmd == "mvn":
        allowed_flags = {"-B", "-ntp", "-am"}
        goals = []
        saw_test_filter = False
        i = 1
        while i < len(argv):
            arg = argv[i]
            if arg in allowed_flags:
                i += 1
            elif arg == "-pl":
                if i + 1 >= len(argv):
                    return "-pl requires a module list"
                modules = argv[i + 1].split(",")
                if not modules or any(not re.fullmatch(r"[A-Za-z0-9_.-]+", m) for m in modules):
                    return "invalid -pl module list"
                if any(not os.path.isfile(os.path.join(repo_root, m, "pom.xml")) for m in modules):
                    return "-pl references a non-module"
                i += 2
            elif arg == "test":
                goals.append(arg)
                i += 1
            elif arg.startswith("-Dtest=") and len(arg) > len("-Dtest="):
                saw_test_filter = True
                i += 1
            elif arg == "-Dsurefire.failIfNoSpecifiedTests=false":
                i += 1
            else:
                return "Maven argument/goal not allowed: %s" % arg
        if goals != ["test"] or not saw_test_filter:
            return "Maven command must contain exactly one focused test goal"
        return "OK"

    return "command not allowed: %s" % cmd

lines = text.splitlines()
seen_ids = {}
automated_blocks = 0
i = 0
while i < len(lines):
    strict = OPEN_RE.match(lines[i])
    if strict:
        kind, marker_id = strict.groups()
        close = re.compile(r'^\s*<!--\s*/' + re.escape(kind) + r'\s*-->\s*$')
        j = i + 1
        while j < len(lines) and not close.match(lines[j]):
            if OPEN_RE.match(lines[j]) or CLOSE_RE.match(lines[j]):
                break
            j += 1
        if j >= len(lines) or not close.match(lines[j]):
            emit("ERR", 'unclosed %s marker id="%s"' % (kind, marker_id))
            i += 1
            continue
        if marker_id in seen_ids:
            emit("ERR", 'duplicate marker id="%s"' % marker_id)
        else:
            seen_ids[marker_id] = kind
        region = "\n".join(lines[i + 1:j])
        fences = list(FENCE_RE.finditer(region))
        if len(fences) != 1:
            emit("ERR", 'marker id="%s" must contain exactly one bash/sh fence' % marker_id)
            commands = []
        else:
            commands = [s.strip() for s in fences[0].group(1).splitlines()
                        if s.strip() and not s.strip().startswith("#")]
            if not commands:
                emit("ERR", 'marker id="%s" code fence is empty' % marker_id)
        if kind == "AUTOMATED-SAFE":
            automated_blocks += 1
            for command in commands:
                verdict = grammar(command)
                emit("AUTOCMD" if verdict == "OK" else "REJECT", marker_id, command, verdict)
        elif commands:
            emit("OPHAS", marker_id)
        i = j + 1
        continue
    if CLOSE_RE.match(lines[i]):
        emit("ERR", "close marker without open: %s" % lines[i].strip())
    elif LOOSE_RE.match(lines[i]):
        emit("ERR", "malformed marker: %s" % lines[i].strip())
    i += 1

if automated_blocks == 0:
    emit("ERR", "runbook has no AUTOMATED-SAFE block")

# Ignore links inside code blocks/spans. Local link existence is validation-only; it is never executed.
stripped = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
stripped = re.sub(r"`[^`]*`", "", stripped)
targets = [m.group(2) for m in re.finditer(r'\[([^\]]*)\]\(([^)\s]*)(?:\s+"[^"]*")?\)', stripped)]
targets += [m.group(2) for m in re.finditer(r'^\s*\[([^\]]+)\]:\s*(\S+)', stripped, re.MULTILINE)]
seen_targets = set()
for target in targets:
    if not target or target in seen_targets:
        continue
    seen_targets.add(target)
    if target.startswith(("http://", "https://", "mailto:", "ftp://", "#")):
        continue
    target = target.split("#", 1)[0].split("?", 1)[0]
    if target:
        emit("LINK", target)
PY
}

execute_argv() {
  local command="$1"
  AUTOCMD="$command" REPO_ROOT="$REPO_ROOT" python3 <<'PY'
import os
import shlex
import subprocess
import sys

argv = shlex.split(os.environ["AUTOCMD"], posix=True)
completed = subprocess.run(argv, cwd=os.environ["REPO_ROOT"], shell=False)
sys.exit(completed.returncode)
PY
}

validate_doc() {
  local doc="$1"
  [[ -f "$doc" ]] || { echo "error: runbook not found: $doc" >&2; return 1; }
  local doc_dir records rec kind rest id cmd target
  doc_dir="$(cd "$(dirname "$doc")" && pwd)"
  records="$(parse_records "$doc")"
  local -a errors=() commands=() links=()
  while IFS= read -r rec; do
    [[ -z "$rec" ]] && continue
    kind="${rec%%$'\t'*}"; rest="${rec#*$'\t'}"
    case "$kind" in
      ERR) errors+=("structure: $rest") ;;
      REJECT) errors+=("forbidden command: $rest") ;;
      AUTOCMD)
        id="${rest%%$'\t'*}"; rest="${rest#*$'\t'}"; cmd="${rest%%$'\t'*}"
        commands+=("${id}"$'\x1f'"${cmd}") ;;
      LINK) links+=("$rest") ;;
      OPHAS) ;;
      *) errors+=("internal: unknown record: $rec") ;;
    esac
  done <<< "$records"

  for target in "${links[@]+"${links[@]}"}"; do
    if [[ "$target" == /* ]]; then
      [[ -e "$target" ]] || errors+=("broken link: $target")
    elif [[ ! -e "$doc_dir/$target" && ! -e "$REPO_ROOT/$target" ]]; then
      errors+=("broken link: $target (resolved from $doc_dir)")
    fi
  done
  if ((${#errors[@]})); then
    printf '%s\n' "${errors[@]}" >&2
    echo "error: validation failed for $doc (${#errors[@]} problem(s))" >&2
    return 2
  fi

  local item output code
  for item in "${commands[@]+"${commands[@]}"}"; do
    id="${item%%$'\x1f'*}"; cmd="${item#*$'\x1f'}"
    echo "  [AUTOMATED-SAFE $id] $cmd"
    output="$(mktemp -t kairo-runbook-command-XXXXXX)"; code=0
    execute_argv "$cmd" >"$output" 2>&1 || code=$?
    if [[ "$code" -ne 0 ]]; then
      echo "error: AUTOMATED-SAFE '$id' failed (exit $code): $cmd" >&2
      cat "$output" >&2; rm -f "$output"; return 3
    fi
    rm -f "$output"
  done
  echo "verified $doc: ${#commands[@]} automated command(s), ${#links[@]} local link(s)"
}

run_self_tests() {
  mkdir -p "$REPO_ROOT/target/v1.7"
  local tmpdir; tmpdir="$(mktemp -d "$REPO_ROOT/target/v1.7/runbook-self-test.XXXXXX")"
  trap 'rm -rf "$tmpdir"' RETURN
  local pass=0 fail=0
  run_case() {
    local name="$1" expected="$2" doc="$3" actual=0
    validate_doc "$doc" >/dev/null 2>&1 || actual=$?
    if [[ "$actual" -eq "$expected" ]]; then pass=$((pass + 1));
    else fail=$((fail + 1)); echo "  FAIL: $name (expected $expected, got $actual)" >&2; fi
  }
  local rel="${tmpdir#$REPO_ROOT/}"
  printf '#!/usr/bin/env bash\necho ok\n' >"$tmpdir/ok.sh"
  printf '{"ok":true}\n' >"$tmpdir/ok.json"
  printf 'link\n' >"$tmpdir/link.txt"

  cat >"$tmpdir/positive.md" <<EOF
<!-- AUTOMATED-SAFE id="syntax" -->
\`\`\`bash
bash -n $rel/ok.sh
\`\`\`
<!-- /AUTOMATED-SAFE -->
<!-- AUTOMATED-SAFE id="json" -->
\`\`\`bash
python3 -m json.tool $rel/ok.json
\`\`\`
<!-- /AUTOMATED-SAFE -->
[link](./link.txt)
EOF
  run_case positive 0 "$tmpdir/positive.md"

  make_bad() {
    local file="$1" command="$2"
    cat >"$tmpdir/$file.md" <<EOF
<!-- AUTOMATED-SAFE id="$file" -->
\`\`\`bash
$command
\`\`\`
<!-- /AUTOMATED-SAFE -->
EOF
  }
  make_bad arbitrary-bash "bash $rel/ok.sh"; run_case arbitrary-bash 2 "$tmpdir/arbitrary-bash.md"
  make_bad arbitrary-python "python3 $rel/evil.py"; run_case arbitrary-python 2 "$tmpdir/arbitrary-python.md"
  make_bad external-read "python3 -m json.tool /tmp/not-repository.json"; run_case external-read 2 "$tmpdir/external-read.md"
  make_bad maven-clean "mvn clean"; run_case maven-clean 2 "$tmpdir/maven-clean.md"
  make_bad maven-plugin "mvn org.codehaus.mojo:exec-maven-plugin:exec"; run_case maven-plugin 2 "$tmpdir/maven-plugin.md"
  make_bad git-write "git commit -m x"; run_case git-write 2 "$tmpdir/git-write.md"
  make_bad redirection "bash -n $rel/ok.sh > $rel/out"; run_case redirection 2 "$tmpdir/redirection.md"

  cat >"$tmpdir/duplicate.md" <<EOF
<!-- AUTOMATED-SAFE id="same" -->
\`\`\`bash
bash -n $rel/ok.sh
\`\`\`
<!-- /AUTOMATED-SAFE -->
<!-- AUTOMATED-SAFE id="same" -->
\`\`\`bash
bash -n $rel/ok.sh
\`\`\`
<!-- /AUTOMATED-SAFE -->
EOF
  run_case duplicate-id 2 "$tmpdir/duplicate.md"

  cat >"$tmpdir/two-fences.md" <<EOF
<!-- AUTOMATED-SAFE id="two" -->
\`\`\`bash
bash -n $rel/ok.sh
\`\`\`
\`\`\`bash
bash -n $rel/ok.sh
\`\`\`
<!-- /AUTOMATED-SAFE -->
EOF
  run_case multiple-fences 2 "$tmpdir/two-fences.md"

  cat >"$tmpdir/unclosed.md" <<EOF
<!-- AUTOMATED-SAFE id="open" -->
\`\`\`bash
bash -n $rel/ok.sh
\`\`\`
EOF
  run_case unclosed 2 "$tmpdir/unclosed.md"

  mkdir "$tmpdir/sentinel"
  cat >"$tmpdir/operator.md" <<EOF
<!-- AUTOMATED-SAFE id="safe" -->
\`\`\`bash
bash -n $rel/ok.sh
\`\`\`
<!-- /AUTOMATED-SAFE -->
<!-- OPERATOR-CONFIRMED id="never-run" -->
\`\`\`bash
rm -rf $rel/sentinel
\`\`\`
<!-- /OPERATOR-CONFIRMED -->
EOF
  run_case operator-not-executed 0 "$tmpdir/operator.md"
  [[ -d "$tmpdir/sentinel" ]] && pass=$((pass + 1)) || { fail=$((fail + 1)); echo "  FAIL: operator block executed" >&2; }

  cat >"$tmpdir/broken-link.md" <<EOF
<!-- AUTOMATED-SAFE id="safe-link" -->
\`\`\`bash
bash -n $rel/ok.sh
\`\`\`
<!-- /AUTOMATED-SAFE -->
[missing](./missing.md)
EOF
  run_case broken-link 2 "$tmpdir/broken-link.md"

  echo "self-test: $pass passed, $fail failed"
  [[ "$fail" -eq 0 ]]
}

DOC=""; SELF_TEST=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --self-test) SELF_TEST=true; shift ;;
    --doc) [[ $# -ge 2 ]] || { echo "error: --doc requires a value" >&2; exit 1; }; DOC="$2"; shift 2 ;;
    *) echo "error: unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done
if [[ "$SELF_TEST" == true ]]; then run_self_tests; exit $?; fi
[[ -n "$DOC" ]] || DOC="$DEFAULT_DOC"
echo "==> verifying runbook: $DOC"
validate_doc "$DOC"
