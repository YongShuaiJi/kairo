#!/usr/bin/env python3
#
# scripts/v1.7/lib/releaselib.py
#
# V1.7 M5-B (roadmap §12.2 / §12.4 / §12.5) release assembly library. Pure, deterministic and
# fail-closed. It owns the §12.2 inventory contract, deterministic archive assembly (§12.4),
# SHA-256 inventory, honest release-manifest schema (§12.5), Docker image metadata parsing and
# secret scanning. It never publishes, pushes, signs, generates an SBOM, fabricates evidence,
# support dates, a source tag or an approval.
#
# build-release.sh drives the build steps (Maven / Web / Docker) and calls into this library for
# every deterministic / validation concern. The Java release-assembly self-test
# (com.example.kairo.release.ReleaseAssemblySelfTest) exercises self_test() so the assembly
# contract is part of the mvn test gate.
#
# Honest status vocabulary (roadmap §5.1 / §12.3 / §12.5):
#   NOT_RUN        gate not executed for this release candidate
#   NOT_AVAILABLE  work owned by a later milestone (M5-C/M5-D/M6), no value exists yet
#   PENDING        produced later in the same pipeline, not yet available at this step
#   SKIPPED        explicitly allowed to be absent in a local/RC context (e.g. cosign w/o OIDC)
# These may never be promoted to PASSED/VERIFIED/SIGNED by this library.

import argparse
import gzip
import hashlib
import io
import json
import os
import re
import sys
import tarfile

# --- §12.1 release version contract --------------------------------------------------------
# Dev default 1.7.0-SNAPSHOT is explicitly NOT a release version. A release version is the final
# 1.7.0 or an RC 1.7.0-rc.N (N >= 1). RC numbers start at 1; 1.7.0-rc.0 is rejected.
VERSION_RE = re.compile(r'^1\.7\.0(-rc\.[1-9][0-9]*)?$')
SNAPSHOT_RE = re.compile(r'-SNAPSHOT$', re.IGNORECASE)

GIT_COMMIT_RE = re.compile(r'^[0-9a-f]{40}$')
SHA256_RE = re.compile(r'^[0-9a-f]{64}$')

CONTRACT_BASELINE = "V1.6.0"
SCHEMA_VERSION = "1.0"

# Honest statuses that may be promoted only by the milestone that owns the work.
HONEST_STATUSES = {"PASSED", "FAILED", "SKIPPED", "NOT_RUN", "EXPERIMENTAL"}
NON_EVIDENCE_STATUSES = {"NOT_AVAILABLE", "PENDING"}  # may stand in for owned-by-later work

# SBOM / signature / provenance may only carry one of these for an M5-B local RC build. A real
# "SIGNED" / "VERIFIED" / "PRESENT" value would be a fabrication.
SUPPLY_STATUSES = {"NOT_AVAILABLE", "PENDING", "SKIPPED"}

# Substrings that must never appear in staged release content (§12.2 "no development Token" /
# §12.6 exclusion of demo/fixtures/credentials). Matched as raw byte substrings.
SECRET_PATTERNS = [
    "kairo-dev-admin-token-change-me",
    "kairo-compose-session-key-change-me",
    "kairo-dev-admin-token",
    "kairo-compose-session-key",
    "-----BEGIN RSA PRIVATE KEY-----",
    "-----BEGIN EC PRIVATE KEY-----",
    "-----BEGIN OPENSSH PRIVATE KEY-----",
    "-----BEGIN PRIVATE KEY-----",
    "-----BEGIN ENCRYPTED PRIVATE KEY-----",
    "ghp_",      # GitHub personal access token
    "gho_",      # GitHub OAuth token
    "glpat-",    # GitLab personal access token
    "xoxb-",     # Slack bot token
    "AKIA",      # AWS access key id prefix
]

# Artifact names that are explicitly NOT §12.2 release artifacts and must never appear in the
# inventory or inside a bundle.
FORBIDDEN_NAME_SUBSTRINGS = [
    "kairo-demo",
    "kairo-sidecar",
    "node_modules",
    ".env",
    "kairo-dev-admin-token",
    "kairo-compose-session-key",
    "dependency-reduced-pom",
    "original-kairo",   # shade original-* leftovers
]


def validate_version(version):
    """Return a list of human-readable errors for a candidate release version (empty == valid)."""
    errors = []
    if not isinstance(version, str) or not version:
        errors.append("version must be a non-blank string")
        return errors
    if SNAPSHOT_RE.search(version):
        errors.append("release version must not contain -SNAPSHOT (dev default); got: %s" % version)
    if not VERSION_RE.match(version):
        errors.append(
            "release version must match 1.7.0 or 1.7.0-rc.N (N>=1); got: %s" % version)
    return errors


def is_release_version(version):
    return not validate_version(version)


def _check_arcname(arc):
    if not arc or arc.startswith('/'):
        raise ValueError("absolute or empty archive entry name: %r" % arc)
    parts = arc.split('/')
    if '..' in parts:
        raise ValueError("path-traversal component in archive entry name: %r" % arc)
    if any(p == '' for p in parts[1:]):
        # tolerate a single trailing slash on directories elsewhere; here it is an error
        raise ValueError("empty path segment in archive entry name: %r" % arc)


def _norm_arc(top_dir, rel):
    rel = rel.replace(os.sep, '/')
    if not rel:
        return top_dir
    return top_dir + '/' + rel


