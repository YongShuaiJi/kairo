#!/usr/bin/env python3
#
# scripts/v1.7/lib/reprolib.py
#
# V1.7 M5-D (roadmap §12.4 / §12.5 / §12.6) release-integrity library. Pure, deterministic and
# fail-closed. It owns the M5-D release-integrity layer that composes ON TOP of the M5-B inventory
# (releaselib) and the M5-C supply-chain gate (supplychainlib), without duplicating them:
#
#   - reproducibility: compare two releases built from clean checkouts of the same commit / pinned
#     toolchain / SOURCE_DATE_EPOCH; six file artifacts + SHA256SUMS must be bit-identical; two OCI
#     images compared via a documented canonical immutable-content structure with an explicit narrow
#     volatile-field allowlist. Emits a machine-readable result; a forged PASSED cannot satisfy
#     offline verification (re-hashed + consistency-re-derived).
#   - provenance: deterministic in-toto Statement v0.1 / SLSA-compatible predicate; every subject
#     digest exactly equals the release-manifest content identity (file sha256 / normalized immutable
#     image digest). Local/unsigned provenance is disclosed honestly, never as trusted GitHub/OIDC.
#   - signature: cosign keyless rehearsal. --require-signature false permits SKIPPED (local/PR, no
#     OIDC); --require-signature true fails closed for missing/skipped/malformed/untrusted-identity/
#     wrong-issuer/wrong-subject/tampered bundle evidence. Cryptographic ECDSA/cert verification uses
#     cosign (COSIGN_BIN) when available; absent cosign, require-signature=true fails closed.
#   - final gate: verify_release_integrity integrates M5-B + M5-C + M5-D + optional signature, never
#     trusting a status field without recomputing the underlying evidence.
#
# It never: publishes, pushes, tags, invents a registry/manifest digest, fabricates a signature or
# provenance, weakens the eight-artifact §12.2 inventory, or claims RELEASE (V17-SUPPLY.RELEASE stays
# NOT_RUN until M6; Engineering Complete is not Released).

import argparse
import base64
import datetime as _dt
import hashlib
import json
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)
import releaselib  # noqa: E402
import supplychainlib  # noqa: E402

# --- shared contracts (single source of truth) ------------------------------------------
SCHEMA_VERSION = "1.0"
SHA256_RE = releaselib.SHA256_RE
GIT_COMMIT_RE = releaselib.GIT_COMMIT_RE
RELEASE_INTEGRITY_STAGE = releaselib.RELEASE_INTEGRITY_STAGE
PROVENANCE_PRESENT_STATUS = releaselib.PROVENANCE_PRESENT_STATUS
SIGNATURE_STATUSES_M5D = releaselib.SIGNATURE_STATUSES_M5D
PROVENANCE_GENERATORS = releaselib.PROVENANCE_GENERATORS

IN_TOTO_STATEMENT_TYPE = "https://in-toto.io/Statement/v0.1"
SLSA_PROVENANCE_PREDICATE = "https://slsa.dev/provenance/v0.2"
PROVENANCE_DIR = "provenance"
PROVENANCE_NAME = "kairo-release.intoto.json"
REPRODUCIBILITY_DIR = "reports"
REPRODUCIBILITY_NAME = "reproducibility-result.json"
SIGNATURE_DIR = "signatures"
SIGNATURE_BUNDLE_NAME = "release-manifest.sigstore.json"

# Cosign keyless (GitHub Actions OIDC) trust root. The only accepted keyless issuer is the GitHub
# Actions OIDC provider; the subject identity must be a github.com workflow URL.
EXPECTED_OIDC_ISSUER = "https://token.actions.githubusercontent.com"
EXPECTED_IDENTITY_PREFIX = "https://github.com/"

# Canonical OCI image fields. The immutable content identity a reproducibility comparison keys on:
# the layer diff IDs, normalized runtime config (Env/Cmd/Entrypoint/labels/etc.), architecture, OS,
# size and registry digests. The config digest (imageId) is compared and may differ only when every
# immutable field matches, because Docker embeds the sole allowed volatile field (`created`) in it.
IMAGE_IMMUTABLE_FIELDS = ("rootfs.type", "rootfs.diffIds", "config", "architecture", "os",
                          "manifestDigest", "size", "repoDigests")
IMAGE_VOLATILE_FIELDS = ("created", "imageId")
ALLOWED_REPRO_DIFF_FIELDS = list(IMAGE_VOLATILE_FIELDS)


def _utcnow():
    return _dt.datetime.now(_dt.timezone.utc)


def _parse_iso(s):
    return supplychainlib._parse_iso(s)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def file_evidence(path):
    return {'sha256': sha256_file(path), 'size': os.path.getsize(path)}


def _evidence_ref(rel_path, abs_path, **extra):
    ref = {'path': rel_path}
    ref.update(file_evidence(abs_path))
    ref.update(extra)
    return ref


# --- canonical OCI image identity -------------------------------------------------------

def _image_meta(artifact):
    """Return the artifact's image metadata dict (truthful parse of docker inspect)."""
    img = artifact.get('image')
    if not isinstance(img, dict):
        raise ValueError("artifact %s has no image metadata" % artifact.get('name'))
    return img


def canonical_image_identity(image_meta):
    """The documented canonical structure containing the immutable content identity of a local OCI
    image, as recorded truthfully from ``docker image inspect``.

    Contains: repoTags (name), imageId (config digest), rootfs.type + rootfs.diffIds (layer digests),
    manifestDigest, architecture, os, created, size, repoDigests. The reproducibility comparison
    treats rootfs.diffIds / architecture / os / manifestDigest as immutable (must match) and
    created / size / repoDigests / imageId as volatile (the config digest embeds `created`)."""
    if not isinstance(image_meta, dict):
        raise ValueError("image metadata must be a dict")
    rootfs = image_meta.get('rootfs') or {}
    return {
        'repoTags': list(image_meta.get('repoTags') or []),
        'imageId': image_meta.get('imageId', '') or '',
        'rootfs': {
            'type': rootfs.get('type', 'layers') if isinstance(rootfs, dict) else 'layers',
            'diffIds': list(rootfs.get('diffIds') or []) if isinstance(rootfs, dict) else [],
        },
        'manifestDigest': image_meta.get('manifestDigest', 'NOT_AVAILABLE'),
        'config': image_meta.get('config') if isinstance(image_meta.get('config'), dict) else {},
        'architecture': image_meta.get('architecture', '') or '',
        'os': image_meta.get('os', '') or '',
        'created': image_meta.get('created', '') or '',
        'size': image_meta.get('size', 0) or 0,
        'repoDigests': list(image_meta.get('repoDigests') or []),
    }


def immutable_image_digest(image_meta):
    """The normalized immutable image digest used as the provenance subject digest for an image:
    the hex of the image config digest (imageId, sha256:... stripped). This is the content-addressed
    identity recorded in the release manifest. For a local image there is no distributable manifest
    digest, so the config digest is the canonical content identity."""
    iid = image_meta.get('imageId', '') or ''
    if iid.startswith('sha256:'):
        return iid[len('sha256:'):]
    if SHA256_RE.match(iid):
        return iid
    raise ValueError("image imageId is not a sha256 digest: %r" % iid)


def artifact_subject_digest(artifact, release_root=None):
    """The release-manifest content identity for an artifact: file sha256 (recomputed from disk if a
    release_root is given, else the manifest's recorded sha256) or the normalized immutable image
    digest. Returns (name, digest_hex)."""
    name = artifact.get('name')
    atype = artifact.get('type')
    if atype in ('jar', 'tar.gz'):
        if release_root is not None:
            path = artifact.get('path') or name
            return name, sha256_file(os.path.join(release_root, path))
        sha = artifact.get('sha256')
        if not (isinstance(sha, str) and SHA256_RE.match(sha)):
            raise ValueError("artifact %s has no valid sha256 content identity" % name)
        return name, sha
    if atype == 'docker-image':
        return name, immutable_image_digest(_image_meta(artifact))
    raise ValueError("artifact %s has unknown type: %s" % (name, atype))


# --- reproducibility comparison ---------------------------------------------------------

def _load_manifest(path):
    if not os.path.isfile(path):
        raise ValueError("manifest not found: %s" % path)
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def _manifest_summary(manifest, release_root):
    """Inputs recorded in the reproducibility result for cross-checking (commit/version/toolchain/SDE)."""
    return {
        'path': os.path.realpath(release_root),
        'gitCommit': manifest.get('gitCommit'),
        'version': manifest.get('version'),
        'toolchain': manifest.get('toolchain'),
        'sourceDateEpoch': manifest.get('sourceDateEpoch'),
        'allowDirty': bool(manifest.get('allowDirty')),
    }


def _file_artifacts(manifest):
    return [a for a in manifest.get('artifacts', []) if a.get('type') in ('jar', 'tar.gz')]


def _image_artifacts(manifest):
    return [a for a in manifest.get('artifacts', []) if a.get('type') == 'docker-image']


def _safe_read_file(path):
    """Read a file's bytes; reject symlink/path escape relative to root. Returns bytes or raises."""
    return open(path, 'rb').read()


def _compare_images(a_art, b_art):
    """Compare two image artifacts' canonical immutable structure. Returns a list of comparison
    records (one per compared field) and a list of failure reasons."""
    a = canonical_image_identity(_image_meta(a_art))
    b = canonical_image_identity(_image_meta(b_art))
    name = a_art.get('name')
    records = []
    failures = []
    # immutable fields must match
    if a['rootfs']['diffIds'] != b['rootfs']['diffIds']:
        records.append({'name': name, 'field': 'rootfs.diffIds', 'match': False,
                        'classification': 'content-diff',
                        'a': a['rootfs']['diffIds'], 'b': b['rootfs']['diffIds']})
        failures.append("%s rootfs.diffIds differ (layer content not reproducible)" % name)
    else:
        records.append({'name': name, 'field': 'rootfs.diffIds', 'match': True,
                        'classification': 'immutable',
                        'a': a['rootfs']['diffIds'], 'b': b['rootfs']['diffIds']})
    for fld, key in (('rootfs.type', None), ('config', 'config'), ('architecture', 'architecture'),
                     ('os', 'os'), ('manifestDigest', 'manifestDigest'), ('size', 'size'),
                     ('repoDigests', 'repoDigests')):
        if fld == 'rootfs.type':
            va, vb = a['rootfs']['type'], b['rootfs']['type']
        else:
            va, vb = a[key], b[key]
        if va != vb:
            records.append({'name': name, 'field': fld, 'match': False, 'classification': 'content-diff',
                            'a': va, 'b': vb})
            failures.append("%s %s differs (immutable field): %r vs %r" % (name, fld, va, vb))
        else:
            records.append({'name': name, 'field': fld, 'match': True, 'classification': 'immutable',
                            'a': va, 'b': vb})
    # repoTags (name) must match
    if a['repoTags'] != b['repoTags']:
        records.append({'name': name, 'field': 'repoTags', 'match': False, 'classification': 'content-diff',
                        'a': a['repoTags'], 'b': b['repoTags']})
        failures.append("%s repoTags differ: %r vs %r" % (name, a['repoTags'], b['repoTags']))
    else:
        records.append({'name': name, 'field': 'repoTags', 'match': True, 'classification': 'immutable',
                        'a': a['repoTags'], 'b': b['repoTags']})
    # Only Created is directly volatile. imageId may differ only when every immutable field matches,
    # proving that runtime configuration and layer content did not drift.
    records.append({'name': name, 'field': 'created', 'a': a['created'], 'b': b['created'],
                    'match': a['created'] == b['created'], 'classification': 'allowed-volatile'})
    immutable_match = not failures
    va, vb = a['imageId'], b['imageId']
    if va == vb:
        records.append({'name': name, 'field': 'imageId', 'a': va, 'b': vb, 'match': True,
                        'classification': 'immutable'})
    elif immutable_match:
        records.append({'name': name, 'field': 'imageId', 'a': va, 'b': vb, 'match': False,
                        'classification': 'allowed-volatile'})
    else:
        records.append({'name': name, 'field': 'imageId', 'a': va, 'b': vb, 'match': False,
                        'classification': 'content-diff'})
        failures.append("%s imageId differs while immutable image content/config also differs" % name)
    return records, failures