def make_deterministic_tar(staging_root, top_dir, out_path, epoch):
    """Create a deterministic .tar.gz from the staged directory tree.

    Entries are sorted lexicographically by archive name; uid/gid are 0 with empty owner/group
    names; mtime is fixed to ``epoch``; modes are stable (dirs 0755, .sh 0755, else 0644); gzip is
    written with mtime=0 and no original name. Absolute and path-traversal entry names are rejected.
    """
    epoch = int(epoch)
    entries = []  # (arcname, fspath, is_dir)
    entries.append((top_dir, None, True))
    if not os.path.isdir(staging_root):
        raise ValueError("staging root is not a directory: %s" % staging_root)
    for dirpath, dirnames, filenames in os.walk(staging_root):
        dirnames.sort()
        filenames.sort()
        rel_dir = os.path.relpath(dirpath, staging_root)
        if rel_dir != '.':
            entries.append((_norm_arc(top_dir, rel_dir), dirpath, True))
        for fn in filenames:
            fp = os.path.join(dirpath, fn)
            rel = os.path.join(rel_dir, fn) if rel_dir != '.' else fn
            entries.append((_norm_arc(top_dir, rel), fp, False))
    entries.sort(key=lambda e: e[0])

    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w', format=tarfile.GNU_FORMAT) as tf:
        for arc, fp, is_dir in entries:
            _check_arcname(arc)
            ti = tarfile.TarInfo(arc)
            ti.mtime = epoch
            ti.uid = 0
            ti.gid = 0
            ti.uname = ''
            ti.gname = ''
            if is_dir:
                ti.type = tarfile.DIRTYPE
                ti.mode = 0o755
                ti.size = 0
                tf.addfile(ti)
            else:
                ti.type = tarfile.REGTYPE
                ti.mode = 0o755 if arc.endswith('.sh') else 0o644
                with open(fp, 'rb') as f:
                    data = f.read()
                ti.size = len(data)
                tf.addfile(ti, io.BytesIO(data))
    blob = gzip.compress(buf.getvalue(), compresslevel=9, mtime=0)
    with open(out_path, 'wb') as f:
        f.write(blob)
    return len(entries)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def scan_secrets(paths):
    """Return a list of (path, pattern, byte_offset) findings for known secret/dev-token patterns."""
    findings = []
    for p in paths:
        try:
            with open(p, 'rb') as f:
                data = f.read()
        except OSError as e:
            findings.append((p, 'unreadable', str(e)))
            continue
        for pat in SECRET_PATTERNS:
            pb = pat.encode('utf-8', 'surrogateescape')
            idx = data.find(pb)
            if idx >= 0:
                findings.append((p, pat, idx))
    return findings


def write_sha256sums(entries, out_path):
    """Write a SHA256SUMS file covering each (name, path) exactly once in stable lexical order.

    ``entries`` is a list of (name, path) tuples where ``name`` is the output-root-relative
    artifact name and ``path`` is the absolute filesystem path. SHA256SUMS never hashes itself or
    the mutable manifest; the caller must not include them.
    """
    seen = set()
    rows = []
    for name, path in sorted(entries, key=lambda e: e[0]):
        if name in seen:
            raise ValueError("duplicate SHA256SUMS entry: %s" % name)
        if name in ('SHA256SUMS', 'release-manifest.json'):
            raise ValueError("SHA256SUMS must not hash itself or the manifest: %s" % name)
        seen.add(name)
        rows.append("%s  %s\n" % (sha256_file(path), name))
    with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
        f.writelines(rows)
    return [name for name, _ in sorted(entries, key=lambda e: e[0])]


def parse_docker_inspect(obj):
    """Normalize a parsed ``docker image inspect`` payload into truthful local-image metadata.

    Local images have no registry RepoDigest and no distributable manifest digest; those fields
    are recorded as empty / NOT_AVAILABLE rather than invented.
    """
    if isinstance(obj, list):
        obj = obj[0] if obj else {}
    if not isinstance(obj, dict):
        raise ValueError("docker inspect payload must be a JSON object or single-element list")
    image_id = obj.get('Id', '') or ''
    repo_tags = obj.get('RepoTags') or []
    repo_digests = obj.get('RepoDigests') or []
    size = obj.get('Size', 0) or 0
    created = obj.get('Created', '') or ''
    arch = obj.get('Architecture', '') or ''
    os_name = obj.get('Os', '') or ''
    rootfs = obj.get('RootFS') or {}
    layers = rootfs.get('Layers', []) if isinstance(rootfs, dict) else []
    return {
        'imageId': image_id,
        'repoTags': list(repo_tags),
        'repoDigests': list(repo_digests),
        'size': int(size),
        'created': created,
        'architecture': arch,
        'os': os_name,
        'rootfs': {
            'type': rootfs.get('type', 'layers') if isinstance(rootfs, dict) else 'layers',
            'diffIds': list(layers),
        },
        'manifestDigest': 'NOT_AVAILABLE',
    }


def expected_inventory(version):
    """The exact §12.2 release inventory for a version (order is the canonical manifest order)."""
    return [
        {'name': 'kairo-agent-bundle-%s.tar.gz' % version, 'type': 'tar.gz'},
        {'name': 'kairo-platform-server-%s.jar' % version, 'type': 'jar'},
        {'name': 'kairo-cli-%s.jar' % version, 'type': 'jar'},
        {'name': 'kairo-mcp-%s.jar' % version, 'type': 'jar'},
        {'name': 'kairo-sdk-%s.jar' % version, 'type': 'jar'},
        {'name': 'kairo-compose-%s.tar.gz' % version, 'type': 'tar.gz'},
        {'name': 'kairo-platform-server:%s' % version, 'type': 'docker-image'},
        {'name': 'kairo-platform-web:%s' % version, 'type': 'docker-image'},
    ]


def _err(errors, msg):
    errors.append(msg)
    return errors