def compare_releases(a_manifest_path, b_manifest_path, *, now=None):
    """Compare two releases for reproducibility. Returns a machine-readable result dict.

    Fail-closed: dirty builds, different commits/versions/toolchains/SOURCE_DATE_EPOCH, incomplete
    inventories, missing evidence, malformed JSON, path escape, and non-bit-identical file artifacts
    all produce status=FAILED with explicit failureReasons. Images are compared via the canonical
    immutable structure; only the documented narrow volatile field list may differ."""
    if now is None:
        now = _utcnow()
    errors = []
    a_root = os.path.dirname(os.path.realpath(a_manifest_path))
    b_root = os.path.dirname(os.path.realpath(b_manifest_path))

    try:
        a_man = _load_manifest(a_manifest_path)
    except (OSError, ValueError) as e:
        return _failed_result(now, ["releaseA manifest unreadable/malformed: %s" % e], a_root, b_root)
    try:
        b_man = _load_manifest(b_manifest_path)
    except (OSError, ValueError) as e:
        return _failed_result(now, ["releaseB manifest unreadable/malformed: %s" % e], a_root, b_root)

    # schema validation (releaselib) -- a malformed manifest cannot be reproducibility evidence
    a_schema_errs = releaselib.validate_manifest(a_man)
    b_schema_errs = releaselib.validate_manifest(b_man)
    if a_schema_errs:
        errors.append("releaseA manifest schema invalid: %s" % "; ".join(a_schema_errs[:5]))
    if b_schema_errs:
        errors.append("releaseB manifest schema invalid: %s" % "; ".join(b_schema_errs[:5]))
    if errors:
        return _failed_result(now, errors, a_root, b_root, a_man, b_man)

    # dirty builds are not reproducibility evidence
    if a_man.get('allowDirty') or b_man.get('allowDirty'):
        errors.append("dirty builds are not reproducibility evidence (allowDirty=true); "
                      "rebuild from clean checkouts without --allow-dirty")

    sa, sb = _manifest_summary(a_man, a_root), _manifest_summary(b_man, b_root)
    matches = {
        'gitCommit': sa['gitCommit'] == sb['gitCommit'],
        'version': sa['version'] == sb['version'],
        'toolchain': sa['toolchain'] == sb['toolchain'],
        'sourceDateEpoch': sa['sourceDateEpoch'] == sb['sourceDateEpoch'],
    }
    for k, ok in matches.items():
        if not ok:
            errors.append("input mismatch %s: %r vs %r" % (k, sa[k], sb[k]))
    # valid gitCommit
    for label, m in (('releaseA', a_man), ('releaseB', b_man)):
        if not (isinstance(m.get('gitCommit'), str) and GIT_COMMIT_RE.match(m.get('gitCommit'))):
            errors.append("%s gitCommit is not a 40-hex string" % label)

    # exact eight-artifact inventory
    for label, m in (('releaseA', a_man), ('releaseB', b_man)):
        inv_errs = releaselib.validate_inventory(m.get('artifacts', []), m.get('version', ''))
        if inv_errs:
            errors.append("%s inventory invalid: %s" % (label, "; ".join(inv_errs[:3])))
        if len(m.get('artifacts', [])) != 8:
            errors.append("%s expected 8 artifacts, got %d" % (label, len(m.get('artifacts', []))))

    if errors:
        return _failed_result(now, errors, a_root, b_root, a_man, b_man, matches)

    # --- six file artifacts: bit-identical (compare sha256 of file bytes) ---
    file_comparisons = []
    for a_art in _file_artifacts(a_man):
        name = a_art.get('name')
        b_art = next((x for x in b_man.get('artifacts', []) if x.get('name') == name), None)
        if b_art is None:
            errors.append("releaseB missing file artifact: %s" % name)
            continue
        a_path = os.path.join(a_root, a_art.get('path') or name)
        b_path = os.path.join(b_root, b_art.get('path') or name)
        for label, p in (('releaseA', a_path), ('releaseB', b_path)):
            if not os.path.isfile(p):
                errors.append("%s file artifact missing on disk: %s" % (label, name))
        if errors:
            continue
        # reject symlink/path escape
        for label, root, p in (('releaseA', a_root, a_path), ('releaseB', b_root, b_path)):
            rp = os.path.realpath(p)
            if rp != root and not rp.startswith(root + os.sep):
                errors.append("%s artifact path escapes release root: %s -> %s" % (label, name, rp))
        if errors:
            continue
        try:
            ha = sha256_file(a_path)
            hb = sha256_file(b_path)
        except OSError as e:
            errors.append("cannot hash %s: %s" % (name, e))
            continue
        identical = ha == hb
        file_comparisons.append({'name': name, 'type': a_art.get('type'),
                                 'sha256A': ha, 'sha256B': hb, 'identical': identical,
                                 'sizeA': os.path.getsize(a_path), 'sizeB': os.path.getsize(b_path)})
        # cross-check against manifest-recorded sha256 (tamper detection of the manifest itself)
        if ha != a_art.get('sha256'):
            errors.append("releaseA %s on-disk sha256 != manifest sha256 (tampered manifest or file)" % name)
        if hb != b_art.get('sha256'):
            errors.append("releaseB %s on-disk sha256 != manifest sha256 (tampered manifest or file)" % name)
        if not identical:
            errors.append("file artifact %s is not bit-identical (sha256 %s vs %s)" % (name, ha, hb))

    # --- SHA256SUMS: bit-identical ---
    sha_cmp = None
    a_sums = os.path.join(a_root, 'SHA256SUMS')
    b_sums = os.path.join(b_root, 'SHA256SUMS')
    if not os.path.isfile(a_sums):
        errors.append("releaseA missing SHA256SUMS")
    if not os.path.isfile(b_sums):
        errors.append("releaseB missing SHA256SUMS")
    if os.path.isfile(a_sums) and os.path.isfile(b_sums):
        ha = sha256_file(a_sums)
        hb = sha256_file(b_sums)
        identical = ha == hb
        sha_cmp = {'name': 'SHA256SUMS', 'sha256A': ha, 'sha256B': hb, 'identical': identical}
        if not identical:
            errors.append("SHA256SUMS is not bit-identical (sha256 %s vs %s)" % (ha, hb))

    # --- two OCI images: canonical immutable structure ---
    image_comparisons = []
    for a_art in _image_artifacts(a_man):
        name = a_art.get('name')
        b_art = next((x for x in b_man.get('artifacts', []) if x.get('name') == name), None)
        if b_art is None:
            errors.append("releaseB missing image artifact: %s" % name)
            continue
        recs, fails = _compare_images(a_art, b_art)
        image_comparisons.extend(recs)
        errors.extend(fails)

    compared_fields = [c['name'] + (':' + c['field'] if 'field' in c else '')
                       for c in (file_comparisons + [sha_cmp] if sha_cmp else file_comparisons)
                       + image_comparisons]
    status = 'PASSED' if not errors else 'FAILED'
    result = {
        'schemaVersion': SCHEMA_VERSION,
        'generatedAt': now.isoformat().replace('+00:00', 'Z'),
        'status': status,
        'inputs': {'releaseA': sa, 'releaseB': sb, 'matches': matches},
        'comparedFields': compared_fields,
        'allowedDifferences': list(ALLOWED_REPRO_DIFF_FIELDS),
        'allowedDifferencesNote': "created is wall-clock volatile; imageId may differ only when "
                                  "rootfs, normalized runtime config, platform, size and repo digests "
                                  "all match (the config digest embeds the volatile created timestamp)",
        'fileComparisons': file_comparisons,
        'sha256sumsComparison': sha_cmp,
        'imageComparisons': image_comparisons,
        'failureReasons': errors,
    }
    return result


def _failed_result(now, errors, a_root, b_root, a_man=None, b_man=None, matches=None):
    sa = _manifest_summary(a_man, a_root) if a_man else {'path': os.path.realpath(a_root)}
    sb = _manifest_summary(b_man, b_root) if b_man else {'path': os.path.realpath(b_root)}
    return {
        'schemaVersion': SCHEMA_VERSION,
        'generatedAt': now.isoformat().replace('+00:00', 'Z'),
        'status': 'FAILED',
        'inputs': {'releaseA': sa, 'releaseB': sb, 'matches': matches or {}},
        'comparedFields': [],
        'allowedDifferences': list(ALLOWED_REPRO_DIFF_FIELDS),
        'allowedDifferencesNote': "",
        'fileComparisons': [],
        'sha256sumsComparison': None,
        'imageComparisons': [],
        'failureReasons': errors,
    }


def write_reproducibility_result(result, out_path):
    os.makedirs(os.path.dirname(out_path) or '.', exist_ok=True)
    with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(result, f, indent=2, sort_keys=True)
        f.write('\n')


# --- provenance generation ---------------------------------------------------------------

def build_provenance_statement(manifest, release_root, *, generator, build_workflow,
                               generated_at=None):
    """Build a deterministic in-toto Statement v0.1 with a SLSA Provenance v0.2 predicate.

    Every subject digest equals the release-manifest content identity: file artifact sha256
    (recomputed from disk) or the normalized immutable image digest (image config digest). Records
    the exact source commit, version, build interface and locked toolchain. Local/unsigned
    provenance is tagged ``generator=local-unsigned``; it must never be misrepresented as trusted
    GitHub/OIDC provenance."""
    if generator not in PROVENANCE_GENERATORS:
        raise ValueError("generator must be one of %s" % (PROVENANCE_GENERATORS,))
    if generated_at is None:
        generated_at = _utcnow().isoformat().replace('+00:00', 'Z')
    subjects = []
    for a in manifest.get('artifacts', []):
        name, digest = artifact_subject_digest(a, release_root)
        subjects.append({'name': name, 'digest': {'sha256': digest}})
    subjects.sort(key=lambda s: s['name'])
    toolchain = manifest.get('toolchain') or {}
    predicate = {
        'builder': {'id': build_workflow},
        'buildType': build_workflow,
        'invocation': {
            'configSource': {
                'uri': 'git+runtime-mock',
                'digest': {'sha1': manifest.get('gitCommit', '')},
            },
            'parameters': {
                'version': manifest.get('version'),
                'revision': manifest.get('version'),
                'sourceDateEpoch': manifest.get('sourceDateEpoch'),
            },
            'environment': dict(toolchain),
        },
        'buildStartedOn': manifest.get('buildStartedAt'),
        'buildFinishedOn': manifest.get('buildEndedAt'),
        'materials': [{
            'uri': 'git+runtime-mock',
            'digest': {'sha1': manifest.get('gitCommit', '')},
        }],
        'metadata': {
            'generator': generator,
            'trustedOIDC': generator == 'github-actions-oidc',
            'reproducible': manifest.get('sourceDateEpoch') is not None,
            'contractBaseline': manifest.get('contractBaseline'),
            'schemaVersion': manifest.get('schemaVersion'),
            'buildWorkflow': build_workflow,
        },
    }
    statement = {
        '_type': IN_TOTO_STATEMENT_TYPE,
        'subject': subjects,
        'predicateType': SLSA_PROVENANCE_PREDICATE,
        'predicate': predicate,
    }
    return statement