def validate_inventory(artifacts, version):
    """Validate that ``artifacts`` is exactly the §12.2 inventory for ``version``; return error list."""
    errors = []
    if not isinstance(artifacts, list):
        return _err(errors, "artifacts must be a list")
    expected = expected_inventory(version)
    expected_by_name = {e['name']: e for e in expected}
    seen = set()
    for i, a in enumerate(artifacts):
        if not isinstance(a, dict):
            _err(errors, "artifacts[%d] must be an object" % i)
            continue
        name = a.get('name')
        if not isinstance(name, str) or not name:
            _err(errors, "artifacts[%d].name must be a non-blank string" % i)
            continue
        if name in seen:
            _err(errors, "duplicate artifact: %s" % name)
        seen.add(name)
        for sub in FORBIDDEN_NAME_SUBSTRINGS:
            if sub in name:
                _err(errors, "forbidden artifact name (not §12.2): %s" % name)
        exp = expected_by_name.get(name)
        if exp is None:
            _err(errors, "unexpected artifact (not in §12.2 inventory): %s" % name)
        else:
            if a.get('type') != exp['type']:
                _err(errors, "artifact %s type must be %s, got %s" % (name, exp['type'], a.get('type')))
    if seen != set(expected_by_name.keys()):
        missing = sorted(set(expected_by_name.keys()) - seen)
        _err(errors, "missing §12.2 artifacts: %s" % ', '.join(missing))
    return errors


def _validate_supply_field(obj, field, errors, prefix):
    v = obj.get(field)
    if isinstance(v, dict) and 'status' in v:
        if v['status'] not in SUPPLY_STATUSES:
            _err(errors, "%s.%s.status must be one of %s for an M5-B local RC build; got %s"
                 % (prefix, field, sorted(SUPPLY_STATUSES), v['status']))
    elif isinstance(v, str):
        if v not in SUPPLY_STATUSES:
            _err(errors, "%s.%s must be %s for an M5-B local RC build; got %s"
                 % (prefix, field, sorted(SUPPLY_STATUSES), v))
    else:
        _err(errors, "%s.%s must be a string or {status,...} object" % (prefix, field))


def validate_manifest(obj):
    """Validate a release manifest against §12.5 + honesty; return a list of error strings."""
    errors = []
    if not isinstance(obj, dict):
        return _err(errors, "manifest must be a JSON object")

    required_top = [
        'schemaVersion', 'version', 'gitCommit', 'sourceTag', 'contractBaseline',
        'buildWorkflow', 'buildStartedAt', 'buildEndedAt', 'toolchain', 'artifacts',
        'compatibilityEvidence', 'recoveryEvidence', 'performanceEvidence', 'soakEvidence',
        'knownLimitations', 'ltsStart', 'standardSupportEnd', 'securitySupportEnd',
        'maintainer', 'approval',
    ]
    for k in required_top:
        if k not in obj:
            _err(errors, "missing top-level field: %s" % k)

    if obj.get('schemaVersion') != SCHEMA_VERSION:
        _err(errors, "schemaVersion must be %s" % SCHEMA_VERSION)

    version = obj.get('version')
    errors.extend(validate_version(version))

    git_commit = obj.get('gitCommit')
    if not isinstance(git_commit, str) or not GIT_COMMIT_RE.match(git_commit):
        _err(errors, "gitCommit must be a 40-hex string")

    if obj.get('contractBaseline') != CONTRACT_BASELINE:
        _err(errors, "contractBaseline must be %s" % CONTRACT_BASELINE)

    if obj.get('sourceTag') != 'NOT_AVAILABLE':
        _err(errors, "sourceTag must be NOT_AVAILABLE (V1.7.0 tag is created at M6)")

    for f in ('buildStartedAt', 'buildEndedAt'):
        v = obj.get(f)
        if not isinstance(v, str) or not v:
            _err(errors, "%s must be a non-blank ISO-8601 string" % f)

    if not isinstance(obj.get('toolchain'), dict) or not obj.get('toolchain'):
        _err(errors, "toolchain must be a non-empty object")

    if not isinstance(obj.get('knownLimitations'), list) or not obj.get('knownLimitations'):
        _err(errors, "knownLimitations must be a non-empty array")
    else:
        # A dirty worktree release must disclose itself; it may not present as final/clean evidence.
        if obj.get('allowDirty') is True:
            kls = obj.get('knownLimitations')
            if not any('dirty' in str(k).lower() for k in kls):
                _err(errors, "allowDirty=true requires knownLimitations to disclose the dirty worktree")

    # Support dates / maintainer / approval: NOT_AVAILABLE until M6 certification.
    for f in ('ltsStart', 'standardSupportEnd', 'securitySupportEnd', 'maintainer', 'approval'):
        if obj.get(f) != 'NOT_AVAILABLE':
            _err(errors, "%s must be NOT_AVAILABLE until M6 certification; got %r" % (f, obj.get(f)))

    # Evidence: RC local build must not claim PASSED for compatibility/recovery/performance/soak.
    for f in ('compatibilityEvidence', 'recoveryEvidence', 'performanceEvidence', 'soakEvidence'):
        ev = obj.get(f)
        if isinstance(ev, dict) and 'status' in ev:
            if ev['status'] not in ('NOT_RUN', 'PENDING'):
                _err(errors, "%s.status must be NOT_RUN or PENDING for an M5-B local RC build; got %s"
                     % (f, ev['status']))
        else:
            _err(errors, "%s must be {status,...}" % f)

    artifacts = obj.get('artifacts')
    if not isinstance(artifacts, list) or not artifacts:
        _err(errors, "artifacts must be a non-empty array")
    else:
        errors.extend(validate_inventory(artifacts, version))
        for i, a in enumerate(artifacts):
            if not isinstance(a, dict):
                _err(errors, "artifacts[%d] must be an object" % i)
                continue
            for k in ('name', 'type', 'sha256', 'size', 'sbom', 'signature', 'provenance'):
                if k not in a:
                    _err(errors, "artifacts[%d] (%s) missing field: %s" % (i, a.get('name'), k))
            atype = a.get('type')
            sha = a.get('sha256')
            size = a.get('size')
            if atype in ('jar', 'tar.gz'):
                if not (isinstance(sha, str) and SHA256_RE.match(sha)):
                    _err(errors, "artifact %s (file) sha256 must be 64-hex" % a.get('name'))
                if not isinstance(size, int) or size < 0:
                    _err(errors, "artifact %s (file) size must be a non-negative integer" % a.get('name'))
            elif atype == 'docker-image':
                if sha != 'NOT_AVAILABLE':
                    _err(errors, "artifact %s (image) sha256 must be NOT_AVAILABLE "
                         "(local image has no distributable file SHA-256)" % a.get('name'))
                img = a.get('image')
                if not isinstance(img, dict):
                    _err(errors, "artifact %s (image) missing image metadata" % a.get('name'))
                else:
                    if not (isinstance(img.get('imageId'), str) and img.get('imageId', '').startswith('sha256:')):
                        _err(errors, "artifact %s image.imageId must be a sha256: digest" % a.get('name'))
                    rd = img.get('repoDigests')
                    if not isinstance(rd, list) or not all(isinstance(x, str) for x in rd):
                        _err(errors, "artifact %s image.repoDigests must be a list of strings taken "
                             "truthfully from docker inspect (local builds are usually empty, but a "
                             "registry-pulled base may supply real digests that must be preserved)" % a.get('name'))
                    if not isinstance(img.get('rootfs'), dict) or not img.get('rootfs', {}).get('diffIds'):
                        _err(errors, "artifact %s image.rootfs.diffIds must be a non-empty list" % a.get('name'))
            else:
                _err(errors, "artifact %s has unknown type: %s" % (a.get('name'), atype))
            for k in ('sbom', 'signature', 'provenance'):
                _validate_supply_field(a, k, errors, "artifacts[%d] (%s)" % (i, a.get('name')))
    return errors