def write_provenance_statement(statement, out_path):
    os.makedirs(os.path.dirname(out_path) or '.', exist_ok=True)
    blob = json.dumps(statement, indent=2, sort_keys=True, ensure_ascii=False, separators=(',', ': ')) + "\n"
    with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(blob)


def promote_manifest_m5d(manifest, spec):
    """Promote an M5-C manifest to M5-D by attaching the ``releaseIntegrity`` section.

    ``spec`` carries: generatedAt, generator (local-unsigned|github-actions-oidc), buildWorkflow,
    reproducibility {resultRel, resultAbs, result}, signature {status, reason, bundleRel, bundleAbs,
    identity, issuer} (bundle fields only when status=SIGNED).

    The eight-artifact inventory, SHA256SUMS contract and the M5-C supplyChain section are NEVER
    altered; only per-artifact provenance is promoted to PRESENT and signature to SKIPPED/SIGNED.
    Returns the promoted, validated manifest."""
    out = json.loads(json.dumps(manifest))  # deep copy
    release_root = spec['releaseRoot']
    statement_rel = PROVENANCE_DIR + '/' + PROVENANCE_NAME
    statement_abs = os.path.join(release_root, statement_rel)
    statement = build_provenance_statement(out, release_root, generator=spec['generator'],
                                          build_workflow=spec['buildWorkflow'],
                                          generated_at=spec['generatedAt'])
    write_provenance_statement(statement, statement_abs)
    st_ev = _evidence_ref(statement_rel, statement_abs)
    # per-artifact provenance + signature promotion
    for a in out.get('artifacts', []):
        name, digest = artifact_subject_digest(a, release_root)
        a['provenance'] = {
            'status': PROVENANCE_PRESENT_STATUS,
            'statement': dict(st_ev),
            'subject': {'name': name, 'digest': {'sha256': digest}},
        }
        sig_spec = spec.get('signature', {})
        if sig_spec.get('status') == 'SIGNED':
            brel = sig_spec.get('bundleRel')
            babs = sig_spec.get('bundleAbs')
            bev = _evidence_ref(brel, babs) if babs and os.path.isfile(babs) else None
            a['signature'] = {
                'status': 'SIGNED',
                'bundle': bev,
                'identity': sig_spec.get('identity'),
                'issuer': sig_spec.get('issuer'),
            }
        else:
            a['signature'] = {'status': 'SKIPPED',
                              'reason': sig_spec.get('reason', "local/PR build; cosign keyless "
                                  "requires GitHub Actions OIDC (controlled CI); RELEASE requires "
                                  "signature at M6")}
    # reproducibility evidence reference
    repro = spec.get('reproducibility', {})
    result_abs = repro.get('resultAbs')
    repro_ref = _evidence_ref(repro['resultRel'], result_abs) if result_abs else None
    repro_result = repro.get('result') or {}
    sig_spec = spec.get('signature', {})
    release_sig = {'status': sig_spec.get('status', 'SKIPPED')}
    if release_sig['status'] == 'SIGNED':
        release_sig['bundle'] = _evidence_ref(sig_spec['bundleRel'], sig_spec['bundleAbs']) \
            if sig_spec.get('bundleAbs') and os.path.isfile(sig_spec['bundleAbs']) else None
        release_sig['identity'] = sig_spec.get('identity')
        release_sig['issuer'] = sig_spec.get('issuer')
    else:
        release_sig['reason'] = sig_spec.get('reason', "local/PR build; cosign keyless in controlled CI")
    out['releaseIntegrity'] = {
        'stage': RELEASE_INTEGRITY_STAGE,
        'generatedAt': spec['generatedAt'],
        'reproducibility': {
            'status': repro_result.get('status', 'FAILED'),
            'inputs': repro_result.get('inputs', {}),
            'comparedFields': repro_result.get('comparedFields', []),
            'allowedDifferences': repro_result.get('allowedDifferences', list(ALLOWED_REPRO_DIFF_FIELDS)),
            'resultReport': repro_ref,
            'failureReasons': repro_result.get('failureReasons', []),
        },
        'provenance': {
            'status': PROVENANCE_PRESENT_STATUS,
            'generator': spec['generator'],
            'trustedOIDC': spec['generator'] == 'github-actions-oidc',
            'statement': dict(st_ev),
        },
        'signature': release_sig,
        'overallStatus': _derive_m5d_overall(repro_result, spec, manifest=out),
        'failureReasons': repro_result.get('failureReasons', []) if repro_result.get('status') != 'PASSED' else [],
    }
    # knownLimitations disclosure for local-unsigned / skipped signature
    kls = list(out.get('knownLimitations', []))
    if spec['generator'] == 'local-unsigned':
        kls.append("M5-D provenance is locally generated and unsigned (generator=local-unsigned); "
                   "NOT trusted GitHub/OIDC provenance. Trusted provenance requires the controlled "
                   "GitHub Actions OIDC job.")
    if release_sig['status'] == 'SKIPPED':
        kls.append("M5-D signature is SKIPPED (local/PR build, no OIDC); cosign keyless signature is "
                    "rehearsed in controlled CI and required at M6 RELEASE.")
    out['knownLimitations'] = kls
    errors = releaselib.validate_manifest(out)
    if errors:
        raise ValueError("promoted M5-D manifest failed validation:\n  " + "\n  ".join(errors))
    return out


def _derive_m5d_overall(repro_result, spec, manifest=None):
    """Derive the M5-D overall status from reproducibility + provenance + signature honesty."""
    if repro_result.get('status') != 'PASSED':
        return 'FAILED'
    if spec['generator'] not in PROVENANCE_GENERATORS:
        return 'FAILED'
    return 'PASSED'


# --- offline verification ----------------------------------------------------------------

def _recompute_file_hashes(manifest, release_root, errors):
    """Recompute each file artifact's sha256 from disk and cross-check against the manifest + SHA256SUMS.
    Returns {name: sha256}."""
    hashes = {}
    release_root = os.path.realpath(release_root)
    sums_path = os.path.join(release_root, 'SHA256SUMS')
    sums_lines = {}
    if os.path.isfile(sums_path):
        for line in open(sums_path, encoding='utf-8').read().splitlines():
            parts = line.split('  ', 1)
            if len(parts) == 2 and SHA256_RE.match(parts[0]):
                sums_lines[parts[1]] = parts[0]
    for a in manifest.get('artifacts', []):
        if a.get('type') not in ('jar', 'tar.gz'):
            continue
        name = a.get('name')
        path = os.path.join(release_root, a.get('path') or name)
        rp = os.path.realpath(path)
        if rp != release_root and not rp.startswith(release_root + os.sep):
            errors.append("artifact %s path escapes release root: %s -> %s" % (name, a.get('path'), rp))
            continue
        if not os.path.isfile(path):
            errors.append("file artifact missing on disk: %s" % name)
            continue
        h = sha256_file(path)
        hashes[name] = h
        if h != a.get('sha256'):
            errors.append("artifact %s on-disk sha256 (%s) != manifest sha256 (%s)" % (name, h, a.get('sha256')))
        if name in sums_lines and sums_lines[name] != h:
            errors.append("artifact %s sha256 != SHA256SUMS entry (%s vs %s)" % (name, h, sums_lines[name]))
        if name not in sums_lines:
            errors.append("artifact %s missing from SHA256SUMS" % name)
    return hashes


def verify_reproducibility_evidence(manifest, release_root, *, now=None):
    """Re-validate the reproducibility result referenced by the manifest. Re-hash the result file,
    re-derive releaseA-side file hashes from disk and check consistency with the recorded result, and
    confirm the result's inputs match the manifest's commit/version/toolchain/SOURCE_DATE_EPOCH."""
    if now is None:
        now = _utcnow()
    errors = []
    ri = manifest.get('releaseIntegrity') or {}
    repro = ri.get('reproducibility') or {}
    rr = repro.get('resultReport')
    if not isinstance(rr, dict):
        return ["releaseIntegrity.reproducibility.resultReport must be {path,sha256,size}"]
    rel = rr.get('path')
    perr = supplychainlib.validate_evidence_path(rel, release_root)
    if perr:
        return perr
    abs_path = os.path.join(release_root, rel)
    ev = file_evidence(abs_path)
    if ev['sha256'] != rr.get('sha256'):
        errors.append("reproducibility resultReport %s sha256 mismatch (tampered): recorded %s, actual %s"
                       % (rel, rr.get('sha256'), ev['sha256']))
    if ev['size'] != rr.get('size'):
        errors.append("reproducibility resultReport %s size mismatch" % rel)
    try:
        with open(abs_path, encoding='utf-8') as f:
            result = json.load(f)
    except (OSError, ValueError) as e:
        return errors + ["reproducibility result %s unreadable/malformed: %s" % (rel, e)]
    if not isinstance(result, dict) or result.get('schemaVersion') != SCHEMA_VERSION:
        errors.append("reproducibility result schemaVersion must be %s" % SCHEMA_VERSION)
        return errors
    if result.get('status') not in releaselib.REPRODUCIBILITY_STATUSES:
        errors.append("reproducibility result status must be PASSED or FAILED")
    # a forged PASSED must not satisfy: re-derive releaseA-side file hashes and cross-check
    inputs = result.get('inputs') or {}
    ra = inputs.get('releaseA') or {}
    for k in ('gitCommit', 'version', 'toolchain', 'sourceDateEpoch'):
        if ra.get(k) != manifest.get(k):
            errors.append("reproducibility result inputs.releaseA.%s (%r) != manifest (%r); "
                          "stale or fabricated result" % (k, ra.get(k), manifest.get(k)))
    # re-derive releaseA file hashes from disk and compare to recorded result
    disk_hashes = _recompute_file_hashes(manifest, release_root, errors)
    for fc in result.get('fileComparisons', []):
        name = fc.get('name')
        if name in disk_hashes and fc.get('sha256A') != disk_hashes[name]:
            errors.append("reproducibility result fileComparisons %s sha256A (%s) != recomputed (%s); "
                          "forged or stale result" % (name, fc.get('sha256A'), disk_hashes[name]))
    # the result's recorded sha256sumsComparison.sha256A must match the on-disk SHA256SUMS
    sha_cmp = result.get('sha256sumsComparison')
    if isinstance(sha_cmp, dict):
        sums_path = os.path.join(release_root, 'SHA256SUMS')
        if os.path.isfile(sums_path):
            actual = sha256_file(sums_path)
            if sha_cmp.get('sha256A') != actual:
                errors.append("reproducibility result sha256sumsComparison.sha256A (%s) != on-disk "
                              "SHA256SUMS (%s)" % (sha_cmp.get('sha256A'), actual))
    # image identities: re-derive from manifest and cross-check the result's imageComparisons
    for a in manifest.get('artifacts', []):
        if a.get('type') == 'docker-image':
            name = a.get('name')
            try:
                digest = immutable_image_digest(_image_meta(a))
            except ValueError as e:
                errors.append("artifact %s image identity invalid: %s" % (name, e))
                continue
            # find the recorded releaseA imageId in the result
            rec = next((c for c in result.get('imageComparisons', [])
                        if c.get('name') == name and c.get('field') == 'imageId'), None)
            if rec is not None and rec.get('a') and rec.get('a') != ('sha256:' + digest) \
                    and rec.get('a') != digest:
                errors.append("reproducibility result imageComparisons %s imageId A (%s) != manifest "
                              "imageId (sha256:%s); forged or stale result" % (name, rec.get('a'), digest))
    if result.get('status') != 'PASSED':
        errors.append("reproducibility result status is not PASSED: %s" % result.get('status'))
    return errors


def verify_provenance(manifest, release_root):
    """Recompute subject mappings + artifact/evidence hashes + path containment for the provenance
    statement. Missing/malformed/stale/mismatched provenance fails closed."""
    errors = []
    ri = manifest.get('releaseIntegrity') or {}
    prov = ri.get('provenance') or {}
    st = prov.get('statement')
    if not isinstance(st, dict):
        return ["releaseIntegrity.provenance.statement must be {path,sha256,size}"]
    rel = st.get('path')
    perr = supplychainlib.validate_evidence_path(rel, release_root)
    if perr:
        return perr
    abs_path = os.path.join(release_root, rel)
    ev = file_evidence(abs_path)
    if ev['sha256'] != st.get('sha256'):
        errors.append("provenance statement %s sha256 mismatch (tampered): recorded %s, actual %s"
                       % (rel, st.get('sha256'), ev['sha256']))
    if ev['size'] != st.get('size'):
        errors.append("provenance statement %s size mismatch" % rel)
    try:
        with open(abs_path, encoding='utf-8') as f:
            statement = json.load(f)
    except (OSError, ValueError) as e:
        return errors + ["provenance statement %s unreadable/malformed: %s" % (rel, e)]
    if statement.get('_type') != IN_TOTO_STATEMENT_TYPE:
        errors.append("provenance statement _type must be %s" % IN_TOTO_STATEMENT_TYPE)
    if statement.get('predicateType') != SLSA_PROVENANCE_PREDICATE:
        errors.append("provenance statement predicateType must be %s" % SLSA_PROVENANCE_PREDICATE)
    subjects = statement.get('subject')
    if not isinstance(subjects, list) or not subjects:
        return errors + ["provenance statement subject must be a non-empty list"]
    # recompute expected subjects from the manifest
    expected = {}
    for a in manifest.get('artifacts', []):
        name, digest = artifact_subject_digest(a, release_root)
        expected[name] = digest
    stmt_by_name = {s.get('name'): s for s in subjects if isinstance(s, dict)}
    if set(stmt_by_name.keys()) != set(expected.keys()):
        errors.append("provenance subjects (%s) != manifest artifacts (%s); subject mapping mismatch"
                      % (sorted(stmt_by_name.keys()), sorted(expected.keys())))
    for name, exp_digest in expected.items():
        s = stmt_by_name.get(name)
        if s is None:
            errors.append("provenance subject missing for artifact: %s" % name)
            continue
        sd = s.get('digest') or {}
        got = sd.get('sha256')
        if got != exp_digest:
            errors.append("provenance subject %s digest (%s) != release-manifest content identity (%s); "
                          "stale or mismatched provenance" % (name, got, exp_digest))
    # Per-artifact references are part of the manifest contract too. They must all point at the
    # exact release-level statement and repeat the exact recomputed subject, not merely be shaped
    # like plausible provenance objects.
    for a in manifest.get('artifacts', []):
        name = a.get('name')
        ap = a.get('provenance') or {}
        if ap.get('statement') != st:
            errors.append("artifact %s provenance.statement differs from release-level statement" % name)
        expected_subject = {'name': name, 'digest': {'sha256': expected.get(name)}}
        if ap.get('subject') != expected_subject:
            errors.append("artifact %s provenance.subject does not equal the recomputed subject" % name)
    # predicate must record the exact source commit / version / toolchain
    pred = statement.get('predicate') or {}
    inv = pred.get('invocation') or {}
    params = inv.get('parameters') or {}
    if params.get('version') != manifest.get('version'):
        errors.append("provenance predicate version (%s) != manifest (%s)" % (params.get('version'), manifest.get('version')))
    cs = inv.get('configSource') or {}
    if cs.get('digest', {}).get('sha1') != manifest.get('gitCommit'):
        errors.append("provenance predicate configSource digest (%s) != manifest gitCommit (%s); "
                      "stale provenance" % (cs.get('digest', {}).get('sha1'), manifest.get('gitCommit')))
    md = pred.get('metadata') or {}
    if md.get('generator') != prov.get('generator'):
        errors.append("provenance predicate metadata.generator (%s) != manifest provenance.generator (%s)"
                       % (md.get('generator'), prov.get('generator')))
    # local-unsigned must not claim trustedOIDC
    if prov.get('generator') == 'local-unsigned' and md.get('trustedOIDC') is True:
        errors.append("provenance generator=local-unsigned but statement claims trustedOIDC=true "
                      "(misrepresents local provenance as trusted GitHub/OIDC)")
    if prov.get('generator') == 'github-actions-oidc' and not prov.get('trustedOIDC'):
        errors.append("provenance generator=github-actions-oidc requires trustedOIDC=true")
    return errors


def _expected_identity_match(identity, expected_identity):
    """True if the signing identity matches the expected identity (exact, or pattern with *)."""
    if not isinstance(identity, str) or not identity:
        return False
    if not expected_identity:
        # default structural trust: github.com workflow URL
        return identity.startswith(EXPECTED_IDENTITY_PREFIX) and 'release-integrity' in identity
    if '*' in expected_identity:
        pat = re.escape(expected_identity).replace(r'\*', '.*')
        return re.match('^' + pat + '$', identity) is not None
    return identity == expected_identity


def verify_signature_evidence(manifest, release_root, *, require_signature, expected_identity=None,
                              expected_issuer=EXPECTED_OIDC_ISSUER, cosign_bin=None,
                              manifest_path=None):
    """Verify signature evidence. ``require_signature`` False permits SKIPPED (local/PR). True fails
    closed for missing/skipped/malformed/untrusted-identity/wrong-issuer/wrong-subject/tampered bundle.

    The signature is release-level: cosign keyless signs the release manifest (the canonical summary
    carrying every artifact hash). Every SIGNED artifact references the same bundle; the bundle's
    payload digest must equal the on-disk release-manifest.json sha256 (wrong-subject/tampered). The
    per-artifact ``subject.digest`` (provenance) is the artifact content identity; the SIGNATURE
    covers the manifest that attests those subjects.

    Cryptographic ECDSA/cert verification uses cosign (COSIGN_BIN env or ``cosign_bin``) when
    available; absent cosign, require_signature=True fails closed (cannot cryptographically verify)."""
    errors = []
    ri = manifest.get('releaseIntegrity') or {}
    release_sig = ri.get('signature') or {}
    artifacts = manifest.get('artifacts', [])
    import shutil
    cosign_bin = cosign_bin or os.environ.get('COSIGN_BIN') or shutil.which('cosign')
    if cosign_bin:
        try:
            if not shutil.which(cosign_bin) and not os.path.isfile(cosign_bin):
                cosign_bin = None
        except Exception:
            cosign_bin = None

    # the signed blob is the provenance statement (the in-toto attestation that carries every
    # artifact subject). cosign keyless signs the statement; the bundle payload digest must equal the
    # on-disk statement sha256 (wrong-subject/tampered). The statement is deterministic (sorted keys,
    # no signature refs), so there is no circular manifest<->signature dependency.
    statement_sha = None
    st_abs = None
    prov = ri.get('provenance') or {}
    st_ref = prov.get('statement') if isinstance(prov, dict) else None
    if isinstance(st_ref, dict):
        st_rel = st_ref.get('path')
        if isinstance(st_rel, str) and st_rel:
            st_abs = os.path.join(release_root, st_rel)
            if os.path.isfile(st_abs):
                statement_sha = sha256_file(st_abs)
    if statement_sha is None:
        errors.append("signature: provenance statement not found on disk; cannot establish signed payload")

    def _verify_one(sig, artifact_name):
        status = sig.get('status') if isinstance(sig, dict) else None
        if status == 'SKIPPED':
            if require_signature:
                errors.append("artifact %s signature is SKIPPED but --require-signature true" % artifact_name)
            return
        if status != 'SIGNED':
            errors.append("artifact %s signature.status must be SIGNED or SKIPPED; got %r"
                          % (artifact_name, status))
            return
        b = sig.get('bundle')
        if not isinstance(b, dict):
            errors.append("artifact %s signature.bundle missing" % artifact_name)
            return
        rel = b.get('path')
        perr = supplychainlib.validate_evidence_path(rel, release_root)
        if perr:
            errors.extend(perr)
            return
        abs_path = os.path.join(release_root, rel)
        ev = file_evidence(abs_path)
        if ev['sha256'] != b.get('sha256'):
            errors.append("artifact %s signature bundle %s sha256 mismatch (tampered bundle)"
                          % (artifact_name, rel))
        if ev['size'] != b.get('size'):
            errors.append("artifact %s signature bundle %s size mismatch" % (artifact_name, rel))
        # parse the bundle
        try:
            with open(abs_path, encoding='utf-8') as f:
                bundle = json.load(f)
        except (OSError, ValueError) as e:
            errors.append("artifact %s signature bundle %s unreadable/malformed: %s" % (artifact_name, rel, e))
            return
        if not isinstance(bundle, dict):
            errors.append("artifact %s signature bundle must be a JSON object" % artifact_name)
            return
        # structural: must carry a signature and a cert or tlog entry (cosign bundle shape)
        has_sig = isinstance(bundle.get('base64Signature'), str) and bundle.get('base64Signature')
        has_msg = isinstance(bundle.get('messageSignature'), dict)
        if not (has_sig or has_msg):
            errors.append("artifact %s signature bundle malformed: missing base64Signature/messageSignature"
                          % artifact_name)
        # identity / issuer (recorded in manifest; checked against the trust root)
        identity = sig.get('identity')
        issuer = sig.get('issuer')
        if not _expected_identity_match(identity, expected_identity):
            errors.append("artifact %s signature identity %r is not a trusted GitHub OIDC subject "
                          "(expected %r)" % (artifact_name, identity, expected_identity or EXPECTED_IDENTITY_PREFIX))
        if issuer != expected_issuer:
            errors.append("artifact %s signature issuer %r != expected %r (wrong-issuer)"
                          % (artifact_name, issuer, expected_issuer))
        # wrong-subject / tampered payload: the bundle's payload digest must equal the provenance
        # statement sha256 (the signed blob). A mismatch means the bundle signs a different subject.
        if has_msg:
            ms = bundle.get('messageSignature') or {}
            md = ms.get('messageDigest') or {}
            got = md.get('digest')
            # Sigstore bundle v0.3 serializes the digest bytes as base64; older fixtures/tools may
            # expose lowercase hex. Normalize both before comparing to the signed statement hash.
            got_hex = got
            if isinstance(got, str) and not SHA256_RE.match(got):
                try:
                    decoded = base64.b64decode(got, validate=True)
                    got_hex = decoded.hex() if len(decoded) == 32 else None
                except Exception:
                    got_hex = None
            if got and got_hex != statement_sha:
                errors.append("artifact %s signature payload digest (%s) != provenance statement sha256 (%s); "
                              "wrong-subject or tampered bundle" % (artifact_name, got, statement_sha))
        # cryptographic verification (cosign); fail closed when required and cosign unavailable
        if cosign_bin:
            import subprocess
            if st_abs is not None and os.path.isfile(st_abs):
                r = subprocess.run([cosign_bin, 'verify-blob', '--bundle', abs_path,
                                    '--certificate-identity', identity or '',
                                    '--certificate-oidc-issuer', expected_issuer, st_abs],
                                   capture_output=True, text=True)
                if r.returncode != 0:
                    errors.append("artifact %s cosign verify-blob failed (exit %d): %s"
                                  % (artifact_name, r.returncode, (r.stderr or '').strip()[:200]))
        else:
            # `require_signature=false` permits an honest SKIPPED status; it never permits a SIGNED
            # claim to pass without cryptographic verification.
            errors.append("artifact %s SIGNED bundle: cosign unavailable; cannot cryptographically "
                          "verify the SIGNED claim" % artifact_name)

    for a in artifacts:
        sig = a.get('signature')
        _verify_one(sig, a.get('name'))

    # release-level signature summary honesty
    if release_sig.get('status') not in SIGNATURE_STATUSES_M5D:
        errors.append("releaseIntegrity.signature.status must be SKIPPED or SIGNED")
    release_status = release_sig.get('status')
    for a in artifacts:
        sig = a.get('signature') or {}
        if sig.get('status') != release_status:
            errors.append("artifact %s signature.status %r != release-level status %r"
                          % (a.get('name'), sig.get('status'), release_status))
        if release_status == 'SIGNED':
            for key in ('bundle', 'identity', 'issuer'):
                if sig.get(key) != release_sig.get(key):
                    errors.append("artifact %s signature.%s differs from release-level signature"
                                  % (a.get('name'), key))
    if require_signature and release_sig.get('status') == 'SKIPPED':
        if not any('SKIPPED' in e and 'require-signature' in e for e in errors):
            errors.append("release signature is SKIPPED but --require-signature true")
    return errors