# --- manifest construction (M5-B local RC) ------------------------------------------------
# build_manifest turns a build spec (computed paths + raw docker inspect payloads + build metadata)
# into a fully-populated, validated, honest M5-B release manifest. It is the single place that
# promotes field values, so the honesty vocabulary stays centralized: file sha256/size are computed
# from the staged files; image metadata is parsed from real docker inspect output; SBOM/signature/
# provenance/evidence/support-dates are the NOT_AVAILABLE/NOT_RUN/SKIPPED statuses owned by
# M5-C/M5-D/M6. It never fabricates a digest, signature, SBOM, provenance, source tag, approval or
# support date.

DEFAULT_KNOWN_LIMITATIONS = [
    "M5-B local RC build; not a certified LTS release (M6 owns certification).",
    "Maven unit/integration test gate (roadmap §14) is a separate pre-condition; build-release.sh "
    "assembles with -DskipTests and the manifest does not claim test evidence.",
    "SBOM (CycloneDX), vulnerability scan, license policy, cosign signature, and provenance are "
    "owned by M5-C/M5-D and recorded NOT_AVAILABLE/PENDING/SKIPPED; never fabricated.",
    "Two-clean-checkout reproducibility comparison is owned by M5-D (verify-reproducible.sh).",
    "Support dates, maintainer, and approval are owned by M6 and recorded NOT_AVAILABLE.",
    "Compatibility, recovery, performance, and soak evidence are owned by M3/M1/M2/M6 and recorded NOT_RUN.",
]


def build_manifest(spec):
    """Construct an honest M5-B local RC manifest from a build spec dict; validate and return it.

    ``spec`` keys: version, gitCommit, buildWorkflow, buildStartedAt, buildEndedAt, sourceDateEpoch,
    allowDirty (bool), dirtyFiles (list), toolchain (dict), knownLimitations (list, optional),
    files (list of {name,type,path}), images (list of {name,inspect}). File sha256/size are computed
    from ``path``; image metadata is parsed from the raw ``docker inspect`` ``inspect`` payload.
    """
    version = spec['version']
    artifacts = []
    for f in spec.get('files', []):
        path = f['path']
        if not os.path.isfile(path):
            raise ValueError("file artifact path does not exist: %s" % path)
        artifacts.append({
            'name': f['name'],
            'type': f['type'],
            'sha256': sha256_file(path),
            'size': os.path.getsize(path),
            'path': f['name'],  # output-root-relative
            'sbom': {'status': 'NOT_AVAILABLE'},
            'signature': {'status': 'SKIPPED'},
            'provenance': {'status': 'NOT_AVAILABLE'},
        })
    for img in spec.get('images', []):
        meta = parse_docker_inspect(img['inspect'])
        artifacts.append({
            'name': img['name'],
            'type': 'docker-image',
            'sha256': 'NOT_AVAILABLE',  # local image has no distributable file SHA-256
            'size': meta['size'],
            'image': meta,
            'sbom': {'status': 'NOT_AVAILABLE'},
            'signature': {'status': 'SKIPPED'},
            'provenance': {'status': 'NOT_AVAILABLE'},
        })
    known = list(spec.get('knownLimitations', []))
    for d in DEFAULT_KNOWN_LIMITATIONS:
        if d not in known:
            known.append(d)
    if spec.get('allowDirty'):
        known.append("Released from a dirty worktree (--allow-dirty development override); "
                     "NOT final/clean release evidence.")
    manifest = {
        'schemaVersion': SCHEMA_VERSION,
        'version': version,
        'gitCommit': spec['gitCommit'],
        'sourceTag': 'NOT_AVAILABLE',  # V1.7.0 tag is created at M6
        'contractBaseline': CONTRACT_BASELINE,
        'buildWorkflow': spec['buildWorkflow'],
        'buildStartedAt': spec['buildStartedAt'],
        'buildEndedAt': spec['buildEndedAt'],
        'toolchain': spec['toolchain'],
        'sourceDateEpoch': spec['sourceDateEpoch'],
        'allowDirty': bool(spec.get('allowDirty', False)),
        'dirtyFiles': list(spec.get('dirtyFiles', [])),
        'artifacts': artifacts,
        'compatibilityEvidence': {'status': 'NOT_RUN'},
        'recoveryEvidence': {'status': 'NOT_RUN'},
        'performanceEvidence': {'status': 'NOT_RUN'},
        'soakEvidence': {'status': 'NOT_RUN'},
        'knownLimitations': known,
        'ltsStart': 'NOT_AVAILABLE',
        'standardSupportEnd': 'NOT_AVAILABLE',
        'securitySupportEnd': 'NOT_AVAILABLE',
        'maintainer': 'NOT_AVAILABLE',
        'approval': 'NOT_AVAILABLE',
    }
    errors = validate_manifest(manifest)
    if errors:
        raise ValueError("manifest validation failed:\n  " + "\n  ".join(errors))
    return manifest


# --- self-test ----------------------------------------------------------------------------

def _self_test_tar_determinism(tmp):
    staging = os.path.join(tmp, 'stage')
    top = 'kairo-agent-bundle-1.7.0-rc.1'
    os.makedirs(os.path.join(staging, 'lib'))
    os.makedirs(os.path.join(staging, 'bin'))
    os.makedirs(os.path.join(staging, 'examples'))
    with open(os.path.join(staging, 'lib', 'kairo-cli-1.7.0-rc.1.jar'), 'wb') as f:
        f.write(b'cli-payload')
    with open(os.path.join(staging, 'bin', 'kairo-attach.jar'), 'wb') as f:
        f.write(b'attach-payload')
    with open(os.path.join(staging, 'examples', 'attach-launch.sh'), 'w') as f:
        f.write('#!/usr/bin/env bash\njava -jar kairo-attach.jar exec\n')
    with open(os.path.join(staging, 'LICENSE'), 'w') as f:
        f.write('AGPL-3.0\n')
    out1 = os.path.join(tmp, 'a.tar.gz')
    out2 = os.path.join(tmp, 'b.tar.gz')
    make_deterministic_tar(staging, top, out1, 1700000000)
    make_deterministic_tar(staging, top, out2, 1700000000)
    b1 = open(out1, 'rb').read()
    b2 = open(out2, 'rb').read()
    if b1 != b2:
        return False, "two deterministic builds of identical inputs differ (%d vs %d bytes)" % (len(b1), len(b2))
    # Inspect entries: sorted, uid/gid 0, mtime fixed, no traversal, stable modes.
    names, infos = [], []
    raw = gzip.decompress(b1)
    with tarfile.open(fileobj=io.BytesIO(raw), mode='r') as tf:
        for m in tf.getmembers():
            names.append(m.name)
            infos.append((m.uid, m.gid, m.uname, m.gname, m.mtime, m.mode, m.type))
    sorted_names = sorted(names)
    if names != sorted_names:
        return False, "tar entries not in sorted order: %s" % names
    for arc, (uid, gid, un, gn, mt, mode, typ) in zip(names, infos):
        if arc.startswith('/') or '..' in arc.split('/'):
            return False, "absolute/traversal entry present: %s" % arc
        if uid != 0 or gid != 0 or un != '' or gn != '':
            return False, "entry %s has non-normalized owner: uid=%d gid=%d uname=%r gname=%r" % (arc, uid, gid, un, gn)
        if mt != 1700000000:
            return False, "entry %s mtime not fixed: %d" % (arc, mt)
        base = arc.rsplit('/', 1)[-1]
        if typ == tarfile.DIRTYPE:
            if mode != 0o755:
                return False, "dir %s mode not 0755: %o" % (arc, mode)
        elif base.endswith('.sh'):
            if mode != 0o755:
                return False, "script %s mode not 0755: %o" % (arc, mode)
        else:
            if mode != 0o644:
                return False, "file %s mode not 0644: %o" % (arc, mode)
    if not any(n.endswith('examples/attach-launch.sh') for n in names):
        return False, "expected example script missing from tar"
    return True, "%d entries, deterministic, normalized" % len(names)


def _self_test_secrets(tmp):
    clean = os.path.join(tmp, 'clean.txt')
    with open(clean, 'w') as f:
        f.write('just text, no secrets\n')
    if scan_secrets([clean]):
        return False, "clean file flagged"
    hot = os.path.join(tmp, 'hot.env')
    with open(hot, 'w') as f:
        f.write('KAIRO_BOOTSTRAP_TOKEN=kairo-dev-admin-token-change-me\n')
    findings = scan_secrets([hot])
    if not any(p == hot and 'kairo-dev-admin-token' in pat for p, pat, _ in findings):
        return False, "dev token not detected"
    return True, "dev token + clean file handled"


def _self_test_sha256sums(tmp):
    a = os.path.join(tmp, 'a.jar')
    b = os.path.join(tmp, 'b.tar.gz')
    with open(a, 'wb') as f:
        f.write(b'aaaa')
    with open(b, 'wb') as f:
        f.write(b'bbbb')
    sums = os.path.join(tmp, 'SHA256SUMS')
    written = write_sha256sums([('b.tar.gz', b), ('a.jar', a)], sums)
    if written != ['a.jar', 'b.tar.gz']:
        return False, "SHA256SUMS not in lexical order: %s" % written
    lines = open(sums, encoding='utf-8').read().splitlines()
    if len(lines) != 2:
        return False, "expected 2 sum lines, got %d" % len(lines)
    # each line: <64hex>  <name>
    for ln, exp in zip(lines, ['a.jar', 'b.tar.gz']):
        parts = ln.split('  ', 1)
        if len(parts) != 2 or not SHA256_RE.match(parts[0]) or parts[1] != exp:
            return False, "bad sum line: %r" % ln
    # self-inclusion must be rejected
    try:
        write_sha256sums([('SHA256SUMS', sums)], sums + '.x')
        return False, "SHA256SUMS self-inclusion not rejected"
    except ValueError:
        pass
    return True, "lexical order + self-inclusion rejected"