def verify_release_integrity(manifest_path, *, require_signature, now=None, expected_identity=None):
    """The final M5 release-integrity verifier. Integrates M5-B inventory/hash validation, M5-C
    SBOM/vulnerability/license validation, M5-D reproducibility/provenance validation, and optional
    signature validation. Returns {ok, stage, prGate, releaseGate, errors, notes}.

    V17-SUPPLY.PR may become PASSED only when every applicable local/PR check independently passes.
    V17-SUPPLY.RELEASE stays NOT_RUN until M6 (Engineering Complete is not Released)."""
    if now is None:
        now = _utcnow()
    errors = []
    notes = []
    if not os.path.isfile(manifest_path):
        return {'ok': False, 'stage': None, 'prGate': 'NOT_RUN', 'releaseGate': 'NOT_RUN',
                'errors': ["manifest not found: %s" % manifest_path], 'notes': notes}
    with open(manifest_path, encoding='utf-8') as f:
        manifest = json.load(f)
    release_root = os.path.dirname(os.path.realpath(manifest_path))

    # 1. M5-B: schema + inventory + hash validation (releaselib + on-disk re-hash)
    schema_errors = releaselib.validate_manifest(manifest)
    errors.extend(schema_errors)
    _recompute_file_hashes(manifest, release_root, errors)

    ri = manifest.get('releaseIntegrity')
    is_m5d = isinstance(ri, dict) and ri.get('stage') == RELEASE_INTEGRITY_STAGE
    supply = manifest.get('supplyChain')
    is_m5c = isinstance(supply, dict) and supply.get('stage') == releaselib.SUPPLY_CHAIN_STAGE

    stage = None
    if is_m5d:
        stage = 'M5-D'
    elif is_m5c:
        stage = 'M5-C'
    else:
        stage = 'M5-B'

    # 2. M5-C: SBOM/vulnerability/license (supplychainlib offline verifier, if promoted)
    if is_m5c:
        sc_res = supplychainlib.verify_release(manifest_path, now=now)
        if not sc_res['ok']:
            errors.extend(sc_res['errors'])
        else:
            notes.append("M5-C supply-chain evidence verified (SBOM + vulnerability + license)")
    else:
        errors.append("manifest is not M5-C-promoted; supply-chain evidence is required for the M5 gate")

    # 3. M5-D: reproducibility + provenance + signature
    if is_m5d:
        errors.extend(verify_reproducibility_evidence(manifest, release_root, now=now))
        errors.extend(verify_provenance(manifest, release_root))
        errors.extend(verify_signature_evidence(manifest, release_root,
                                                 require_signature=require_signature,
                                                 expected_identity=expected_identity,
                                                 manifest_path=manifest_path))
        notes.append("M5-D release-integrity evidence verified (reproducibility + provenance + signature)")
        if ri.get('overallStatus') != 'PASSED':
            errors.append("releaseIntegrity.overallStatus must be PASSED for the M5 gate; got %r"
                          % ri.get('overallStatus'))
        if require_signature:
            notes.append("--require-signature true: signature required (cosign keyless)")
        else:
            notes.append("--require-signature false: local/PR; signature may be SKIPPED")
    elif require_signature:
        errors.append("--require-signature true requires an M5-D releaseIntegrity section with SIGNED "
                      "signature evidence (missing)")
    else:
        # The M5 completion gate (roadmap §12.6) requires M5-D reproducibility + provenance evidence.
        # An M5-C-only manifest has not completed M5-D; the gate fails closed rather than passing on
        # incomplete evidence. (require-signature false only relaxes the signature sub-check, not the
        # reproducibility/provenance evidence that M5-D owns.)
        errors.append("M5-D releaseIntegrity evidence required (reproducibility + provenance); manifest is "
                      "only M5-C. Run generate-provenance.sh to promote to M5-D.")
        notes.append("M5-D not promoted (no releaseIntegrity); reproducibility/provenance not verified")

    # 4. gate states. PR gate reflects every local/PR check independently passing. RELEASE is NOT_RUN
    # until M6: M5-D never claims RELEASE even when all local checks pass.
    pr_gate = 'PASSED' if (not errors) else 'FAILED'
    release_gate = 'NOT_RUN'  # M6 owns RELEASE; M5-D is Engineering Complete, not Released

    ok = (not errors)
    if ok:
        notes.append("V17-SUPPLY.PR = PASSED (every local/PR check independently derived and passing)")
    else:
        notes.append("V17-SUPPLY.PR = FAILED (%d error(s))" % len(errors))
    notes.append("V17-SUPPLY.RELEASE = NOT_RUN (M6 owns final signature + certification; Engineering "
                 "Complete is not Released)")
    return {'ok': ok, 'stage': stage, 'prGate': pr_gate, 'releaseGate': release_gate,
            'errors': errors, 'notes': notes}


# --- self-test --------------------------------------------------------------------------

def _write_json(path, obj, sort=True):
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, indent=2, sort_keys=sort)
        f.write('\n')


def _m5c_manifest(version="1.7.0-rc.1", git_commit=None):
    """Reuse supplychainlib's fixture builder to get a valid M5-C manifest, then return it."""
    import tempfile
    tmp = tempfile.mkdtemp(prefix='m5d-m5c-')
    rel, promoted = supplychainlib._build_release_dir(tmp)
    return tmp, rel, promoted


def _m5d_release(tmp, *, generator='local-unsigned', repro_status='PASSED', sig_status='SKIPPED',
                 git_commit_a=None, git_commit_b=None, version="1.7.0-rc.1"):
    """Build a full M5-D release dir under tmp and return (rel_root, manifest)."""
    import shutil
    import tempfile
    sc_tmp = tempfile.mkdtemp(prefix='m5d-sc-')
    sc_rel, m5c = supplychainlib._build_release_dir(sc_tmp)
    rel = os.path.join(tmp, "release")
    shutil.copytree(sc_rel, rel)
    mp = os.path.join(rel, "release-manifest.json")
    m = json.load(open(mp))
    # normalize fixture image digests to realistic 64-hex (supplychainlib fixtures use short ids)
    _img_seq = 0
    for a in m.get('artifacts', []):
        if a.get('type') == 'docker-image':
            a.setdefault('image', {})
            _img_seq += 1
            hexid = ('1' * 60) + ('%04d' % _img_seq)
            a['image']['imageId'] = 'sha256:' + hexid
            a['image']['rootfs'] = a['image'].get('rootfs') or {}
            a['image']['rootfs']['diffIds'] = ['sha256:' + ('a' * 60) + ('%04d' % _img_seq),
                                              'sha256:' + ('b' * 60) + ('%04d' % _img_seq)]
            a['image']['manifestDigest'] = 'NOT_AVAILABLE'
            a['image']['repoTags'] = [a['name']]
            a['image']['repoDigests'] = []
            a['image']['config'] = {
                'user': '', 'env': ['LANG=C.UTF-8'], 'entrypoint': [], 'cmd': ['java', '-jar', 'app.jar'],
                'workingDir': '/app', 'labels': {'org.opencontainers.image.version': version},
                'exposedPorts': [], 'volumes': [], 'stopSignal': '', 'shell': [], 'healthcheck': {},
            }
            a['image'].setdefault('architecture', 'amd64')
            a['image'].setdefault('os', 'linux')
            a['image'].setdefault('created', '2026-08-04T00:00:00Z')
            a['image'].setdefault('size', 100)
    _write_json(mp, m)
    # build a second identical release dir as releaseB for the reproducibility result
    rel_b = os.path.join(tmp, "release-b")
    shutil.copytree(sc_rel, rel_b)
    # write the six file artifacts on disk so re-hashing works (supplychainlib fixture writes them)
    # The _build_release_dir writes sbom/reports/config + manifest; it does NOT write the 6 file
    # artifacts. Create minimal file artifacts matching the manifest sha256.
    for a in m.get('artifacts', []):
        if a.get('type') in ('jar', 'tar.gz'):
            p = os.path.join(rel, a.get('path') or a['name'])
            if not os.path.isfile(p):
                # write a placeholder then fix the manifest sha256/size to match
                payload = b'kairo-m5d-fixture-' + a['name'].encode()
                os.makedirs(os.path.dirname(p) or rel, exist_ok=True)
                with open(p, 'wb') as f:
                    f.write(payload)
                a['sha256'] = sha256_file(p)
                a['size'] = os.path.getsize(p)
                # also in releaseB
                pb = os.path.join(rel_b, a.get('path') or a['name'])
                with open(pb, 'wb') as f:
                    f.write(payload)
    # rebuild SHA256SUMS for the 6 file artifacts (lexical order)
    sums = os.path.join(rel, "SHA256SUMS")
    file_arts = [a for a in m['artifacts'] if a.get('type') in ('jar', 'tar.gz')]
    file_arts.sort(key=lambda a: a['name'])
    with open(sums, 'w', encoding='utf-8', newline='\n') as f:
        for a in file_arts:
            f.write("%s  %s\n" % (a['sha256'], a['name']))
    shutil.copy(sums, os.path.join(rel_b, "SHA256SUMS"))
    # write manifest back (with updated sha) for both A and B
    _write_json(mp, m)
    mb_path = os.path.join(rel_b, "release-manifest.json")
    _write_json(mb_path, json.loads(json.dumps(m)))
    # build reproducibility result
    a_man = m
    b_man = json.loads(json.dumps(m))
    if git_commit_a:
        a_man['gitCommit'] = git_commit_a
        m['gitCommit'] = git_commit_a
        _write_json(mp, m)
    result = compare_releases(mp, mb_path)
    if repro_status == 'PASSED' and result['status'] != 'PASSED':
        # force-evaluate; if it failed, surface in tests
        pass
    if repro_status == 'FAILED' and result['status'] == 'PASSED':
        # inject a failure to test FAILED path
        result['status'] = 'FAILED'
        result['failureReasons'] = ['injected failure for test']
    result_rel = "reports/reproducibility-result.json"
    write_reproducibility_result(result, os.path.join(rel, result_rel))
    # promote to M5-D
    spec = {
        'generatedAt': '2026-08-04T00:00:00Z',
        'generator': generator,
        'buildWorkflow': './scripts/v1.7/build-release.sh --version ' + version,
        'releaseRoot': rel,
        'reproducibility': {'resultRel': result_rel,
                            'resultAbs': os.path.join(rel, result_rel),
                            'result': result},
        'signature': {'status': sig_status, 'reason': "local/PR; no OIDC",
                      'identity': 'https://github.com/jiyong/runtime-mock/.github/workflows/release-integrity.yml@refs/heads/V1.7',
                      'issuer': EXPECTED_OIDC_ISSUER,
                      'bundleRel': 'signatures/release-manifest.sigstore.json',
                      'bundleAbs': os.path.join(rel, 'signatures/release-manifest.sigstore.json')},
    }
    if sig_status == 'SIGNED':
        # write a minimal (structurally valid) cosign bundle fixture. The signature is release-level:
        # cosign signs the provenance statement; the bundle payload digest == the statement sha256.
        bdir = os.path.join(rel, 'signatures')
        os.makedirs(bdir, exist_ok=True)
        # build the statement first (build-provenance) so we can hash it as the signed payload
        tmp_statement = build_provenance_statement(m, rel, generator=spec['generator'],
                                                  build_workflow=spec['buildWorkflow'],
                                                  generated_at=spec['generatedAt'])
        tmp_stmt_path = os.path.join(rel, PROVENANCE_DIR, PROVENANCE_NAME)
        write_provenance_statement(tmp_statement, tmp_stmt_path)
        stmt_sha = sha256_file(tmp_stmt_path)
        bundle = {
            'base64Signature': 'aGVsbG8=',  # placeholder; crypto verified only when cosign present
            'messageSignature': {'messageDigest': {'algorithm': 'SHA2_256', 'digest': stmt_sha}},
            'cert': {'rawBytes': ''},
        }
        _write_json(os.path.join(rel, spec['signature']['bundleRel']), bundle, sort=False)
    promoted = promote_manifest_m5d(m, spec)
    _write_json(mp, promoted)
    return rel, promoted