def _self_test_version():
    good = ['1.7.0', '1.7.0-rc.1', '1.7.0-rc.10']
    bad = ['1.7.0-SNAPSHOT', '1.7.1', '1.6.0', '1.7.0-rc', '1.7.0-rc.0', '', '1.7.0-rc.01']
    for v in good:
        if not is_release_version(v):
            return False, "valid version rejected: %s" % v
    for v in bad:
        if is_release_version(v):
            return False, "invalid version accepted: %s" % v
    return True, "version contract enforced"


def _self_test_docker_inspect():
    payload = [{
        "Id": "sha256:abc123def",
        "RepoTags": ["kairo-platform-server:1.7.0-rc.1"],
        "RepoDigests": [],
        "Size": 123456,
        "Created": "2026-01-01T00:00:00Z",
        "Architecture": "amd64",
        "Os": "linux",
        "RootFS": {"type": "layers", "Layers": ["sha256:layer1", "sha256:layer2"]},
    }]
    meta = parse_docker_inspect(payload)
    if meta['imageId'] != 'sha256:abc123def':
        return False, "imageId not parsed"
    if meta['repoDigests'] != []:
        return False, "empty repoDigests not preserved"
    if meta['manifestDigest'] != 'NOT_AVAILABLE':
        return False, "manifestDigest must be NOT_AVAILABLE for local image"
    if meta['rootfs']['diffIds'] != ['sha256:layer1', 'sha256:layer2']:
        return False, "rootfs layers not parsed"
    # Truthful non-empty RepoDigests (e.g. an image whose base was pulled from a registry) must be
    # preserved verbatim, never dropped or invented. Do not require RepoDigests to be empty in all envs.
    payload2 = json.loads(json.dumps(payload))
    payload2[0]["RepoDigests"] = ["kairo-platform-server@sha256:deadbeef"]
    meta2 = parse_docker_inspect(payload2)
    if meta2['repoDigests'] != ["kairo-platform-server@sha256:deadbeef"]:
        return False, "non-empty repoDigests not preserved truthfully: %r" % (meta2['repoDigests'],)
    return True, "local image metadata parsed truthfully (empty + non-empty repoDigests preserved)"


def _full_valid_manifest():
    """A complete, honest M5-B local RC manifest covering the exact §12.2 inventory."""
    def file_entry(name, typ, sha):
        return {"name": name, "type": typ, "sha256": sha, "size": 10, "path": name,
                "sbom": {"status": "NOT_AVAILABLE"}, "signature": {"status": "SKIPPED"},
                "provenance": {"status": "NOT_AVAILABLE"}}

    def image_entry(name):
        return {"name": name, "type": "docker-image", "sha256": "NOT_AVAILABLE", "size": 10,
                "image": {"imageId": "sha256:abc", "repoTags": [name], "repoDigests": [],
                          "rootfs": {"type": "layers", "diffIds": ["sha256:x"]}},
                "sbom": {"status": "NOT_AVAILABLE"}, "signature": {"status": "SKIPPED"},
                "provenance": {"status": "NOT_AVAILABLE"}}

    return {
        "schemaVersion": "1.0",
        "version": "1.7.0-rc.1",
        "gitCommit": "a" * 40,
        "sourceTag": "NOT_AVAILABLE",
        "contractBaseline": "V1.6.0",
        "buildWorkflow": "./scripts/v1.7/build-release.sh --version 1.7.0-rc.1",
        "buildStartedAt": "2026-08-03T00:00:00Z",
        "buildEndedAt": "2026-08-03T00:01:00Z",
        "toolchain": {"os": "linux"},
        "sourceDateEpoch": 1700000000,
        "allowDirty": False,
        "artifacts": [
            file_entry("kairo-agent-bundle-1.7.0-rc.1.tar.gz", "tar.gz", "f" * 64),
            file_entry("kairo-platform-server-1.7.0-rc.1.jar", "jar", "e" * 64),
            file_entry("kairo-cli-1.7.0-rc.1.jar", "jar", "d" * 64),
            file_entry("kairo-mcp-1.7.0-rc.1.jar", "jar", "c" * 64),
            file_entry("kairo-sdk-1.7.0-rc.1.jar", "jar", "b" * 64),
            file_entry("kairo-compose-1.7.0-rc.1.tar.gz", "tar.gz", "a" * 64),
            image_entry("kairo-platform-server:1.7.0-rc.1"),
            image_entry("kairo-platform-web:1.7.0-rc.1"),
        ],
        "compatibilityEvidence": {"status": "NOT_RUN"},
        "recoveryEvidence": {"status": "NOT_RUN"},
        "performanceEvidence": {"status": "NOT_RUN"},
        "soakEvidence": {"status": "NOT_RUN"},
        "knownLimitations": ["M5-B local RC build"],
        "ltsStart": "NOT_AVAILABLE",
        "standardSupportEnd": "NOT_AVAILABLE",
        "securitySupportEnd": "NOT_AVAILABLE",
        "maintainer": "NOT_AVAILABLE",
        "approval": "NOT_AVAILABLE",
    }


def _self_test_manifest_validation():
    full = _full_valid_manifest()
    errors = validate_manifest(full)
    if errors:
        return False, "valid manifest rejected: %s" % errors
    # tampered: fabricated signature
    bad = json.loads(json.dumps(full))
    bad["artifacts"][0]["signature"] = {"status": "SIGNED"}
    if not validate_manifest(bad):
        return False, "fabricated SIGNED signature not rejected"
    # tampered: snapshot version
    bad = json.loads(json.dumps(full))
    bad["version"] = "1.7.0-SNAPSHOT"
    if not validate_manifest(bad):
        return False, "SNAPSHOT version not rejected"
    # tampered: extra forbidden artifact
    bad = json.loads(json.dumps(full))
    bad["artifacts"].append({"name": "kairo-demo-1.7.0-rc.1.jar", "type": "jar",
                             "sha256": "0" * 64, "size": 1,
                             "sbom": {"status": "NOT_AVAILABLE"}, "signature": {"status": "SKIPPED"},
                             "provenance": {"status": "NOT_AVAILABLE"}})
    if not validate_manifest(bad):
        return False, "kairo-demo artifact not rejected"
    # tampered: truthful non-empty repoDigests must be ACCEPTED (docker may supply real digests;
    # they must not be required empty in all environments)
    bad = json.loads(json.dumps(full))
    for a in bad["artifacts"]:
        if a["type"] == "docker-image":
            a["image"]["repoDigests"] = ["kairo-platform-server@sha256:deadbeef"]
    if validate_manifest(bad):
        return False, "truthful non-empty repoDigests wrongly rejected"
    # tampered: non-list repoDigests must be REJECTED (structural honesty)
    bad = json.loads(json.dumps(full))
    for a in bad["artifacts"]:
        if a["type"] == "docker-image":
            a["image"]["repoDigests"] = "not-a-list"
    if not validate_manifest(bad):
        return False, "non-list repoDigests not rejected"
    # tampered: PASSED compatibility evidence for an RC local build
    bad = json.loads(json.dumps(full))
    bad["compatibilityEvidence"] = {"status": "PASSED"}
    if not validate_manifest(bad):
        return False, "RC compatibility evidence PASSED not rejected"
    # tampered: real support date before M6
    bad = json.loads(json.dumps(full))
    bad["ltsStart"] = "2026-01-01"
    if not validate_manifest(bad):
        return False, "pre-M6 support date not rejected"
    return True, "manifest schema/honesty enforced"


def _self_test_inventory():
    inv = expected_inventory("1.7.0-rc.1")
    if validate_inventory(inv, "1.7.0-rc.1"):
        return False, "exact inventory rejected: %s" % validate_inventory(inv, "1.7.0-rc.1")
    if len(inv) != 8:
        return False, "expected 8 artifacts, got %d" % len(inv)
    missing_one = inv[:-1]
    if not validate_inventory(missing_one, "1.7.0-rc.1"):
        return False, "missing artifact not detected"
    extra = inv + [{"name": "kairo-demo-1.7.0-rc.1.jar", "type": "jar"}]
    if not validate_inventory(extra, "1.7.0-rc.1"):
        return False, "extra artifact not detected"
    return True, "exact §12.2 inventory enforced (8 artifacts)"


def _self_test_build_manifest(tmp):
    # Synthetic file artifacts (clearly fixtures, not a real release).
    fa = os.path.join(tmp, 'kairo-sdk-1.7.0-rc.1.jar')
    fb = os.path.join(tmp, 'kairo-compose-1.7.0-rc.1.tar.gz')
    with open(fa, 'wb') as f:
        f.write(b'sdk-payload')
    with open(fb, 'wb') as f:
        f.write(b'compose-payload')
    spec = {
        'version': '1.7.0-rc.1',
        'gitCommit': 'b' * 40,
        'buildWorkflow': './scripts/v1.7/build-release.sh --version 1.7.0-rc.1',
        'buildStartedAt': '2026-08-03T00:00:00Z',
        'buildEndedAt': '2026-08-03T00:01:00Z',
        'sourceDateEpoch': 1700000000,
        'allowDirty': False,
        'toolchain': {'mvn': '3.9.16', 'java': '21.0.11', 'os': 'linux'},
        'files': [
            {'name': 'kairo-agent-bundle-1.7.0-rc.1.tar.gz', 'type': 'tar.gz', 'path': fb},
            {'name': 'kairo-platform-server-1.7.0-rc.1.jar', 'type': 'jar', 'path': fa},
            {'name': 'kairo-cli-1.7.0-rc.1.jar', 'type': 'jar', 'path': fa},
            {'name': 'kairo-mcp-1.7.0-rc.1.jar', 'type': 'jar', 'path': fa},
            {'name': 'kairo-sdk-1.7.0-rc.1.jar', 'type': 'jar', 'path': fa},
            {'name': 'kairo-compose-1.7.0-rc.1.tar.gz', 'type': 'tar.gz', 'path': fb},
        ],
        'images': [
            {'name': 'kairo-platform-server:1.7.0-rc.1', 'inspect': [{
                'Id': 'sha256:server', 'RepoTags': ['kairo-platform-server:1.7.0-rc.1'],
                'RepoDigests': [], 'Size': 100, 'Created': '2026-01-01T00:00:00Z',
                'Architecture': 'amd64', 'Os': 'linux',
                'RootFS': {'type': 'layers', 'Layers': ['sha256:l1']}}]},
            {'name': 'kairo-platform-web:1.7.0-rc.1', 'inspect': [{
                'Id': 'sha256:web', 'RepoTags': ['kairo-platform-web:1.7.0-rc.1'],
                # Real registry-pull digest that Docker truthfully supplied; must be preserved.
                'RepoDigests': ['kairo-platform-web@sha256:real'], 'Size': 200,
                'Created': '2026-01-01T00:00:00Z', 'Architecture': 'amd64', 'Os': 'linux',
                'RootFS': {'type': 'layers', 'Layers': ['sha256:l2']}}]},
        ],
    }
    m = build_manifest(spec)
    errs = validate_manifest(m)
    if errs:
        return False, "build_manifest produced an invalid manifest: %s" % errs
    if len(m['artifacts']) != 8:
        return False, "expected 8 artifacts, got %d" % len(m['artifacts'])
    # File hash computed from the staged file.
    sdk = [a for a in m['artifacts'] if a['name'] == 'kairo-sdk-1.7.0-rc.1.jar'][0]
    if sdk['sha256'] != sha256_file(fa):
        return False, "sdk sha256 not computed from the staged file"
    # Web image preserves the truthful non-empty repoDigests; manifestDigest stays NOT_AVAILABLE.
    web = [a for a in m['artifacts'] if a['name'].startswith('kairo-platform-web:')][0]
    if web['image']['repoDigests'] != ['kairo-platform-web@sha256:real']:
        return False, "web non-empty repoDigests not preserved: %r" % (web['image']['repoDigests'],)
    if web['image']['manifestDigest'] != 'NOT_AVAILABLE':
        return False, "web manifestDigest must be NOT_AVAILABLE"
    # allowDirty=true must record an honest disclosure in knownLimitations.
    spec2 = json.loads(json.dumps(spec))
    spec2['allowDirty'] = True
    spec2['dirtyFiles'] = ['scripts/v1.7/build-release.sh']
    m2 = build_manifest(spec2)
    if not any('dirty worktree' in k for k in m2['knownLimitations']):
        return False, "allowDirty did not disclose the dirty worktree"
    return True, "build_manifest produces an honest, validated manifest (8 artifacts)"