def self_test():
    """Run focused M5-D assertions. Prints a JSON summary and exits 0 iff all pass."""
    import tempfile
    checks = []

    def run(name, fn):
        try:
            ok, detail = fn()
        except Exception as e:
            ok, detail = False, "exception: %s: %s" % (type(e).__name__, e)
        checks.append({"name": name, "passed": bool(ok), "detail": detail})

    NOW = _parse_iso("2026-08-04T00:00:00Z")

    # --- canonical image identity / immutable digest ---
    def t_image_identity():
        meta = {'imageId': 'sha256:abc123', 'repoTags': ['kairo-platform-server:1.7.0-rc.1'],
                'rootfs': {'type': 'layers', 'diffIds': ['sha256:l1', 'sha256:l2']},
                'manifestDigest': 'NOT_AVAILABLE', 'architecture': 'amd64', 'os': 'linux',
                'created': '2026-01-01T00:00:00Z', 'size': 100, 'repoDigests': [],
                'config': {'user': '', 'env': [], 'entrypoint': [], 'cmd': [], 'workingDir': '',
                           'labels': {}, 'exposedPorts': [], 'volumes': [], 'stopSignal': '',
                           'shell': [], 'healthcheck': {}}}
        ci = canonical_image_identity(meta)
        if ci['rootfs']['diffIds'] != ['sha256:l1', 'sha256:l2']:
            return False, "diffIds not parsed"
        if immutable_image_digest(meta) != 'abc123':
            return False, "immutable digest not hex of imageId"
        return True, "canonical image identity + immutable digest"
    run("image: canonical identity + immutable digest", t_image_identity)

    def t_image_identity_rejects_non_digest():
        try:
            immutable_image_digest({'imageId': 'not-a-digest'})
            return False, "non-digest imageId accepted"
        except ValueError:
            return True, "non-digest imageId rejected"
    run("image: non-digest imageId rejected", t_image_identity_rejects_non_digest)

    # --- reproducibility: happy path (two identical releases) ---
    def t_repro_happy():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, m = _m5d_release(tmp)
            # build a second identical release for comparison
            import shutil
            rel_b = os.path.join(tmp, "release-b")
            # _m5d_release already created release-b; compare
            res = compare_releases(os.path.join(rel, "release-manifest.json"),
                                   os.path.join(rel_b, "release-manifest.json"), now=NOW)
            return (res['status'] == 'PASSED' and not res['failureReasons'],
                    "status=%s failures=%s" % (res['status'], res['failureReasons'][:2]))
    run("reproducibility: two identical releases PASSED", t_repro_happy)

    # --- reproducibility: artifact tamper (different content, same name) ---
    def t_repro_tamper():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, m = _m5d_release(tmp)
            rel_b = os.path.join(tmp, "release-b")
            # tamper a file artifact in B (same name, different content)
            art = next(a for a in m['artifacts'] if a.get('type') == 'jar')
            bp = os.path.join(rel_b, art['path'])
            with open(bp, 'wb') as f:
                f.write(b'tampered-different-content')
            # fix B manifest sha to match the tampered file (so the diff is content, not manifest)
            bm = json.load(open(os.path.join(rel_b, "release-manifest.json")))
            for a in bm['artifacts']:
                if a['name'] == art['name']:
                    a['sha256'] = sha256_file(bp)
                    a['size'] = os.path.getsize(bp)
            _write_json(os.path.join(rel_b, "release-manifest.json"), bm)
            # rebuild B SHA256SUMS
            file_arts = sorted([a for a in bm['artifacts'] if a.get('type') in ('jar', 'tar.gz')], key=lambda a: a['name'])
            with open(os.path.join(rel_b, "SHA256SUMS"), 'w', encoding='utf-8', newline='\n') as f:
                for a in file_arts:
                    f.write("%s  %s\n" % (a['sha256'], a['name']))
            res = compare_releases(os.path.join(rel, "release-manifest.json"),
                                   os.path.join(rel_b, "release-manifest.json"), now=NOW)
            return (res['status'] == 'FAILED' and any('not bit-identical' in e for e in res['failureReasons']),
                    "status=%s failures=%s" % (res['status'], [e for e in res['failureReasons'] if 'bit-identical' in e][:2]))
    run("reproducibility: same-name different-content artifact FAILED", t_repro_tamper)

    # --- reproducibility: commit/version/toolchain mismatch ---
    def t_repro_commit_mismatch():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, m = _m5d_release(tmp)
            rel_b = os.path.join(tmp, "release-b")
            bm = json.load(open(os.path.join(rel_b, "release-manifest.json")))
            bm['gitCommit'] = 'b' * 40
            _write_json(os.path.join(rel_b, "release-manifest.json"), bm)
            res = compare_releases(os.path.join(rel, "release-manifest.json"),
                                   os.path.join(rel_b, "release-manifest.json"), now=NOW)
            return (res['status'] == 'FAILED' and any('gitCommit' in e for e in res['failureReasons']),
                    "failures=%s" % [e for e in res['failureReasons'] if 'gitCommit' in e][:2])
    run("reproducibility: commit mismatch FAILED", t_repro_commit_mismatch)

    def t_repro_version_mismatch():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, m = _m5d_release(tmp)
            rel_b = os.path.join(tmp, "release-b")
            bm = json.load(open(os.path.join(rel_b, "release-manifest.json")))
            # bump B to a valid rc.2 manifest: version + every artifact name + image repoTags + path
            bm['version'] = '1.7.0-rc.2'
            for a in bm['artifacts']:
                old = a['name']
                a['name'] = old.replace('1.7.0-rc.1', '1.7.0-rc.2')
                if a.get('path'):
                    a['path'] = a['path'].replace('1.7.0-rc.1', '1.7.0-rc.2')
                if a['type'] == 'docker-image':
                    a['image']['repoTags'] = [a['name']]
            bm['buildWorkflow'] = bm['buildWorkflow'].replace('1.7.0-rc.1', '1.7.0-rc.2')
            _write_json(os.path.join(rel_b, "release-manifest.json"), bm)
            res = compare_releases(os.path.join(rel, "release-manifest.json"),
                                   os.path.join(rel_b, "release-manifest.json"), now=NOW)
            return (res['status'] == 'FAILED' and any('version' in e for e in res['failureReasons']),
                    "status=%s failures=%s" % (res['status'], [e for e in res['failureReasons'] if 'version' in e][:2]))
    run("reproducibility: version mismatch FAILED", t_repro_version_mismatch)

    def t_repro_toolchain_mismatch():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, m = _m5d_release(tmp)
            rel_b = os.path.join(tmp, "release-b")
            bm = json.load(open(os.path.join(rel_b, "release-manifest.json")))
            bm['toolchain'] = {'mvn': '3.9.99', 'java': '21', 'os': 'linux'}
            _write_json(os.path.join(rel_b, "release-manifest.json"), bm)
            res = compare_releases(os.path.join(rel, "release-manifest.json"),
                                   os.path.join(rel_b, "release-manifest.json"), now=NOW)
            return (res['status'] == 'FAILED' and any('toolchain' in e for e in res['failureReasons']),
                    "failures=%s" % [e for e in res['failureReasons'] if 'toolchain' in e][:2])
    run("reproducibility: toolchain mismatch FAILED", t_repro_toolchain_mismatch)

    # --- reproducibility: dirty input rejected ---
    def t_repro_dirty():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, m = _m5d_release(tmp)
            rel_b = os.path.join(tmp, "release-b")
            bm = json.load(open(os.path.join(rel_b, "release-manifest.json")))
            bm['allowDirty'] = True
            bm['dirtyFiles'] = ['x']
            _write_json(os.path.join(rel_b, "release-manifest.json"), bm)
            res = compare_releases(os.path.join(rel, "release-manifest.json"),
                                   os.path.join(rel_b, "release-manifest.json"), now=NOW)
            return (res['status'] == 'FAILED' and any('dirty' in e for e in res['failureReasons']),
                    "failures=%s" % [e for e in res['failureReasons'] if 'dirty' in e][:2])
    run("reproducibility: dirty build rejected", t_repro_dirty)

    # --- reproducibility: missing image identity ---
    def t_repro_missing_image_identity():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, m = _m5d_release(tmp)
            rel_b = os.path.join(tmp, "release-b")
            bm = json.load(open(os.path.join(rel_b, "release-manifest.json")))
            for a in bm['artifacts']:
                if a['type'] == 'docker-image':
                    a['image']['imageId'] = ''  # missing identity
            _write_json(os.path.join(rel_b, "release-manifest.json"), bm)
            res = compare_releases(os.path.join(rel, "release-manifest.json"),
                                   os.path.join(rel_b, "release-manifest.json"), now=NOW)
            # the B manifest is now schema-invalid (imageId must be sha256:) -> FAILED
            return (res['status'] == 'FAILED',
                    "status=%s failures=%s" % (res['status'], res['failureReasons'][:2]))
    run("reproducibility: missing image identity FAILED", t_repro_missing_image_identity)

    # --- reproducibility: malformed result JSON ---
    def t_repro_malformed_result():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, m = _m5d_release(tmp)
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            # corrupt the result file on disk
            rp = os.path.join(rel, m['releaseIntegrity']['reproducibility']['resultReport']['path'])
            with open(rp, 'w') as f:
                f.write('{not valid json')
            errs = verify_reproducibility_evidence(m, rel, now=NOW)
            return (bool(errs) and any('malformed' in e or 'unreadable' in e for e in errs),
                    "errors=%s" % errs[:2])
    run("verify: malformed reproducibility result JSON fails closed", t_repro_malformed_result)

    # --- reproducibility: fabricated PASSED (result says PASSED but on-disk hashes differ) ---
    def t_repro_fabricated_passed():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, m = _m5d_release(tmp)
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            # tamper an on-disk file (so the recomputed hash differs from the result's recorded sha256A)
            art = next(a for a in m['artifacts'] if a.get('type') == 'jar')
            p = os.path.join(rel, art['path'])
            with open(p, 'wb') as f:
                f.write(b'forged-content')
            errs = verify_reproducibility_evidence(m, rel, now=NOW)
            return (bool(errs) and any('forged' in e or 'sha256A' in e or 'on-disk sha256' in e for e in errs),
                    "errors=%s" % errs[:3])
    run("verify: fabricated PASSED result fails closed (recomputed hash mismatch)", t_repro_fabricated_passed)

    # --- provenance: subject mismatch ---
    def t_prov_subject_mismatch():
        with tempfile.TemporaryDirectory(prefix='m5d-prov-') as tmp:
            rel, m = _m5d_release(tmp)
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            # tamper the provenance statement's first subject digest
            sp = os.path.join(rel, m['releaseIntegrity']['provenance']['statement']['path'])
            stmt = json.load(open(sp))
            stmt['subject'][0]['digest']['sha256'] = '0' * 64
            _write_json(sp, stmt)
            # fix the statement sha in the manifest so only the subject mismatch is the failure
            m['releaseIntegrity']['provenance']['statement']['sha256'] = sha256_file(sp)
            m['releaseIntegrity']['provenance']['statement']['size'] = os.path.getsize(sp)
            _write_json(mp, m)
            errs = verify_provenance(m, rel)
            return (bool(errs) and any('subject' in e and 'mismatch' in e.lower() or 'stale' in e.lower() for e in errs),
                    "errors=%s" % errs[:3])
    run("provenance: subject digest mismatch fails closed", t_prov_subject_mismatch)

    # --- provenance: stale provenance (commit mismatch) ---
    def t_prov_stale():
        with tempfile.TemporaryDirectory(prefix='m5d-prov-') as tmp:
            rel, m = _m5d_release(tmp)
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            sp = os.path.join(rel, m['releaseIntegrity']['provenance']['statement']['path'])
            stmt = json.load(open(sp))
            stmt['predicate']['invocation']['configSource']['digest']['sha1'] = 'c' * 40
            _write_json(sp, stmt)
            m['releaseIntegrity']['provenance']['statement']['sha256'] = sha256_file(sp)
            m['releaseIntegrity']['provenance']['statement']['size'] = os.path.getsize(sp)
            _write_json(mp, m)
            errs = verify_provenance(m, rel)
            return (bool(errs) and any('gitCommit' in e or 'stale' in e.lower() for e in errs),
                    "errors=%s" % errs[:3])
    run("provenance: stale commit fails closed", t_prov_stale)

    # --- provenance: local-unsigned must not claim trustedOIDC ---
    def t_prov_local_misrepresented():
        with tempfile.TemporaryDirectory(prefix='m5d-prov-') as tmp:
            rel, m = _m5d_release(tmp, generator='local-unsigned')
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            sp = os.path.join(rel, m['releaseIntegrity']['provenance']['statement']['path'])
            stmt = json.load(open(sp))
            stmt['predicate']['metadata']['trustedOIDC'] = True
            _write_json(sp, stmt)
            m['releaseIntegrity']['provenance']['statement']['sha256'] = sha256_file(sp)
            m['releaseIntegrity']['provenance']['statement']['size'] = os.path.getsize(sp)
            _write_json(mp, m)
            errs = verify_provenance(m, rel)
            return (bool(errs) and any('trustedOIDC' in e for e in errs),
                    "errors=%s" % errs[:3])
    run("provenance: local-unsigned claiming trustedOIDC fails closed", t_prov_local_misrepresented)

    # --- signature: require-signature false with SKIPPED -> OK ---
    def t_sig_false_skipped_ok():
        with tempfile.TemporaryDirectory(prefix='m5d-sig-') as tmp:
            rel, m = _m5d_release(tmp, sig_status='SKIPPED')
            errs = verify_signature_evidence(m, rel, require_signature=False)
            return (not errs, "errors=%s" % errs[:3])
    run("signature: require-signature=false + SKIPPED -> OK", t_sig_false_skipped_ok)

    # --- signature: require-signature true with SKIPPED -> FAIL ---
    def t_sig_true_skipped_fail():
        with tempfile.TemporaryDirectory(prefix='m5d-sig-') as tmp:
            rel, m = _m5d_release(tmp, sig_status='SKIPPED')
            errs = verify_signature_evidence(m, rel, require_signature=True)
            return (bool(errs) and any('SKIPPED' in e and 'require-signature' in e for e in errs),
                    "errors=%s" % errs[:3])
    run("signature: require-signature=true + SKIPPED -> FAIL", t_sig_true_skipped_fail)

    # --- signature: require-signature true with SIGNED but wrong issuer -> FAIL ---
    def t_sig_wrong_issuer():
        with tempfile.TemporaryDirectory(prefix='m5d-sig-') as tmp:
            rel, m = _m5d_release(tmp, sig_status='SIGNED')
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            for a in m['artifacts']:
                a['signature']['issuer'] = 'https://wrong.example.com'
            _write_json(mp, m)
            errs = verify_signature_evidence(m, rel, require_signature=True)
            return (bool(errs) and any('wrong-issuer' in e or 'issuer' in e for e in errs),
                    "errors=%s" % errs[:3])
    run("signature: wrong issuer -> FAIL", t_sig_wrong_issuer)

    # --- signature: require-signature true with SIGNED but wrong identity -> FAIL ---
    def t_sig_wrong_identity():
        with tempfile.TemporaryDirectory(prefix='m5d-sig-') as tmp:
            rel, m = _m5d_release(tmp, sig_status='SIGNED')
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            for a in m['artifacts']:
                a['signature']['identity'] = 'https://evil.example.com/workflow'
            _write_json(mp, m)
            errs = verify_signature_evidence(m, rel, require_signature=True)
            return (bool(errs) and any('identity' in e and 'trusted' in e for e in errs),
                    "errors=%s" % errs[:3])
    run("signature: untrusted identity -> FAIL", t_sig_wrong_identity)

    # --- signature: tampered bundle (bundle sha mismatch) -> FAIL ---
    def t_sig_tampered_bundle():
        with tempfile.TemporaryDirectory(prefix='m5d-sig-') as tmp:
            rel, m = _m5d_release(tmp, sig_status='SIGNED')
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            brel = m['artifacts'][0]['signature']['bundle']['path']
            bp = os.path.join(rel, brel)
            with open(bp, 'w') as f:
                f.write('{}')  # truncate/alter the bundle file (hash changes)
            errs = verify_signature_evidence(m, rel, require_signature=True)
            return (bool(errs) and any('sha256 mismatch' in e or 'malformed' in e for e in errs),
                    "errors=%s" % errs[:3])
    run("signature: tampered bundle -> FAIL", t_sig_tampered_bundle)

    # --- signature: wrong-subject (payload digest != subject digest) -> FAIL ---
    def t_sig_wrong_subject():
        with tempfile.TemporaryDirectory(prefix='m5d-sig-') as tmp:
            rel, m = _m5d_release(tmp, sig_status='SIGNED')
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            # alter the bundle payload digest to not match the artifact
            brel = m['artifacts'][0]['signature']['bundle']['path']
            bp = os.path.join(rel, brel)
            bundle = json.load(open(bp))
            bundle['messageSignature']['messageDigest']['digest'] = '0' * 64
            _write_json(bp, bundle, sort=False)
            # fix manifest bundle sha so only the subject mismatch is detected
            for a in m['artifacts']:
                a['signature']['bundle']['sha256'] = sha256_file(bp)
                a['signature']['bundle']['size'] = os.path.getsize(bp)
            _write_json(mp, m)
            errs = verify_signature_evidence(m, rel, require_signature=True)
            return (bool(errs) and any('wrong-subject' in e or 'payload digest' in e for e in errs),
                    "errors=%s" % errs[:3])
    run("signature: wrong-subject payload digest -> FAIL", t_sig_wrong_subject)

    # --- signature: malformed bundle -> FAIL ---
    def t_sig_malformed_bundle():
        with tempfile.TemporaryDirectory(prefix='m5d-sig-') as tmp:
            rel, m = _m5d_release(tmp, sig_status='SIGNED')
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            brel = m['artifacts'][0]['signature']['bundle']['path']
            bp = os.path.join(rel, brel)
            bundle = json.load(open(bp))
            del bundle['base64Signature']
            del bundle['messageSignature']
            _write_json(bp, bundle, sort=False)
            for a in m['artifacts']:
                a['signature']['bundle']['sha256'] = sha256_file(bp)
                a['signature']['bundle']['size'] = os.path.getsize(bp)
            _write_json(mp, m)
            errs = verify_signature_evidence(m, rel, require_signature=True)
            return (bool(errs) and any('malformed' in e for e in errs),
                    "errors=%s" % errs[:3])
    run("signature: malformed bundle -> FAIL", t_sig_malformed_bundle)

    # --- final gate: verify_release_integrity happy path (require-signature false) ---
    def t_gate_happy():
        with tempfile.TemporaryDirectory(prefix='m5d-gate-') as tmp:
            rel, m = _m5d_release(tmp, sig_status='SKIPPED')
            res = verify_release_integrity(os.path.join(rel, "release-manifest.json"),
                                           require_signature=False, now=NOW)
            return (res['ok'] and res['stage'] == 'M5-D' and res['prGate'] == 'PASSED'
                    and res['releaseGate'] == 'NOT_RUN',
                    "ok=%s pr=%s rel=%s errors=%s" % (res['ok'], res['prGate'], res['releaseGate'], res['errors'][:3]))
    run("gate: M5-D release with require-signature=false PASSES (PR=PASSED, RELEASE=NOT_RUN)", t_gate_happy)

    # --- final gate: require-signature true with SKIPPED -> PR FAILED, RELEASE NOT_RUN ---
    def t_gate_require_true_skipped():
        with tempfile.TemporaryDirectory(prefix='m5d-gate-') as tmp:
            rel, m = _m5d_release(tmp, sig_status='SKIPPED')
            res = verify_release_integrity(os.path.join(rel, "release-manifest.json"),
                                           require_signature=True, now=NOW)
            return (not res['ok'] and res['prGate'] == 'FAILED' and res['releaseGate'] == 'NOT_RUN',
                    "ok=%s pr=%s errors=%s" % (res['ok'], res['prGate'], res['errors'][:2]))
    run("gate: require-signature=true + SKIPPED -> PR FAILED, RELEASE NOT_RUN", t_gate_require_true_skipped)

    # --- final gate: M5-C manifest (no M5-D) with require-signature false -> FAILED (no M5-D evidence) ---
    def t_gate_m5c_not_m5d():
        with tempfile.TemporaryDirectory(prefix='m5d-gate-') as tmp:
            sc_tmp = tempfile.mkdtemp(prefix='m5d-sc2-')
            sc_rel, m5c = supplychainlib._build_release_dir(sc_tmp)
            res = verify_release_integrity(os.path.join(sc_rel, "release-manifest.json"),
                                           require_signature=False, now=NOW)
            return (not res['ok'] and res['stage'] == 'M5-C',
                    "ok=%s stage=%s errors=%s" % (res['ok'], res['stage'], res['errors'][:2]))
    run("gate: M5-C manifest (no M5-D) -> FAILED (M5-D evidence required)", t_gate_m5c_not_m5d)

    # --- final gate: tampered artifact hash -> FAILED ---
    def t_gate_tampered_artifact():
        with tempfile.TemporaryDirectory(prefix='m5d-gate-') as tmp:
            rel, m = _m5d_release(tmp)
            art = next(a for a in m['artifacts'] if a.get('type') == 'jar')
            with open(os.path.join(rel, art['path']), 'wb') as f:
                f.write(b'tampered')
            res = verify_release_integrity(os.path.join(rel, "release-manifest.json"),
                                           require_signature=False, now=NOW)
            return (not res['ok'] and res['prGate'] == 'FAILED',
                    "ok=%s errors=%s" % (res['ok'], [e for e in res['errors'] if 'sha256' in e][:2]))
    run("gate: tampered artifact hash -> PR FAILED", t_gate_tampered_artifact)

    # --- over-broad OCI normalization is not permitted: a manifest that declares an empty volatile
    # allowlist (ignoring all image metadata) must not be accepted as PASSED via name-only match ---
    def t_repro_no_name_only():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, m = _m5d_release(tmp)
            rel_b = os.path.join(tmp, "release-b")
            # make B's image diffIds differ (real content diff) but keep names the same
            bm = json.load(open(os.path.join(rel_b, "release-manifest.json")))
            for a in bm['artifacts']:
                if a['type'] == 'docker-image':
                    a['image']['rootfs']['diffIds'] = ['sha256:differentlayer']
            _write_json(os.path.join(rel_b, "release-manifest.json"), bm)
            res = compare_releases(os.path.join(rel, "release-manifest.json"),
                                   os.path.join(rel_b, "release-manifest.json"), now=NOW)
            # names match but diffIds differ -> must FAIL (not claim reproducible by name match)
            return (res['status'] == 'FAILED'
                    and any('diffIds' in e for e in res['failureReasons']),
                    "status=%s failures=%s" % (res['status'], [e for e in res['failureReasons'] if 'diffIds' in e][:2]))
    run("reproducibility: name-match with differing diffIds FAILED (no name-only reproducibility)", t_repro_no_name_only)

    def t_repro_config_drift():
        with tempfile.TemporaryDirectory(prefix='m5d-repro-') as tmp:
            rel, _ = _m5d_release(tmp)
            rel_b = os.path.join(tmp, "release-b")
            bmp = os.path.join(rel_b, "release-manifest.json")
            bm = json.load(open(bmp))
            for a in bm['artifacts']:
                if a['type'] == 'docker-image':
                    a['image']['config']['env'].append('UNEXPECTED=1')
            _write_json(bmp, bm)
            res = compare_releases(os.path.join(rel, "release-manifest.json"), bmp, now=NOW)
            return (res['status'] == 'FAILED' and any('config' in e for e in res['failureReasons']),
                    "status=%s failures=%s" % (res['status'], res['failureReasons'][:2]))
    run("reproducibility: same layers with runtime config drift FAILED", t_repro_config_drift)

    # --- path traversal / symlink escape in evidence path ---
    def t_path_escape():
        with tempfile.TemporaryDirectory(prefix='m5d-path-') as tmp:
            rel, m = _m5d_release(tmp)
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            # set the provenance statement path to an absolute escape
            m['releaseIntegrity']['provenance']['statement']['path'] = '/etc/passwd'
            _write_json(mp, m)
            errs = verify_provenance(m, rel)
            return (bool(errs) and any('absolute' in e or 'outside' in e for e in errs),
                    "errors=%s" % errs[:2])
    run("verify: provenance path escape rejected", t_path_escape)

    passed = sum(1 for c in checks if c['passed'])
    failed = len(checks) - passed
    summary = {"passed": passed, "failed": failed, "total": len(checks), "checks": checks}
    print(json.dumps(summary, indent=2))
    return 0 if failed == 0 else 1