def self_test():
    """Run focused assembly assertions. Prints a JSON summary and exits 0 iff all pass."""
    import tempfile
    checks = []

    def run(name, fn):
        try:
            ok, detail = fn()
        except Exception as e:
            ok, detail = False, "exception: %s: %s" % (type(e).__name__, e)
        checks.append({"name": name, "passed": bool(ok), "detail": detail})

    run("version-contract", _self_test_version)
    run("docker-inspect", _self_test_docker_inspect)
    run("inventory", _self_test_inventory)
    run("manifest-validation", _self_test_manifest_validation)

    with tempfile.TemporaryDirectory(prefix='releaselib-selftest-') as tmp:
        run("deterministic-tar", lambda: _self_test_tar_determinism(tmp))
        run("secret-scanning", lambda: _self_test_secrets(tmp))
        run("sha256sums", lambda: _self_test_sha256sums(tmp))
        run("build-manifest", lambda: _self_test_build_manifest(tmp))

    passed = sum(1 for c in checks if c['passed'])
    failed = len(checks) - passed
    summary = {"passed": passed, "failed": failed, "total": len(checks), "checks": checks}
    print(json.dumps(summary, indent=2))
    return 0 if failed == 0 else 1


def main(argv=None):
    p = argparse.ArgumentParser(description="V1.7 M5-B release assembly library")
    sub = p.add_subparsers(dest='cmd')

    sub.add_parser('self-test', help="run focused assembly assertions")

    vi = sub.add_parser('validate-version', help="exit 0 iff a release version is valid")
    vi.add_argument('version')

    vd = sub.add_parser('validate-manifest', help="validate a release-manifest.json; exit 0 iff valid")
    vd.add_argument('path')

    inv = sub.add_parser('validate-inventory', help="validate §12.2 inventory in a manifest; exit 0 iff valid")
    inv.add_argument('path')

    mt = sub.add_parser('make-tar', help="create a deterministic tar.gz from a staging directory")
    mt.add_argument('--staging-root', required=True)
    mt.add_argument('--top-dir', required=True)
    mt.add_argument('--out', required=True)
    mt.add_argument('--epoch', required=True, type=int)

    ss = sub.add_parser('scan-secrets', help="scan files for known secret/dev-token patterns; exit 0 iff clean")
    ss.add_argument('paths', nargs='+')

    ws = sub.add_parser('sha256sums', help="write SHA256SUMS for (name:path,...) entries")
    ws.add_argument('--out', required=True)
    ws.add_argument('entries', nargs='+', help="name:path pairs")

    bm = sub.add_parser('build-manifest',
                        help="build + validate an honest M5-B release manifest from a spec JSON")
    bm.add_argument('--spec', required=True, help="path to the build spec JSON")
    bm.add_argument('--out', required=True, help="path to write release-manifest.json")

    args = p.parse_args(argv)
    if args.cmd == 'self-test':
        return self_test()
    if args.cmd == 'validate-version':
        errs = validate_version(args.version)
        if errs:
            for e in errs:
                print("error: %s" % e, file=sys.stderr)
            return 1
        print("ok: %s" % args.version)
        return 0
    if args.cmd == 'validate-manifest':
        with open(args.path, encoding='utf-8') as f:
            obj = json.load(f)
        errs = validate_manifest(obj)
        if errs:
            for e in errs:
                print("error: %s" % e, file=sys.stderr)
            return 1
        print("ok: manifest valid")
        return 0
    if args.cmd == 'validate-inventory':
        with open(args.path, encoding='utf-8') as f:
            obj = json.load(f)
        errs = validate_inventory(obj.get('artifacts', []), obj.get('version', ''))
        if errs:
            for e in errs:
                print("error: %s" % e, file=sys.stderr)
            return 1
        print("ok: inventory valid")
        return 0
    if args.cmd == 'make-tar':
        n = make_deterministic_tar(args.staging_root, args.top_dir, args.out, args.epoch)
        print("ok: wrote %s (%d entries)" % (args.out, n))
        return 0
    if args.cmd == 'scan-secrets':
        findings = scan_secrets(args.paths)
        if findings:
            for p, pat, off in findings:
                print("secret: %s :: %s @ %s" % (p, pat, off), file=sys.stderr)
            return 1
        print("ok: no secrets found across %d file(s)" % len(args.paths))
        return 0
    if args.cmd == 'sha256sums':
        entries = []
        for e in args.entries:
            if ':' not in e:
                print("error: entry must be name:path: %s" % e, file=sys.stderr)
                return 2
            name, path = e.split(':', 1)
            entries.append((name, path))
        written = write_sha256sums(entries, args.out)
        print("ok: wrote %s (%d entries)" % (args.out, len(written)))
        return 0
    if args.cmd == 'build-manifest':
        with open(args.spec, encoding='utf-8') as f:
            spec = json.load(f)
        manifest = build_manifest(spec)
        with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(manifest, f, indent=2, sort_keys=True)
            f.write('\n')
        print("ok: wrote %s (%d artifacts)" % (args.out, len(manifest['artifacts'])))
        return 0
    p.print_help()
    return 1


if __name__ == '__main__':
    sys.exit(main())