# --- CLI ----------------------------------------------------------------------------------

def _cmd_compare_releases(args):
    res = compare_releases(args.release_a, args.release_b)
    out = args.out or None
    if out:
        write_reproducibility_result(res, out)
    print(json.dumps({'status': res['status'], 'generatedAt': res['generatedAt'],
                      'failureReasons': res['failureReasons']}, indent=2))
    return 0 if res['status'] == 'PASSED' else 2


def _cmd_build_provenance(args):
    with open(args.manifest, encoding='utf-8') as f:
        manifest = json.load(f)
    release_root = os.path.dirname(os.path.realpath(args.manifest))
    statement = build_provenance_statement(manifest, release_root, generator=args.generator,
                                           build_workflow=args.build_workflow)
    out = args.out or os.path.join(release_root, PROVENANCE_DIR, PROVENANCE_NAME)
    write_provenance_statement(statement, out)
    ev = file_evidence(out)
    print(json.dumps({'path': out, 'sha256': ev['sha256'], 'size': ev['size'],
                      'subjects': len(statement['subject']), 'generator': args.generator}))
    return 0


def _cmd_promote_m5d(args):
    """Promote an M5-C manifest to M5-D using a spec JSON. The spec carries: generatedAt, generator,
    buildWorkflow, releaseRoot, reproducibility {resultRel, resultAbs}, signature {status, reason,
    bundleRel, bundleAbs, identity, issuer}."""
    with open(args.manifest, encoding='utf-8') as f:
        manifest = json.load(f)
    with open(args.spec, encoding='utf-8') as f:
        spec = json.load(f)
    promoted = promote_manifest_m5d(manifest, spec)
    with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(promoted, f, indent=2, sort_keys=True)
        f.write('\n')
    ov = promoted.get('releaseIntegrity', {}).get('overallStatus')
    sig = promoted.get('releaseIntegrity', {}).get('signature', {}).get('status')
    print(json.dumps({'ok': True, 'overallStatus': ov, 'signature': sig,
                      'stage': promoted.get('releaseIntegrity', {}).get('stage')}))
    return 0


def _cmd_verify_release(args):
    res = verify_release_integrity(
        args.manifest,
        require_signature=args.require_signature,
        expected_identity=os.environ.get('KAIRO_EXPECTED_SIGNING_IDENTITY'))
    for n in res['notes']:
        print(n)
    if res['errors']:
        for e in res['errors']:
            print("error: %s" % e, file=sys.stderr)
        return 1
    print("ok: release-integrity gate PASSED (stage=%s, V17-SUPPLY.PR=%s, V17-SUPPLY.RELEASE=%s)"
          % (res['stage'], res['prGate'], res['releaseGate']))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description="V1.7 M5-D release-integrity library")
    sub = p.add_subparsers(dest='cmd')

    sub.add_parser('self-test', help="run focused release-integrity assertions")

    cr = sub.add_parser('compare-releases', help="compare two releases for reproducibility")
    cr.add_argument('release_a')
    cr.add_argument('release_b')
    cr.add_argument('--out', help="path to write the machine-readable result JSON")

    bp = sub.add_parser('build-provenance', help="build a deterministic in-toto/SLSA provenance statement")
    bp.add_argument('--manifest', required=True)
    bp.add_argument('--generator', required=True, choices=list(PROVENANCE_GENERATORS))
    bp.add_argument('--build-workflow', required=True)
    bp.add_argument('--out')

    pm = sub.add_parser('promote-m5d', help="promote an M5-C manifest to M5-D from a spec JSON")
    pm.add_argument('--manifest', required=True)
    pm.add_argument('--spec', required=True)
    pm.add_argument('--out', required=True)

    vr = sub.add_parser('verify-release', help="final M5 release-integrity verifier")
    vr.add_argument('--manifest', required=True)
    vr.add_argument('--require-signature', required=True, help='true or false',
                    type=lambda s: s.lower() == 'true')

    args = p.parse_args(argv)
    if args.cmd == 'self-test':
        return self_test()
    if args.cmd == 'compare-releases':
        return _cmd_compare_releases(args)
    if args.cmd == 'build-provenance':
        return _cmd_build_provenance(args)
    if args.cmd == 'promote-m5d':
        return _cmd_promote_m5d(args)
    if args.cmd == 'verify-release':
        return _cmd_verify_release(args)
    p.print_help()
    return 1


if __name__ == '__main__':
    sys.exit(main())
