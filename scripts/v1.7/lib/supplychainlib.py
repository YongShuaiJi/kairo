#!/usr/bin/env python3
#
# scripts/v1.7/lib/supplychainlib.py
#
# V1.7 M5-C (roadmap §12.3 / lts-policy §9) supply-chain gate library. Pure, deterministic and
# fail-closed. It owns: CycloneDX JSON validation + reproducible normalization, the single Grype
# vulnerability decision (parsed from raw Grype JSON, never re-run), the third-party license
# decision, exact-match expiring exception validation (vulnerability + license), stage-aware manifest
# promotion (M5-B -> M5-C) and the OFFLINE verifier.
#
# It never: runs Grype, downloads a database, makes a network call, fabricates an exception, omits
# or downgrades a finding, invents missing license metadata, weakens a gate, or touches the
# eight-artifact §12.2 inventory / SHA256SUMS contract / signature (SKIPPED) / provenance
# (NOT_AVAILABLE, owned by M5-D). Signature/provenance remain their pre-M5-D state; M5-C only
# attaches SBOM references and decision evidence.
#
# Honest status vocabulary (mirrors releaselib): PASSED / FAILED for gate outcomes. A finding is
# never silently turned into an exception; an exception is an explicit, time-bounded, exact allow.

import argparse
import datetime as _dt
import hashlib
import json
import os
import re
import sys

# Pull the shared release-assembly honesty contract + stage-aware manifest validator from the
# sibling M5-B library. supplychainlib composes on top of releaselib; it does not duplicate it.
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)
import releaselib  # noqa: E402

# --- pinned tool identities (roadmap §12.3 fixed choices) --------------------------------
GRYPE_VERSION = "0.116.1"
CYCLONEDX_MAVEN_PLUGIN_VERSION = "2.9.3"
CYCLONEDX_NPM_VERSION = "6.0.0"

SUPPLY_SCHEMA_VERSION = "1.0"
SBOM_MEDIA_TYPE_JSON = releaselib.SBOM_MEDIA_TYPE_JSON
SBOM_SPEC_VERSIONS = releaselib.SBOM_SPEC_VERSIONS
MAVEN_SBOM_NAME = releaselib.MAVEN_SBOM_NAME
WEB_SBOM_NAME = releaselib.WEB_SBOM_NAME
SBOM_DIR = releaselib.SBOM_DIR
REPORTS_DIR = releaselib.REPORTS_DIR
SUPPLY_CONFIG_DIR = releaselib.SUPPLY_CONFIG_DIR

SEVERITIES = ("Critical", "High", "Medium", "Low", "Negligible", "Unknown")
BLOCKING_SEVERITIES = ("Critical", "High")
# Grype's documented "findings present" exit code (when --fail-on is used). A non-zero scan exit is
# accepted ONLY for this code AND only if valid JSON was still produced; any other non-zero code or
# missing/malformed JSON fails closed.
GRYPE_FINDINGS_EXIT_CODE = 1
MAX_DB_AGE_HOURS = 168

SHA256_RE = releaselib.SHA256_RE
ISO_RE = re.compile(r'^\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$')
PURL_VERSION_RE = re.compile(r'@([^?#]*)')  # the @version segment of a PURL (before ?/#)
SPDX_TOKEN_RE = re.compile(r'[A-Za-z0-9][A-Za-z0-9.+-]*')
SPDX_OPERATORS = {'OR', 'AND', 'WITH'}


# --- small utilities ---------------------------------------------------------------------

def _utcnow():
    return _dt.datetime.now(_dt.timezone.utc)


def _parse_iso(s):
    """Parse an ISO-8601 string (date or datetime, with optional Z) to an aware UTC datetime."""
    if not isinstance(s, str) or not s:
        raise ValueError("not a non-blank ISO string")
    t = s.strip()
    if t.endswith('Z'):
        t = t[:-1] + '+00:00'
    try:
        dt = _dt.datetime.fromisoformat(t)
    except ValueError:
        # bare date YYYY-MM-DD
        try:
            dt = _dt.date.fromisoformat(t[0:10])
        except ValueError:
            raise ValueError("not ISO-8601: %r" % s)
        return _dt.datetime.combine(dt, _dt.time(0, 0), tzinfo=_dt.timezone.utc)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=_dt.timezone.utc)
    return dt.astimezone(_dt.timezone.utc)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def file_evidence(path):
    """Return {sha256, size} for a file (fail-closed if unreadable)."""
    return {'sha256': sha256_file(path), 'size': os.path.getsize(path)}


def _evidence_ref(rel_path, abs_path, **extra):
    """Build a release-root-relative evidence reference with hash+size computed from abs_path."""
    ref = {'path': rel_path}
    ref.update(file_evidence(abs_path))
    ref.update(extra)
    return ref


# --- PURL utilities -----------------------------------------------------------------------

def purl_core(purl):
    """Return the core of a PURL (pkg:type/namespace/name@version) with qualifiers/subpath stripped.

    Used for exact package identity comparison that is robust to Grype's qualifier additions; the
    core still pins name AND version, so it remains an exact (not broad) match."""
    if not isinstance(purl, str) or not purl:
        return None
    s = purl.strip()
    # strip subpath
    s = s.split('#', 1)[0]
    # strip qualifiers
    s = s.split('?', 1)[0]
    return s


def purl_version(purl):
    """Return the version segment of a PURL, or None if the PURL carries no version (broad)."""
    if not isinstance(purl, str) or not purl:
        return None
    s = purl.strip().split('#', 1)[0].split('?', 1)[0]
    m = PURL_VERSION_RE.search(s)
    if not m:
        return None
    v = m.group(1)
    return v if v else None


# --- CycloneDX validation + normalization ------------------------------------------------

def validate_cyclonedx(doc):
    """Validate a parsed CycloneDX JSON document's structure/spec; return a list of error strings.

    Rejects malformed, empty, or wrong-format files. Does not require a specific subject; it only
    enforces the CycloneDX JSON schema contract used by M5-C (bomFormat, specVersion, components)."""
    errors = []
    if not isinstance(doc, dict):
        return ["CycloneDX document must be a JSON object"]
    if doc.get('bomFormat') != 'CycloneDX':
        errors.append("bomFormat must be 'CycloneDX'; got %r" % doc.get('bomFormat'))
    sv = doc.get('specVersion')
    if sv not in SBOM_SPEC_VERSIONS:
        errors.append("specVersion must be one of %s; got %r" % (SBOM_SPEC_VERSIONS, sv))
    comps = doc.get('components')
    if not isinstance(comps, list):
        errors.append("components must be a list")
    elif len(comps) == 0:
        errors.append("components must be a non-empty list (empty SBOM provides no supply-chain value)")
    else:
        for i, c in enumerate(comps):
            if not isinstance(c, dict):
                errors.append("components[%d] must be an object" % i)
                continue
            if not isinstance(c.get('name'), str) or not c.get('name'):
                errors.append("components[%d].name must be a non-blank string" % i)
    return errors


def normalize_cyclonedx(doc, timestamp):
    """Return a deterministic CycloneDX document: volatile serialNumber suppressed, metadata
    timestamp normalized to ``timestamp`` (ISO-8601 of SOURCE_DATE_EPOCH), then re-serialized with
    sorted keys. Dependency identity, versions, PURLs, hashes, relationships and licenses are NEVER
    altered or removed. Returns the normalized JSON bytes."""
    if not isinstance(doc, dict):
        raise ValueError("CycloneDX document must be a JSON object")
    out = json.loads(json.dumps(doc))  # deep copy
    # Suppress the volatile per-run serialNumber (CycloneDX spec: optional). This is suppression of a
    # volatile field, not removal of dependency/provenance data.
    out.pop('serialNumber', None)
    md = out.get('metadata')
    if isinstance(md, dict):
        md['timestamp'] = timestamp
    else:
        out['metadata'] = {'timestamp': timestamp}
    return json.dumps(out, indent=2, sort_keys=True, ensure_ascii=False, separators=(',', ': ')) + "\n"


def normalize_cyclonedx_file(in_path, out_path, timestamp):
    """Read a CycloneDX JSON file, normalize it deterministically, and write to out_path."""
    with open(in_path, encoding='utf-8') as f:
        doc = json.load(f)
    blob = normalize_cyclonedx(doc, timestamp)
    with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(blob)


def count_components(doc):
    comps = doc.get('components')
    return len(comps) if isinstance(comps, list) else 0


# --- CycloneDX license extraction --------------------------------------------------------

def parse_expression_ids(expr):
    """Conservatively extract SPDX license IDs from a license expression string.

    Tokens that are not SPDX operators/parens are returned as candidate IDs; any non-SPDX token
    classifies as UNKNOWN (block) downstream, so parsing is intentionally conservative."""
    if not isinstance(expr, str) or not expr:
        return []
    return [t for t in SPDX_TOKEN_RE.findall(expr) if t not in SPDX_OPERATORS]


def component_license_ids(comp):
    """Return ordered, de-duplicated license IDs/names/expressions declared by a component.

    Expressions are retained as complete expressions. Splitting ``A AND B`` into two independent
    alternatives would be a fail-open bug: an allowed A must not hide a denied B. A license with only
    a name is retained verbatim and classifies as UNKNOWN unless policy explicitly recognizes it."""
    ids = []
    licenses = comp.get('licenses')
    if isinstance(licenses, list):
        for item in licenses:
            if not isinstance(item, dict):
                continue
            if isinstance(item.get('expression'), str) and item['expression'].strip():
                ids.append(item['expression'].strip())
                continue
            lic = item.get('license')
            if isinstance(lic, dict):
                if isinstance(lic.get('id'), str) and lic['id']:
                    ids.append(lic['id'])
                elif isinstance(lic.get('name'), str) and lic['name']:
                    ids.append(lic['name'])
    seen = set()
    out = []
    for x in ids:
        if x and x not in seen:
            seen.add(x)
            out.append(x)
    return out


def component_identity(comp):
    """Return a stable identity tuple for a CycloneDX component: (purl) or (name, version)."""
    purl = comp.get('purl')
    if isinstance(purl, str) and purl:
        return ('purl', purl_core(purl))
    name = comp.get('name')
    version = comp.get('version')
    if isinstance(name, str) and name and isinstance(version, str) and version:
        return ('nv', name, version)
    return None


# --- vulnerability exceptions -------------------------------------------------------------

def _vuln_exc_identity(exc):
    """Return ('vulnId, identity) for an exception or None if the package identity is invalid."""
    vid = exc.get('vulnerabilityId')
    pkg = exc.get('package') or {}
    if not isinstance(pkg, dict):
        return None
    if isinstance(pkg.get('purl'), str) and pkg['purl']:
        return (vid, 'purl', purl_core(pkg['purl']))
    if isinstance(pkg.get('name'), str) and pkg.get('name') and isinstance(pkg.get('version'), str) and pkg.get('version'):
        return (vid, 'nv', pkg['name'], pkg['version'])
    return None


def validate_vulnerability_exceptions(doc, now):
    """Validate vulnerability-exceptions.json: schema, exact identity, expiry, no wildcard/broad/
    duplicate/unused-structural. Returns (errors, exceptions). ``now`` is an aware UTC datetime.

    'unused' (no matching finding) is checked in decide_vulnerabilities, not here, because it needs
    the scan findings; here we reject structural problems (wildcard, broad, missing fields, expired,
    duplicate)."""
    errors = []
    if not isinstance(doc, dict):
        return (["vulnerability-exceptions must be a JSON object"], [])
    if doc.get('schemaVersion') != SUPPLY_SCHEMA_VERSION:
        errors.append("vulnerability-exceptions schemaVersion must be %s" % SUPPLY_SCHEMA_VERSION)
    excs = doc.get('exceptions')
    if not isinstance(excs, list):
        return (errors + ["vulnerability-exceptions.exceptions must be a list"], [])
    required = ('vulnerabilityId', 'package', 'owner', 'reason', 'mitigation', 'reviewCondition', 'expiresAt')
    seen = set()
    out = []
    for i, e in enumerate(excs):
        if not isinstance(e, dict):
            errors.append("exceptions[%d] must be an object" % i)
            continue
        for k in required:
            if k not in e:
                errors.append("exceptions[%d] missing field: %s" % (i, k))
        vid = e.get('vulnerabilityId')
        if not isinstance(vid, str) or not vid:
            errors.append("exceptions[%d].vulnerabilityId must be a non-blank string" % i)
        elif '*' in vid or '?' in vid:
            errors.append("exceptions[%d].vulnerabilityId must not be a wildcard: %r" % (i, vid))
        ident = _vuln_exc_identity(e)
        if ident is None:
            errors.append("exceptions[%d].package must exactly identify an affected package: PURL (with "
                          "version) or exact name+version; bare name/version-only/PURL-without-version is "
                          "broad and rejected" % i)
        else:
            # reject broad PURL (no version) and wildcard name/version
            pkg = e.get('package') or {}
            if isinstance(pkg.get('purl'), str) and pkg.get('purl') and purl_version(pkg['purl']) is None:
                errors.append("exceptions[%d].package.purl must include a version (broad PURL rejected): %r"
                              % (i, pkg['purl']))
            if isinstance(pkg.get('purl'), str) and pkg.get('purl') and ('*' in pkg['purl'] or '?' in pkg['purl']):
                errors.append("exceptions[%d].package.purl must not contain wildcards: %r" % (i, pkg['purl']))
            if any('*' in str(v) or '?' in str(v) for v in (pkg.get('name'), pkg.get('version')) if v is not None):
                errors.append("exceptions[%d].package must not contain wildcards" % i)
            if ident in seen:
                errors.append("exceptions[%d] is a duplicate (same vulnerabilityId+package): %r" % (i, ident))
            seen.add(ident)
        # expiry
        exp = e.get('expiresAt')
        try:
            exp_dt = _parse_iso(exp)
        except ValueError:
            errors.append("exceptions[%d].expiresAt must be ISO-8601; got %r" % (i, exp))
            exp_dt = None
        if exp_dt is not None and exp_dt <= now:
            errors.append("exceptions[%d].expiresAt is expired (<= %s): %r" % (i, now.date(), exp))
        for k in ('owner', 'reason', 'mitigation', 'reviewCondition'):
            v = e.get(k)
            if not isinstance(v, str) or not v.strip():
                errors.append("exceptions[%d].%s must be a non-blank string" % (i, k))
        out.append(e)
    return (errors, out)


def _vuln_finding_identity(match):
    """Return (vulnerabilityId, identity) for a Grype match, or None if unidentifiable."""
    v = match.get('vulnerability') or {}
    a = match.get('artifact') or {}
    vid = v.get('id')
    if not isinstance(vid, str) or not vid:
        return None
    purl = a.get('purl')
    if isinstance(purl, str) and purl:
        return (vid, 'purl', purl_core(purl))
    name = a.get('name')
    version = a.get('version')
    if isinstance(name, str) and name and isinstance(version, str) and version:
        return (vid, 'nv', name, version)
    return None


def _vuln_match(finding_ident, exc):
    """True if a finding identity exactly matches an exception's vulnerabilityId+package."""
    e_ident = _vuln_exc_identity(exc)
    if e_ident is None or finding_ident is None:
        return False
    return finding_ident == e_ident


def _grype_severity(match):
    sev = (match.get('vulnerability') or {}).get('severity')
    if isinstance(sev, str) and sev in SEVERITIES:
        return sev
    return 'Unknown'


def parse_grype_matches(doc):
    """Return a list of normalized finding dicts from a parsed Grype JSON document."""
    findings = []
    if not isinstance(doc, dict):
        return findings
    matches = doc.get('matches')
    if not isinstance(matches, list):
        return findings
    for m in matches:
        if not isinstance(m, dict):
            continue
        ident = _vuln_finding_identity(m)
        if ident is None:
            continue
        a = m.get('artifact') or {}
        v = m.get('vulnerability') or {}
        findings.append({
            'vulnerabilityId': ident[0],
            'severity': _grype_severity(m),
            'package': {
                'name': a.get('name') if isinstance(a.get('name'), str) else '',
                'version': a.get('version') if isinstance(a.get('version'), str) else '',
                'purl': a.get('purl') if isinstance(a.get('purl'), str) else '',
            },
        })
    return findings


def validate_grype_document(doc):
    """Validate the raw Grype JSON needed for a fail-closed decision.

    An empty, well-formed ``matches`` array is a legitimate zero-finding scan. A missing/non-list
    matches field, an unidentifiable match, or missing pinned scanner identity is malformed evidence
    and must never be interpreted as zero findings."""
    errors = []
    if not isinstance(doc, dict):
        return ["raw Grype report must be a JSON object"]
    descriptor = doc.get('descriptor')
    if not isinstance(descriptor, dict):
        errors.append("raw Grype report descriptor must be an object")
    else:
        if descriptor.get('name') != 'grype':
            errors.append("raw Grype report descriptor.name must be 'grype'")
        if descriptor.get('version') != GRYPE_VERSION:
            errors.append("raw Grype report descriptor.version must be %s; got %r"
                          % (GRYPE_VERSION, descriptor.get('version')))
    matches = doc.get('matches')
    if not isinstance(matches, list):
        return errors + ["raw Grype report matches must be a list"]
    for i, match in enumerate(matches):
        if not isinstance(match, dict):
            errors.append("raw Grype report matches[%d] must be an object" % i)
            continue
        vulnerability = match.get('vulnerability')
        artifact = match.get('artifact')
        if not isinstance(vulnerability, dict):
            errors.append("raw Grype report matches[%d].vulnerability must be an object" % i)
        else:
            if not isinstance(vulnerability.get('id'), str) or not vulnerability.get('id'):
                errors.append("raw Grype report matches[%d].vulnerability.id must be non-blank" % i)
            if vulnerability.get('severity') not in SEVERITIES:
                errors.append("raw Grype report matches[%d].vulnerability.severity is unsupported: %r"
                              % (i, vulnerability.get('severity')))
        if not isinstance(artifact, dict):
            errors.append("raw Grype report matches[%d].artifact must be an object" % i)
        else:
            purl = artifact.get('purl')
            if isinstance(purl, str) and purl:
                if purl_version(purl) is None:
                    errors.append("raw Grype report matches[%d].artifact.purl must include a version" % i)
            elif not (isinstance(artifact.get('name'), str) and artifact.get('name')
                      and isinstance(artifact.get('version'), str) and artifact.get('version')):
                errors.append("raw Grype report matches[%d].artifact must have versioned PURL or exact name+version"
                              % i)
    return errors


def grype_descriptor_version(doc):
    """Return the scanner version recorded in a Grype JSON document's descriptor, or None."""
    if not isinstance(doc, dict):
        return None
    desc = doc.get('descriptor') or {}
    v = desc.get('version')
    return v if isinstance(v, str) else None


def decide_vulnerabilities(grype_docs, exceptions, now):
    """Decide the vulnerability gate from parsed Grype documents + validated exceptions.

    ``grype_docs`` is a list of (sbom_rel_path, parsed_doc). Returns a decision dict:
    countsBySeverity, countsByDecision, findings (sorted, with decision), exceptionsUsed,
    overallStatus, failureReasons. Critical/High findings block unless an exact unexpired exception
    matches; Medium/Low/Negligible/Unknown are reported (never silently turned into exceptions)."""
    counts_by_sev = {s: 0 for s in SEVERITIES}
    counts_by_decision = {'blocked': 0, 'exceptionAllowed': 0, 'reported': 0}
    all_findings = []
    for sbom_rel, doc in grype_docs:
        for f in parse_grype_matches(doc):
            f = dict(f)
            f['sbom'] = sbom_rel
            all_findings.append(f)
    # de-duplicate identical findings across SBOMs (same vulnId+package)
    dedup = {}
    for f in all_findings:
        ident = (f['vulnerabilityId'],) + tuple(_finding_key(f))
        if ident not in dedup:
            dedup[ident] = f
    findings = list(dedup.values())
    findings.sort(key=lambda f: (f['vulnerabilityId'], f['package'].get('name', ''), f['package'].get('version', ''),
                                f['package'].get('purl', '')))
    used_exception_indices = set()
    for f in findings:
        counts_by_sev[f['severity']] += 1
        ident = (f['vulnerabilityId'],) + _finding_ident(f)
        if f['severity'] in BLOCKING_SEVERITIES:
            matched = None
            for idx, e in enumerate(exceptions):
                if _vuln_match(ident, e):
                    matched = idx
                    break
            if matched is not None:
                f['decision'] = 'exceptionAllowed'
                counts_by_decision['exceptionAllowed'] += 1
                used_exception_indices.add(matched)
            else:
                f['decision'] = 'blocked'
                counts_by_decision['blocked'] += 1
        else:
            f['decision'] = 'reported'
            counts_by_decision['reported'] += 1
    # an exception is "used" iff it matches at least one finding (any severity); unused -> reject.
    unused = []
    for idx, e in enumerate(exceptions):
        if idx not in used_exception_indices:
            unused.append(_vuln_exc_identity(e))
    failure_reasons = []
    for f in findings:
        if f['decision'] == 'blocked':
            failure_reasons.append("blocked %s %s in %s: %s %s"
                                   % (f['severity'], f['vulnerabilityId'], f['sbom'],
                                      f['package'].get('name', ''), f['package'].get('version', '')))
    if unused:
        failure_reasons.append("unused vulnerability exceptions (no matching finding): %s"
                               % ', '.join(str(u) for u in unused))
    overall = 'FAILED' if (counts_by_decision['blocked'] > 0 or unused) else 'PASSED'
    return {
        'countsBySeverity': counts_by_sev,
        'countsByDecision': counts_by_decision,
        'findings': findings,
        'exceptionsUsed': sorted(used_exception_indices),
        'unusedExceptions': unused,
        'overallStatus': overall,
        'failureReasons': failure_reasons,
    }


def _finding_key(f):
    p = f['package']
    purl = p.get('purl')
    if purl:
        return ('purl', purl_core(purl))
    return ('nv', p.get('name', ''), p.get('version', ''))


def _finding_ident(f):
    p = f['package']
    purl = p.get('purl')
    if purl:
        return ('purl', purl_core(purl))
    return ('nv', p.get('name', ''), p.get('version', ''))


# --- license policy + exceptions + decision ----------------------------------------------

def validate_license_policy(doc):
    """Validate license-policy.json structure: schemaVersion, repositoryLicense, allow/deny/review
    sets (lists of SPDX IDs), mutually disjoint."""
    errors = []
    if not isinstance(doc, dict):
        return ["license-policy must be a JSON object"]
    if doc.get('schemaVersion') != SUPPLY_SCHEMA_VERSION:
        errors.append("license-policy schemaVersion must be %s" % SUPPLY_SCHEMA_VERSION)
    # The repository's OWN license is recorded SEPARATELY in repository-license.json (never used to
    # allow/deny a dependency). The policy references it via repositoryLicenseRef, or may state the
    # SPDX string directly via repositoryLicense; require at least one.
    has_repo = (isinstance(doc.get('repositoryLicense'), str) and doc.get('repositoryLicense')) or \
        (isinstance(doc.get('repositoryLicenseRef'), str) and doc.get('repositoryLicenseRef'))
    if not has_repo:
        errors.append("license-policy must reference the repository license "
                      "(repositoryLicense SPDX string or repositoryLicenseRef)")
    for k in ('allow', 'deny', 'review'):
        v = doc.get(k)
        if not isinstance(v, list) or not all(isinstance(x, str) and x for x in v):
            errors.append("license-policy.%s must be a list of non-blank SPDX strings" % k)
        elif len(v) != len(set(v)):
            errors.append("license-policy.%s must not contain duplicates" % k)
        elif any('*' in x or '?' in x for x in v):
            errors.append("license-policy.%s must not contain wildcard entries" % k)
    fpg = doc.get('firstPartyGroups')
    if fpg is not None and (not isinstance(fpg, list) or not all(isinstance(x, str) and x for x in fpg)):
        errors.append("license-policy.firstPartyGroups must be a list of non-blank group strings")
    allow = set(doc.get('allow') or [])
    deny = set(doc.get('deny') or [])
    review = set(doc.get('review') or [])
    overlap = (allow & deny) | (allow & review) | (deny & review)
    if overlap:
        errors.append("license-policy allow/deny/review sets must be disjoint; overlap: %s" % sorted(overlap))
    return errors


def _purl_namespace(purl):
    """Return the Maven groupId / npm scope namespace of a PURL (between pkg:<type>/ and the last '/'
    before the name@version), or None."""
    if not isinstance(purl, str) or not purl:
        return None
    s = purl.strip()
    if not s.startswith('pkg:'):
        return None
    rest = s[4:]
    # rest = type/namespace/name@version?qualifiers#subpath
    rest = rest.split('#', 1)[0].split('?', 1)[0]
    # split type and path
    if '/' not in rest:
        return None
    _typ, path = rest.split('/', 1)
    # path = namespace/name@version  (name@version has no '/' ; namespace may have '/')
    at = path.rfind('@')
    name_part = path[at:] if at >= 0 else path
    # the namespace is path minus the trailing name segment
    last_slash = path.rfind('/')
    if last_slash < 0:
        return None
    return path[:last_slash]


def is_first_party(comp, first_party_groups):
    """True if a CycloneDX component is a first-party (project) module: its CycloneDX ``group`` or its
    PURL namespace matches a firstPartyGroups entry."""
    if not first_party_groups:
        return False
    g = comp.get('group')
    if isinstance(g, str) and g in first_party_groups:
        return True
    ns = _purl_namespace(comp.get('purl'))
    if ns and ns in first_party_groups:
        return True
    return False


def _lic_exc_identity(exc):
    """Return the component identity for a license exception, or None if invalid/broad."""
    comp = exc.get('component') or {}
    if not isinstance(comp, dict):
        return None
    if isinstance(comp.get('purl'), str) and comp['purl']:
        return ('purl', purl_core(comp['purl']))
    if isinstance(comp.get('name'), str) and comp.get('name') and isinstance(comp.get('version'), str) and comp.get('version'):
        return ('nv', comp['name'], comp['version'])
    return None


def validate_license_exceptions(doc, now):
    """Validate license-exceptions.json: schema, exact component identity, license field, expiry,
    no wildcard/broad/duplicate. Returns (errors, exceptions)."""
    errors = []
    if not isinstance(doc, dict):
        return (["license-exceptions must be a JSON object"], [])
    if doc.get('schemaVersion') != SUPPLY_SCHEMA_VERSION:
        errors.append("license-exceptions schemaVersion must be %s" % SUPPLY_SCHEMA_VERSION)
    excs = doc.get('exceptions')
    if not isinstance(excs, list):
        return (errors + ["license-exceptions.exceptions must be a list"], [])
    required = ('component', 'license', 'owner', 'reason', 'mitigation', 'reviewCondition', 'expiresAt')
    seen = set()
    out = []
    for i, e in enumerate(excs):
        if not isinstance(e, dict):
            errors.append("license-exceptions[%d] must be an object" % i)
            continue
        for k in required:
            if k not in e:
                errors.append("license-exceptions[%d] missing field: %s" % (i, k))
        ident = _lic_exc_identity(e)
        if ident is None:
            errors.append("license-exceptions[%d].component must exactly identify a component: PURL (with "
                          "version) or exact name+version (broad rejected)" % i)
        else:
            comp = e.get('component') or {}
            if isinstance(comp.get('purl'), str) and comp.get('purl') and purl_version(comp['purl']) is None:
                errors.append("license-exceptions[%d].component.purl must include a version (broad PURL rejected)"
                              % i)
            if isinstance(comp.get('purl'), str) and comp.get('purl') and ('*' in comp['purl'] or '?' in comp['purl']):
                errors.append("license-exceptions[%d].component.purl must not contain wildcards" % i)
            if any('*' in str(v) or '?' in str(v) for v in (comp.get('name'), comp.get('version')) if v is not None):
                errors.append("license-exceptions[%d].component must not contain wildcards" % i)
            if ident in seen:
                errors.append("license-exceptions[%d] is a duplicate (same component): %r" % (i, ident))
            seen.add(ident)
        lic = e.get('license')
        if not isinstance(lic, str) or not lic.strip():
            errors.append("license-exceptions[%d].license must be a non-blank SPDX string/expression" % i)
        exp = e.get('expiresAt')
        try:
            exp_dt = _parse_iso(exp)
        except ValueError:
            errors.append("license-exceptions[%d].expiresAt must be ISO-8601; got %r" % (i, exp))
            exp_dt = None
        if exp_dt is not None and exp_dt <= now:
            errors.append("license-exceptions[%d].expiresAt is expired (<= %s): %r" % (i, now.date(), exp))
        for k in ('owner', 'reason', 'mitigation', 'reviewCondition'):
            v = e.get(k)
            if not isinstance(v, str) or not v.strip():
                errors.append("license-exceptions[%d].%s must be a non-blank string" % (i, k))
        out.append(e)
    return (errors, out)


def _classify_license(lic_id, policy):
    allow = set(policy.get('allow') or [])
    deny = set(policy.get('deny') or [])
    review = set(policy.get('review') or [])
    if lic_id in allow:
        return 'allowed'
    if lic_id in deny:
        return 'denied'
    if lic_id in review:
        return 'review'
    return 'unknown'


def _classify_license_expression(expression, policy):
    """Evaluate a conservative SPDX expression for policy purposes.

    ``AND`` uses the worst operand, ``OR`` the best operand. ``WITH`` is only accepted when the full
    expression is explicitly present in allow/deny/review; otherwise it is UNKNOWN because a license
    exception changes legal meaning and must not inherit the base license's decision accidentally."""
    direct = _classify_license(expression, policy)
    if direct != 'unknown':
        return direct
    tokens = re.findall(r'\(|\)|\bAND\b|\bOR\b|\bWITH\b|[A-Za-z0-9][A-Za-z0-9.+-]*', expression)
    if not tokens or ''.join(tokens) != re.sub(r'\s+', '', expression):
        return 'unknown'
    if 'WITH' in tokens:
        return 'unknown'
    pos = [0]
    rank = {'denied': 0, 'unknown': 1, 'review': 2, 'allowed': 3}

    def combine(a, b, best):
        return max((a, b), key=lambda x: rank[x]) if best else min((a, b), key=lambda x: rank[x])

    def atom():
        if pos[0] >= len(tokens):
            raise ValueError('missing operand')
        token = tokens[pos[0]]
        if token == '(':
            pos[0] += 1
            value = parse_or()
            if pos[0] >= len(tokens) or tokens[pos[0]] != ')':
                raise ValueError('missing closing parenthesis')
            pos[0] += 1
            return value
        if token in (')', 'AND', 'OR', 'WITH'):
            raise ValueError('unexpected operator')
        pos[0] += 1
        return _classify_license(token, policy)

    def parse_and():
        value = atom()
        while pos[0] < len(tokens) and tokens[pos[0]] == 'AND':
            pos[0] += 1
            value = combine(value, atom(), False)
        return value

    def parse_or():
        value = parse_and()
        while pos[0] < len(tokens) and tokens[pos[0]] == 'OR':
            pos[0] += 1
            value = combine(value, parse_and(), True)
        return value

    try:
        result = parse_or()
        return result if pos[0] == len(tokens) else 'unknown'
    except ValueError:
        return 'unknown'


def _component_decision(license_ids, policy):
    """Per-component decision from its license IDs. CycloneDX ``licenses[]`` entries are OR
    alternatives (the component may be used under any one), so the decision is the BEST acceptable
    option: allow > review > unknown > denied; an empty list is 'missing'. A component that has an
    allowed/review alternative does not block; one with only denied/unknown options blocks."""
    if not license_ids:
        return 'missing'
    decisions = [_classify_license_expression(x, policy) for x in license_ids]
    if 'allowed' in decisions:
        return 'allowed'
    if 'review' in decisions:
        return 'review'
    if 'unknown' in decisions:
        return 'unknown'
    if 'denied' in decisions:
        return 'denied'
    return 'unknown'


def decide_licenses(sbom_docs, policy, exceptions, now):
    """Decide the third-party license gate from parsed CycloneDX documents + policy + exceptions.

    ``sbom_docs`` is a list of (sbom_rel_path, parsed_doc). metadata.component (the subject/repo) is
    excluded from third-party decisions. Returns a decision dict: countsByDecision, components
    (sorted, with decision+licenses), exceptionsUsed, overallStatus, failureReasons. Denied/unknown/
    missing block unless an exact unexpired exception matches (component+license)."""
    counts = {'allowed': 0, 'denied': 0, 'review': 0, 'unknown': 0, 'missing': 0, 'exceptionAllowed': 0}
    first_party_groups = set(policy.get('firstPartyGroups') or [])
    comps = []
    skipped_first_party = 0
    for sbom_rel, doc in sbom_docs:
        if not isinstance(doc, dict):
            continue
        for component_index, c in enumerate(doc.get('components') or []):
            if not isinstance(c, dict):
                continue
            # first-party (project) modules carry the repository license (AGPL-3.0-only) and are
            # excluded from THIRD-PARTY decisions, like metadata.component.
            if is_first_party(c, first_party_groups):
                skipped_first_party += 1
                continue
            ident = component_identity(c)
            lids = component_license_ids(c)
            # A component without an exact versioned identity cannot be matched by a narrow
            # exception and is an unknown, blocking dependency rather than silently skipped.
            dec = _component_decision(lids, policy) if ident is not None else 'unknown'
            comps.append({
                'name': c.get('name') if isinstance(c.get('name'), str) else '',
                'version': c.get('version') if isinstance(c.get('version'), str) else '',
                'purl': c.get('purl') if isinstance(c.get('purl'), str) else '',
                'group': c.get('group') if isinstance(c.get('group'), str) else '',
                'licenses': lids,
                'decision': dec,
                'sbom': sbom_rel,
                'identityValid': ident is not None,
                'componentIndex': component_index,
            })
    # De-duplicate only byte-for-policy-equivalent entries from the SAME SBOM. Conflicting license
    # declarations for the same PURL must both survive so a permissive first occurrence cannot hide
    # a denied/unknown later occurrence.
    dedup = {}
    for c in comps:
        key = component_identity({'purl': c['purl'], 'name': c['name'], 'version': c['version']})
        if key is None:
            key = ('unidentified', c['sbom'], c['componentIndex'])
        else:
            key = (c['sbom'], key, tuple(c['licenses']), c['decision'])
        if key not in dedup:
            dedup[key] = c
    comps = list(dedup.values())
    comps.sort(key=lambda c: (c['sbom'], c['name'], c['version'], c['purl']))
    used_exception_indices = set()
    failure_reasons = []
    for c in comps:
        base = c['decision']
        if base in ('denied', 'unknown', 'missing'):
            # look for an exact exception: component identity matches AND exception.license matches a
            # declared license (exact string). For 'missing' (no declared license), the exception.license
            # is the license being granted for that component.
            matched = None
            for idx, e in enumerate(exceptions):
                e_ident = _lic_exc_identity(e)
                c_ident = component_identity({'purl': c['purl'], 'name': c['name'], 'version': c['version']})
                if c.get('identityValid') and e_ident is not None and e_ident == c_ident:
                    el = e.get('license')
                    if base == 'missing' or el in c['licenses']:
                        matched = idx
                        break
            if matched is not None:
                c['decision'] = 'exceptionAllowed'
                c['exception'] = exceptions[matched].get('license')
                counts['exceptionAllowed'] += 1
                used_exception_indices.add(matched)
            else:
                counts[base] += 1
                failure_reasons.append("%s license %s for %s %s (%s): %s"
                                       % (base, c['licenses'] or '<missing>', c['name'], c['version'], c['sbom'],
                                          c['purl'] or c['name']))
        else:
            counts[base] += 1
    unused = []
    for idx, e in enumerate(exceptions):
        if idx not in used_exception_indices:
            unused.append(_lic_exc_identity(e))
    if unused:
        failure_reasons.append("unused license exceptions (no matching component+license): %s"
                               % ', '.join(str(u) for u in unused))
    blocked = counts['denied'] + counts['unknown'] + counts['missing']
    overall = 'FAILED' if (blocked > 0 or unused) else 'PASSED'
    return {
        'countsByDecision': counts,
        'components': comps,
        'firstPartyComponentCount': skipped_first_party,
        'exceptionsUsed': sorted(used_exception_indices),
        'unusedExceptions': unused,
        'overallStatus': overall,
        'failureReasons': failure_reasons,
    }


# --- path containment (deep, against a release root) -------------------------------------

def validate_evidence_path(rel_path, release_root, *, must_exist=True):
    """Deep path containment: release-root-relative, canonical, non-symlink-escape, optionally exists.

    Returns a list of error strings (empty == valid). This is the offline verifier's realpath-based
    containment check; releaselib._validate_rel_path is the lighter schema-level counterpart."""
    errors = []
    if not isinstance(rel_path, str) or not rel_path:
        return ["path must be a non-blank string"]
    if rel_path.startswith('/'):
        return ["path must be release-root-relative (got absolute): %s" % rel_path]
    if '\\' in rel_path:
        return ["path must not contain backslashes: %s" % rel_path]
    parts = rel_path.split('/')
    if '..' in parts:
        return ["path must not contain '..' traversal segments: %s" % rel_path]
    if '.' in parts:
        return ["path must be canonical and must not contain '.' segments: %s" % rel_path]
    if any(p == '' for p in parts[1:]):
        return ["path must not contain empty segments: %s" % rel_path]
    abs_release = os.path.realpath(release_root)
    target = os.path.realpath(os.path.join(abs_release, rel_path))
    if target != abs_release and not target.startswith(abs_release + os.sep):
        return ["path resolves outside the release root (symlink/escape): %s -> %s" % (rel_path, target)]
    if must_exist and not os.path.exists(target):
        return ["path does not exist under the release root: %s" % rel_path]
    # reject a symlink whose link itself sits in-tree but points outside (realpath already resolved;
    # also reject if any path component is a symlink that escaped -- realpath covers the endpoint).
    return []


# --- manifest promotion (M5-B -> M5-C) ---------------------------------------------------

def _artifact_sbom_ref(group, sbom_refs):
    """Build the artifact sbom field for a group given the supplyChain sbom refs."""
    if group == 'not-applicable':
        return {'status': 'NOT_APPLICABLE',
                'reason': "compose archive contains Compose/config/templates only; no third-party runtime "
                          "dependencies; SBOM not applicable (roadmap §12.3)."}
    return dict(sbom_refs[group])


def promote_manifest(manifest, spec):
    """Promote an M5-B manifest to M5-C by attaching the supplyChain section and per-artifact SBOM
    references. ``spec`` carries: generatedAt, mavenSbom {rel,abs,...}, webSbom {...}, grype {version,
    database, scans:[{sbomRel,rawRel,rawAbs}]}, vulnDecision, licenseDecision, policyFiles {vulnExceptions,
    licensePolicy, licenseExceptions, repositoryLicense (each {rel,abs})}.

    The eight-artifact inventory, file sha256/size, SHA256SUMS contract, signature (SKIPPED) and
    provenance (NOT_AVAILABLE) are NEVER altered. Returns the promoted, validated manifest."""
    out = json.loads(json.dumps(manifest))  # deep copy; never mutate the caller's dict

    # Compute SBOM evidence (sha256/size/specVersion/componentCount) from the abs paths so the
    # manifest's recorded hashes are the single source of truth the verifier re-checks. The spec only
    # needs rel/abs pairs for SBOMs, raw reports and decision reports.
    def _sbom_ev(s):
        abs_path = s['abs']
        with open(abs_path, encoding='utf-8') as f:
            doc = json.load(f)
        cerrs = validate_cyclonedx(doc)
        if cerrs:
            raise ValueError("invalid SBOM %s: %s" % (s.get('rel'), '; '.join(cerrs)))
        ev = file_evidence(abs_path)
        return {'path': s['rel'], 'sha256': ev['sha256'], 'size': ev['size'],
                'mediaType': SBOM_MEDIA_TYPE_JSON, 'specVersion': doc.get('specVersion'),
                'componentCount': count_components(doc)}

    maven_e = _sbom_ev(spec['mavenSbom'])
    web_e = _sbom_ev(spec['webSbom'])
    maven_ref = {k: maven_e[k] for k in ('path', 'sha256', 'size', 'mediaType', 'specVersion')}
    web_ref = {k: web_e[k] for k in ('path', 'sha256', 'size', 'mediaType', 'specVersion')}
    sbom_refs = {'maven': maven_ref, 'web': web_ref}
    for a in out.get('artifacts', []):
        group = releaselib.artifact_sbom_group(a.get('name'))
        a['sbom'] = _artifact_sbom_ref(group, sbom_refs)
        # signature/provenance are intentionally left at their M5-B values (SKIPPED/NOT_AVAILABLE);
        # M5-C does not promote them. They are re-validated by validate_manifest below.
    scans = []
    for sc in spec['grype']['scans']:
        ev = file_evidence(sc['rawAbs'])
        scans.append({
            'sbom': sc['sbomRel'], 'path': sc['rawRel'], 'sha256': ev['sha256'], 'size': ev['size'],
        })
    vdec = spec['vulnDecision']
    ldec = spec['licenseDecision']
    # The decision dicts are read from the written decision-report files (single source of truth;
    # the verifier re-derives and cross-checks these same files).
    with open(vdec['abs'], encoding='utf-8') as f:
        vrep = json.load(f)
    with open(ldec['abs'], encoding='utf-8') as f:
        lrep = json.load(f)
    out['supplyChain'] = {
        'stage': releaselib.SUPPLY_CHAIN_STAGE,
        'generatedAt': spec['generatedAt'],
        'generators': {
            'maven': {'name': 'cyclonedx-maven-plugin', 'version': CYCLONEDX_MAVEN_PLUGIN_VERSION},
            'web': {'name': '@cyclonedx/cyclonedx-npm', 'version': CYCLONEDX_NPM_VERSION},
        },
        'sboms': {
            'maven': maven_ref,
            'web': web_ref,
        },
        'mavenSbomComponentCount': maven_e['componentCount'],
        'webSbomComponentCount': web_e['componentCount'],
        'vulnerabilityScan': {
            'scanner': {'name': 'grype', 'version': spec['grype']['version']},
            'database': spec['grype']['database'],
            'rawReports': scans,
            'decisionReport': _evidence_ref(vdec['rel'], vdec['abs']),
            'policyFile': _evidence_ref(spec['policyFiles']['vulnerabilityExceptions']['rel'],
                                        spec['policyFiles']['vulnerabilityExceptions']['abs']),
            'countsBySeverity': vrep['countsBySeverity'],
            'countsByDecision': vrep['countsByDecision'],
            'overallStatus': vrep['overallStatus'],
            'failureReasons': vrep['failureReasons'],
        },
        'licensePolicy': {
            'policyFile': _evidence_ref(spec['policyFiles']['licensePolicy']['rel'],
                                         spec['policyFiles']['licensePolicy']['abs']),
            'exceptionsFile': _evidence_ref(spec['policyFiles']['licenseExceptions']['rel'],
                                            spec['policyFiles']['licenseExceptions']['abs']),
            'repositoryLicense': dict(
                _evidence_ref(spec['policyFiles']['repositoryLicense']['rel'],
                              spec['policyFiles']['repositoryLicense']['abs']),
                license=spec['repositoryLicense']),
            'decisionReport': _evidence_ref(ldec['rel'], ldec['abs']),
            'firstPartyComponentCount': lrep.get('firstPartyComponentCount', 0),
            'countsByDecision': lrep['countsByDecision'],
            'overallStatus': lrep['overallStatus'],
            'failureReasons': lrep['failureReasons'],
        },
        'overallStatus': 'PASSED' if (vrep['overallStatus'] == 'PASSED'
                                      and lrep['overallStatus'] == 'PASSED') else 'FAILED',
        'failureReasons': (vrep['failureReasons'] + lrep['failureReasons']),
    }
    errors = releaselib.validate_manifest(out)
    if errors:
        raise ValueError("promoted manifest failed validation:\n  " + "\n  ".join(errors))
    return out


# --- offline verifier --------------------------------------------------------------------

def verify_release(manifest_path, now=None):
    """Offline verification of a release manifest and its supply-chain evidence. Returns a dict:
    {ok: bool, stage, errors:[], notes:[]}. NEVER runs Grype, NEVER downloads a DB, NEVER makes a
    network call. Re-hashes every evidence file, validates CycloneDX structure, re-derives the
    vulnerability + license decisions from raw Grype JSON + SBOMs + policy/exceptions, and cross-checks
    them against the stored decision reports + manifest hashes."""
    if now is None:
        now = _utcnow()
    errors = []
    notes = []
    if not os.path.isfile(manifest_path):
        return {'ok': False, 'stage': None, 'errors': ["manifest not found: %s" % manifest_path], 'notes': notes}
    with open(manifest_path, encoding='utf-8') as f:
        manifest = json.load(f)
    release_root = os.path.dirname(os.path.realpath(manifest_path))

    # 1. stage-aware schema validation (releaselib)
    schema_errors = releaselib.validate_manifest(manifest)
    errors.extend(schema_errors)
    supply = manifest.get('supplyChain')
    is_m5c = isinstance(supply, dict) and supply.get('stage') == releaselib.SUPPLY_CHAIN_STAGE
    if not is_m5c:
        if not schema_errors:
            notes.append("manifest stage: M5-B (no supplyChain); manifest is valid at its stage, "
                         "but no supply-chain evidence is promoted/verified")
        return {'ok': len(errors) == 0, 'stage': 'M5-B' if not schema_errors else None,
                'errors': errors, 'notes': notes}

    stage = 'M5-D' if isinstance(manifest.get('releaseIntegrity'), dict) and \
        manifest['releaseIntegrity'].get('stage') == releaselib.RELEASE_INTEGRITY_STAGE else 'M5-C'
    expected_generators = {
        'maven': {'name': 'cyclonedx-maven-plugin', 'version': CYCLONEDX_MAVEN_PLUGIN_VERSION},
        'web': {'name': '@cyclonedx/cyclonedx-npm', 'version': CYCLONEDX_NPM_VERSION},
    }
    if supply.get('generators') != expected_generators:
        errors.append("supplyChain.generators must record pinned generators: %r" % expected_generators)
    # 2. exact eight-artifact inventory (re-validated by validate_manifest; also re-check mapping here)
    artifacts = manifest.get('artifacts') or []
    if len(artifacts) != 8:
        errors.append("expected exactly 8 §12.2 artifacts; got %d" % len(artifacts))

    # 3. SBOM evidence: re-hash, validate CycloneDX, cross-check artifact mappings
    sboms = supply.get('sboms') or {}
    sbom_docs = {}
    for grp in ('maven', 'web'):
        ref = sboms.get(grp)
        if not isinstance(ref, dict):
            errors.append("supplyChain.sboms.%s missing" % grp)
            continue
        rel = ref.get('path')
        expected_rel = (SBOM_DIR + '/' + MAVEN_SBOM_NAME) if grp == 'maven' else (SBOM_DIR + '/' + WEB_SBOM_NAME)
        if rel != expected_rel:
            errors.append("supplyChain.sboms.%s.path must be stable path %s; got %r"
                          % (grp, expected_rel, rel))
        perr = validate_evidence_path(rel, release_root)
        if perr:
            errors.extend("supplyChain.sboms.%s: %s" % (grp, e) for e in perr)
            continue
        abs_path = os.path.join(release_root, rel)
        # re-hash + size
        ev = file_evidence(abs_path)
        if ev['sha256'] != ref.get('sha256'):
            errors.append("supplyChain.sboms.%s sha256 mismatch (tampered): recorded %s, actual %s"
                          % (grp, ref.get('sha256'), ev['sha256']))
        if ev['size'] != ref.get('size'):
            errors.append("supplyChain.sboms.%s size mismatch: recorded %s, actual %s"
                          % (grp, ref.get('size'), ev['size']))
        if ref.get('mediaType') != SBOM_MEDIA_TYPE_JSON:
            errors.append("supplyChain.sboms.%s.mediaType must be %s" % (grp, SBOM_MEDIA_TYPE_JSON))
        if ref.get('specVersion') not in SBOM_SPEC_VERSIONS:
            errors.append("supplyChain.sboms.%s.specVersion must be in %s" % (grp, SBOM_SPEC_VERSIONS))
        with open(abs_path, encoding='utf-8') as f:
            doc = json.load(f)
        cerr = validate_cyclonedx(doc)
        if cerr:
            errors.extend("supplyChain.sboms.%s: %s" % (grp, e) for e in cerr)
        else:
            sbom_docs[grp] = (rel, doc)

    # 4. artifact -> SBOM mapping (each artifact's sbom ref points to the correct group SBOM)
    for a in artifacts:
        name = a.get('name')
        group = releaselib.artifact_sbom_group(name)
        asbom = a.get('sbom')
        if group == 'not-applicable':
            if not (isinstance(asbom, dict) and asbom.get('status') == 'NOT_APPLICABLE'
                    and isinstance(asbom.get('reason'), str) and asbom.get('reason').strip()):
                errors.append("artifact %s sbom must be NOT_APPLICABLE with reason" % name)
            continue
        ref = sboms.get(group)
        if not isinstance(asbom, dict) or not isinstance(ref, dict):
            errors.append("artifact %s sbom mapping missing" % name)
            continue
        # the artifact sbom ref must equal the supplyChain sbom ref for its group (path/sha256/size)
        for k in ('path', 'sha256', 'size', 'mediaType', 'specVersion'):
            if asbom.get(k) != ref.get(k):
                errors.append("artifact %s sbom.%s must equal supplyChain.sboms.%s.%s (got %r, expected %r)"
                              % (name, k, group, k, asbom.get(k), ref.get(k)))

    # 5. vulnerability scan evidence: re-hash raw reports, validate grype version, re-derive decision
    vs = supply.get('vulnerabilityScan') or {}
    if vs.get('scanner', {}).get('name') != 'grype':
        errors.append("vulnerabilityScan.scanner.name must be 'grype'")
    if vs.get('scanner', {}).get('version') != GRYPE_VERSION:
        errors.append("vulnerabilityScan.scanner.version must be pinned to %s; got %r"
                      % (GRYPE_VERSION, vs.get('scanner', {}).get('version')))
    db = vs.get('database') or {}
    if db.get('current') is not True and db.get('valid') is not True:
        errors.append("vulnerabilityScan.database must be current/valid (stale/missing DB); got current=%r valid=%r"
                      % (db.get('current'), db.get('valid')))
    if not db.get('built'):
        errors.append("vulnerabilityScan.database.built must be present (DB build date/age)")
    else:
        try:
            db_built = _parse_iso(db.get('built'))
            generated_at = _parse_iso(supply.get('generatedAt'))
            actual_age_hours = (generated_at - db_built).total_seconds() / 3600.0
            if actual_age_hours < -1:
                errors.append("vulnerabilityScan.database.built is after supplyChain.generatedAt")
            if actual_age_hours > MAX_DB_AGE_HOURS:
                errors.append("vulnerabilityScan.database is stale: %.1f hours old (max %d)"
                              % (actual_age_hours, MAX_DB_AGE_HOURS))
        except ValueError as e:
            errors.append("vulnerabilityScan database/generatedAt timestamps must be ISO-8601: %s" % e)
    raw_reports = vs.get('rawReports') or []
    if len(raw_reports) != 2:
        errors.append("vulnerabilityScan.rawReports must contain exactly two scans (Maven + Web); got %d"
                      % len(raw_reports))
    grype_docs = []
    covered_sboms = set()
    expected_sbom_paths = {ref.get('path') for ref in sboms.values() if isinstance(ref, dict)}
    raw_paths = set()
    for rr in raw_reports:
        if not isinstance(rr, dict):
            errors.append("vulnerabilityScan.rawReports entries must be objects")
            continue
        rel = rr.get('path')
        if rel in raw_paths:
            errors.append("duplicate raw Grype report path: %s" % rel)
        raw_paths.add(rel)
        perr = validate_evidence_path(rel, release_root)
        if perr:
            errors.extend(perr)
            continue
        abs_path = os.path.join(release_root, rel)
        ev = file_evidence(abs_path)
        if ev['sha256'] != rr.get('sha256'):
            errors.append("raw grype report %s sha256 mismatch (tampered)" % rel)
        if ev['size'] != rr.get('size'):
            errors.append("raw grype report %s size mismatch" % rel)
        with open(abs_path, encoding='utf-8') as f:
            gdoc = json.load(f)
        sbom_rel = rr.get('sbom')
        if sbom_rel not in expected_sbom_paths:
            errors.append("raw grype report %s references unknown SBOM: %r" % (rel, sbom_rel))
        if sbom_rel in covered_sboms:
            errors.append("duplicate raw Grype coverage for SBOM: %s" % sbom_rel)
        gerrs = validate_grype_document(gdoc)
        if gerrs:
            errors.extend("raw grype report %s: %s" % (rel, e) for e in gerrs)
        else:
            covered_sboms.add(sbom_rel)
            grype_docs.append((sbom_rel, gdoc))
    # every SBOM must be covered by a raw Grype report (no silent un-scanned SBOM)
    for grp in ('maven', 'web'):
        ref = sboms.get(grp)
        if isinstance(ref, dict) and ref.get('path') not in covered_sboms:
            errors.append("vulnerabilityScan.rawReports missing a scan for %s SBOM (%s)"
                          % (grp, ref.get('path')))

    # policy/exception files: re-hash + re-read
    pf = vs.get('policyFile') or {}
    if pf.get('path') != SUPPLY_CONFIG_DIR + '/vulnerability-exceptions.json':
        errors.append("vulnerabilityScan.policyFile.path must be supply-chain-config/vulnerability-exceptions.json")
    exc_errors, vuln_exceptions = _load_policy_file(pf, release_root, 'vulnerability-exceptions',
                                                    lambda d: validate_vulnerability_exceptions(d, now))
    errors.extend(exc_errors)

    # re-derive vulnerability decision and cross-check inline manifest values + stored report file
    vdecision_report = vs.get('decisionReport') or {}
    vreport_doc = _verify_decision_report(vdecision_report, release_root, errors, 'vulnerability')
    if sbom_docs and grype_docs and vuln_exceptions is not None:
        red = decide_vulnerabilities(grype_docs, vuln_exceptions, now)
        # inline manifest counts must match the re-derived decision
        if vs.get('countsBySeverity') != red['countsBySeverity']:
            errors.append("vulnerabilityScan.countsBySeverity != re-derived: %r vs %r"
                          % (vs.get('countsBySeverity'), red['countsBySeverity']))
        if vs.get('countsByDecision') != red['countsByDecision']:
            errors.append("vulnerabilityScan.countsByDecision != re-derived: %r vs %r"
                          % (vs.get('countsByDecision'), red['countsByDecision']))
        if vs.get('overallStatus') != red['overallStatus']:
            errors.append("vulnerabilityScan.overallStatus (%s) != re-derived (%s)"
                          % (vs.get('overallStatus'), red['overallStatus']))
        # the stored decision report FILE must agree with the re-derived decision (tamper check)
        if vs.get('failureReasons') != red['failureReasons']:
            errors.append("vulnerabilityScan.failureReasons != re-derived")
        if isinstance(vreport_doc, dict):
            for key in ('countsBySeverity', 'countsByDecision', 'findings', 'exceptionsUsed',
                        'unusedExceptions', 'overallStatus', 'failureReasons'):
                if vreport_doc.get(key) != red[key]:
                    errors.append("vulnerability decision report file %s != re-derived" % key)
        if red['overallStatus'] != 'PASSED':
            errors.append("vulnerability gate FAILED: %s" % '; '.join(red['failureReasons']))

    # 6. license policy evidence: re-hash + re-derive decision
    lp = supply.get('licensePolicy') or {}
    expected_policy_paths = {
        'policyFile': SUPPLY_CONFIG_DIR + '/license-policy.json',
        'exceptionsFile': SUPPLY_CONFIG_DIR + '/license-exceptions.json',
        'repositoryLicense': SUPPLY_CONFIG_DIR + '/repository-license.json',
    }
    for field, expected_path in expected_policy_paths.items():
        ref = lp.get(field) or {}
        if ref.get('path') != expected_path:
            errors.append("licensePolicy.%s.path must be %s" % (field, expected_path))
    lp_errors, lic_policy = _load_policy_file(lp.get('policyFile'), release_root, 'license-policy',
                                              lambda d: (validate_license_policy(d), d))
    errors.extend(lp_errors)
    lex_errors, lic_exceptions = _load_policy_file(lp.get('exceptionsFile'), release_root, 'license-exceptions',
                                                    lambda d: validate_license_exceptions(d, now))
    errors.extend(lex_errors)
    repo_ref = lp.get('repositoryLicense') or {}
    repo_errors, repo_doc = _load_policy_file(repo_ref, release_root, 'repository-license',
                                              lambda d: (_repo_license_errors(d), d))
    errors.extend(repo_errors)
    # repository license must be AGPL-3.0-only and separate from third-party policy
    if repo_doc is not None:
        if repo_doc.get('repositoryLicense') != 'AGPL-3.0-only':
            errors.append("repository-license.repositoryLicense must be AGPL-3.0-only; got %r"
                          % repo_doc.get('repositoryLicense'))
        if repo_ref.get('license') != repo_doc.get('repositoryLicense'):
            errors.append("licensePolicy.repositoryLicense.license must match repository-license evidence")
    ldecision_report = lp.get('decisionReport') or {}
    lreport_doc = _verify_decision_report(ldecision_report, release_root, errors, 'license')
    if sbom_docs and lic_policy is not None and lic_exceptions is not None:
        sbom_pairs = [sbom_docs[g] for g in ('maven', 'web') if g in sbom_docs]
        red_l = decide_licenses(sbom_pairs, lic_policy, lic_exceptions, now)
        if lp.get('countsByDecision') != red_l['countsByDecision']:
            errors.append("licensePolicy.countsByDecision != re-derived: %r vs %r"
                          % (lp.get('countsByDecision'), red_l['countsByDecision']))
        if lp.get('overallStatus') != red_l['overallStatus']:
            errors.append("licensePolicy.overallStatus (%s) != re-derived (%s)"
                          % (lp.get('overallStatus'), red_l['overallStatus']))
        if lp.get('firstPartyComponentCount') != red_l['firstPartyComponentCount']:
            errors.append("licensePolicy.firstPartyComponentCount != re-derived")
        if lp.get('failureReasons') != red_l['failureReasons']:
            errors.append("licensePolicy.failureReasons != re-derived")
        if isinstance(lreport_doc, dict):
            for key in ('countsByDecision', 'components', 'exceptionsUsed', 'unusedExceptions',
                        'overallStatus', 'failureReasons'):
                if lreport_doc.get(key) != red_l[key]:
                    errors.append("license decision report file %s != re-derived" % key)
        if red_l['overallStatus'] != 'PASSED':
            errors.append("license gate FAILED: %s" % '; '.join(red_l['failureReasons']))

    # 7. signature/provenance honesty. Under pure M5-C these must remain pre-M5-D (SKIPPED /
    #    NOT_AVAILABLE); under M5-D they are promoted (PRESENT / SIGNED-or-SKIPPED) and validated by
    #    releaselib.validate_manifest + reprolib, so this double-check is skipped for M5-D manifests.
    is_m5d = stage == 'M5-D'
    if not is_m5d:
        for a in artifacts:
            if a.get('signature', {}).get('status') != 'SKIPPED':
                errors.append("artifact %s signature.status must remain SKIPPED (pre-M5-D)" % a.get('name'))
            if a.get('provenance', {}).get('status') != 'NOT_AVAILABLE':
                errors.append("artifact %s provenance.status must remain NOT_AVAILABLE (pre-M5-D)" % a.get('name'))

    # 8. overall status honesty
    ov = supply.get('overallStatus')
    expected_failure_reasons = []
    if isinstance(vs.get('failureReasons'), list):
        expected_failure_reasons.extend(vs.get('failureReasons'))
    if isinstance(lp.get('failureReasons'), list):
        expected_failure_reasons.extend(lp.get('failureReasons'))
    expected_overall = ('PASSED' if vs.get('overallStatus') == 'PASSED'
                        and lp.get('overallStatus') == 'PASSED' else 'FAILED')
    if ov != expected_overall:
        errors.append("supplyChain.overallStatus (%r) != derived status (%s)" % (ov, expected_overall))
    if supply.get('failureReasons') != expected_failure_reasons:
        errors.append("supplyChain.failureReasons != vulnerability+license failure reasons")
    if expected_overall != 'PASSED':
        errors.append("supply-chain gate is not PASSED")
    notes.append("manifest stage: %s; offline verification %s" % (stage, 'PASSED' if not errors else 'FAILED'))
    return {'ok': len(errors) == 0, 'stage': stage, 'errors': errors, 'notes': notes}


def _repo_license_errors(doc):
    if not isinstance(doc, dict):
        return ["repository-license must be a JSON object"]
    e = []
    if doc.get('repositoryLicense') != 'AGPL-3.0-only':
        e.append("repository-license.repositoryLicense must be AGPL-3.0-only")
    return e


def _load_policy_file(ref, release_root, label, validate_fn):
    """Re-hash + read a policy/exception file referenced from the manifest; return (errors, doc_or_None)."""
    errors = []
    if not isinstance(ref, dict):
        return (["%s file reference missing" % label], None)
    rel = ref.get('path')
    perr = validate_evidence_path(rel, release_root)
    if perr:
        return (["%s.%s" % (label, e) for e in perr], None)
    abs_path = os.path.join(release_root, rel)
    ev = file_evidence(abs_path)
    if ev['sha256'] != ref.get('sha256'):
        errors.append("%s file %s sha256 mismatch (tampered)" % (label, rel))
    if ev['size'] != ref.get('size'):
        errors.append("%s file %s size mismatch" % (label, rel))
    with open(abs_path, encoding='utf-8') as f:
        doc = json.load(f)
    result = validate_fn(doc)
    if isinstance(result, tuple):
        verrs, doc = result
        errors.extend(verrs)
    else:
        errors.extend(result)
    return (errors, doc)


def _verify_decision_report(ref, release_root, errors, label):
    """Re-hash + structural-validate a stored decision report referenced from the manifest; return the
    parsed report document (or None on failure)."""
    if not isinstance(ref, dict):
        errors.append("%s decisionReport reference missing" % label)
        return None
    rel = ref.get('path')
    perr = validate_evidence_path(rel, release_root)
    if perr:
        errors.extend(perr)
        return None
    abs_path = os.path.join(release_root, rel)
    ev = file_evidence(abs_path)
    if ev['sha256'] != ref.get('sha256'):
        errors.append("%s decisionReport %s sha256 mismatch (tampered)" % (label, rel))
    if ev['size'] != ref.get('size'):
        errors.append("%s decisionReport %s size mismatch" % (label, rel))
    try:
        with open(abs_path, encoding='utf-8') as f:
            doc = json.load(f)
    except (OSError, ValueError) as e:
        errors.append("%s decisionReport %s unreadable/malformed: %s" % (label, rel, e))
        return None
    if not isinstance(doc, dict) or doc.get('schemaVersion') != SUPPLY_SCHEMA_VERSION:
        errors.append("%s decisionReport %s schemaVersion must be %s" % (label, rel, SUPPLY_SCHEMA_VERSION))
        return None
    if doc.get('overallStatus') not in ('PASSED', 'FAILED'):
        errors.append("%s decisionReport %s overallStatus must be PASSED or FAILED" % (label, rel))
    return doc


# --- self-test ----------------------------------------------------------------------------

def _valid_cdx(spec_version="1.6", components=None, tools=None):
    if components is None:
        components = [
            {"type": "library", "name": "jackson-databind", "version": "2.17.2",
             "purl": "pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.17.2",
             "licenses": [{"license": {"id": "Apache-2.0"}}]},
            {"type": "library", "name": "react", "version": "19.2.4",
             "purl": "pkg:npm/react@19.2.4", "licenses": [{"license": {"id": "MIT"}}]},
        ]
    return {
        "bomFormat": "CycloneDX", "specVersion": spec_version, "version": 1,
        "metadata": {"timestamp": "2026-01-01T00:00:00Z",
                     "tools": tools or [{"name": "cyclonedx-maven-plugin", "version": CYCLONEDX_MAVEN_PLUGIN_VERSION}],
                     "component": {"type": "application", "name": "kairo-parent", "version": "1.7.0-rc.1"}},
        "components": components,
    }


def _grype_doc(severity_counts=None, version=GRYPE_VERSION, db_current=True, db_built="2026-08-01T00:00:00Z"):
    """Build a minimal Grype JSON doc. severity_counts: {severity: n} placing n findings of each kind."""
    matches = []
    if severity_counts:
        i = 0
        for sev, n in severity_counts.items():
            for _ in range(n):
                i += 1
                matches.append({
                    "vulnerability": {"id": "CVE-2026-%04d" % i, "severity": sev},
                    "artifact": {"name": "vulnpkg-%d" % i, "version": "1.0.%d" % i,
                                 "purl": "pkg:maven/g/a/vulnpkg-%d@1.0.%d" % (i, i)},
                })
    return {
        "matches": matches, "source": {"type": "directory", "target": "sbom/x.cdx.json"},
        "descriptor": {"name": "grype", "version": version, "timestamp": "2026-08-04T00:00:00Z",
                       "db": {"current": db_current, "built": db_built, "version": 5, "path": "/db"}},
    }


def _full_m5b_manifest():
    """A complete honest M5-B local RC manifest (no supplyChain)."""
    def fe(name, typ):
        return {"name": name, "type": typ, "sha256": "f" * 64, "size": 10, "path": name,
                "sbom": {"status": "NOT_AVAILABLE"}, "signature": {"status": "SKIPPED"},
                "provenance": {"status": "NOT_AVAILABLE"}}

    def ie(name):
        return {"name": name, "type": "docker-image", "sha256": "NOT_AVAILABLE", "size": 10,
                "image": {"imageId": "sha256:abc", "repoTags": [name], "repoDigests": [],
                          "rootfs": {"type": "layers", "diffIds": ["sha256:x"]}},
                "sbom": {"status": "NOT_AVAILABLE"}, "signature": {"status": "SKIPPED"},
                "provenance": {"status": "NOT_AVAILABLE"}}
    v = "1.7.0-rc.1"
    return {
        "schemaVersion": "1.0", "version": v, "gitCommit": "a" * 40, "sourceTag": "NOT_AVAILABLE",
        "contractBaseline": "V1.6.0", "buildWorkflow": "./scripts/v1.7/build-release.sh --version " + v,
        "buildStartedAt": "2026-08-04T00:00:00Z", "buildEndedAt": "2026-08-04T00:01:00Z",
        "toolchain": {"mvn": "3.9.16", "java": "21.0.11", "os": "linux"},
        "sourceDateEpoch": 1700000000, "allowDirty": False,
        "artifacts": [
            fe("kairo-agent-bundle-%s.tar.gz" % v, "tar.gz"),
            fe("kairo-platform-server-%s.jar" % v, "jar"),
            fe("kairo-cli-%s.jar" % v, "jar"),
            fe("kairo-mcp-%s.jar" % v, "jar"),
            fe("kairo-sdk-%s.jar" % v, "jar"),
            fe("kairo-compose-%s.tar.gz" % v, "tar.gz"),
            ie("kairo-platform-server:%s" % v),
            ie("kairo-platform-web:%s" % v),
        ],
        "compatibilityEvidence": {"status": "NOT_RUN"}, "recoveryEvidence": {"status": "NOT_RUN"},
        "performanceEvidence": {"status": "NOT_RUN"}, "soakEvidence": {"status": "NOT_RUN"},
        "knownLimitations": ["M5-B local RC build"],
        "ltsStart": "NOT_AVAILABLE", "standardSupportEnd": "NOT_AVAILABLE",
        "securitySupportEnd": "NOT_AVAILABLE", "maintainer": "NOT_AVAILABLE", "approval": "NOT_AVAILABLE",
    }


def _build_release_dir(tmp, *, maven_cdx=None, web_cdx=None, grype_maven=None, grype_web=None,
                       vuln_exc=None, lic_policy=None, lic_exc=None, repo_lic=None,
                       maven_components=None, web_components=None):
    """Build a complete M5-C release dir under tmp and return its path. Defaults produce a PASSING gate."""
    import shutil
    rel = os.path.join(tmp, "release")
    os.makedirs(os.path.join(rel, SBOM_DIR))
    os.makedirs(os.path.join(rel, REPORTS_DIR))
    os.makedirs(os.path.join(rel, SUPPLY_CONFIG_DIR))
    # config files
    ve = vuln_exc if vuln_exc is not None else {"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": []}
    lp = lic_policy if lic_policy is not None else {
        "schemaVersion": SUPPLY_SCHEMA_VERSION, "repositoryLicense": "AGPL-3.0-only",
        "allow": ["Apache-2.0", "MIT"], "deny": ["GPL-2.0-only"], "review": ["MPL-2.0"]}
    le = lic_exc if lic_exc is not None else {"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": []}
    rl = repo_lic if repo_lic is not None else {"schemaVersion": SUPPLY_SCHEMA_VERSION,
        "repositoryLicense": "AGPL-3.0-only", "owner": "Kairo V1.7 maintainers"}
    cfg_dir = os.path.join(rel, SUPPLY_CONFIG_DIR)
    _write_json(cfg_dir, "vulnerability-exceptions.json", ve)
    _write_json(cfg_dir, "license-policy.json", lp)
    _write_json(cfg_dir, "license-exceptions.json", le)
    _write_json(cfg_dir, "repository-license.json", rl)
    # SBOMs (normalized)
    mv = maven_cdx if maven_cdx is not None else _valid_cdx(components=maven_components)
    wb = web_cdx if web_cdx is not None else _valid_cdx(components=web_components or [
        {"type": "library", "name": "react", "version": "19.2.4", "purl": "pkg:npm/react@19.2.4",
         "licenses": [{"license": {"id": "MIT"}}]}])
    maven_rel = SBOM_DIR + "/" + MAVEN_SBOM_NAME
    web_rel = SBOM_DIR + "/" + WEB_SBOM_NAME
    _write_normalized(os.path.join(rel, maven_rel), mv, "2026-08-04T00:00:00Z")
    _write_normalized(os.path.join(rel, web_rel), wb, "2026-08-04T00:00:00Z")
    with open(os.path.join(rel, maven_rel), encoding='utf-8') as f:
        mv_doc = json.load(f)
    with open(os.path.join(rel, web_rel), encoding='utf-8') as f:
        wb_doc = json.load(f)
    # grype raw
    gm = grype_maven if grype_maven is not None else _grype_doc()
    gw = grype_web if grype_web is not None else _grype_doc()
    gm_rel = REPORTS_DIR + "/grype-maven.raw.json"
    gw_rel = REPORTS_DIR + "/grype-web.raw.json"
    _write_json(os.path.join(rel, gm_rel.rsplit('/', 1)[0]), "grype-maven.raw.json", gm, sort=False)
    _write_json(os.path.join(rel, gw_rel.rsplit('/', 1)[0]), "grype-web.raw.json", gw, sort=False)
    # decisions
    now = _parse_iso("2026-08-04T00:00:00Z")
    verrs, vexcs = validate_vulnerability_exceptions(ve, now)
    perrs = validate_license_policy(lp)
    lerrs, lexcs = validate_license_exceptions(le, now)
    assert not (verrs or perrs or lerrs), (verrs, perrs, lerrs)
    vdec = decide_vulnerabilities([(maven_rel, gm), (web_rel, gw)], vexcs, now)
    ldec = decide_licenses([(maven_rel, mv_doc), (web_rel, wb_doc)], lp, lexcs, now)
    vd_rel = REPORTS_DIR + "/vulnerability-decision.json"
    ld_rel = REPORTS_DIR + "/license-decision.json"
    _write_json(os.path.join(rel, vd_rel.rsplit('/', 1)[0]), "vulnerability-decision.json", {
        "schemaVersion": SUPPLY_SCHEMA_VERSION, "generatedAt": "2026-08-04T00:00:00Z",
        "scanner": {"name": "grype", "version": GRYPE_VERSION},
        "policyFile": SUPPLY_CONFIG_DIR + "/vulnerability-exceptions.json",
        "countsBySeverity": vdec["countsBySeverity"], "countsByDecision": vdec["countsByDecision"],
        "findings": vdec["findings"], "exceptionsUsed": vdec["exceptionsUsed"],
        "unusedExceptions": vdec["unusedExceptions"],
        "overallStatus": vdec["overallStatus"], "failureReasons": vdec["failureReasons"]})
    _write_json(os.path.join(rel, ld_rel.rsplit('/', 1)[0]), "license-decision.json", {
        "schemaVersion": SUPPLY_SCHEMA_VERSION, "generatedAt": "2026-08-04T00:00:00Z",
        "policyFile": SUPPLY_CONFIG_DIR + "/license-policy.json",
        "exceptionsFile": SUPPLY_CONFIG_DIR + "/license-exceptions.json",
        "repositoryLicense": {"file": SUPPLY_CONFIG_DIR + "/repository-license.json", "license": "AGPL-3.0-only"},
        "countsByDecision": ldec["countsByDecision"], "components": ldec["components"],
        "exceptionsUsed": ldec["exceptionsUsed"], "unusedExceptions": ldec["unusedExceptions"],
        "overallStatus": ldec["overallStatus"], "failureReasons": ldec["failureReasons"]})
    # promote manifest
    spec = {
        "generatedAt": "2026-08-04T00:00:00Z",
        "mavenSbom": _ev(maven_rel, os.path.join(rel, maven_rel), spec_version=mv_doc.get("specVersion"),
                          component_count=count_components(mv_doc)),
        "webSbom": _ev(web_rel, os.path.join(rel, web_rel), spec_version=wb_doc.get("specVersion"),
                        component_count=count_components(wb_doc)),
        "grype": {"version": GRYPE_VERSION,
                  "database": {"current": True, "built": "2026-08-01T00:00:00Z", "version": 5, "path": "/db"},
                  "scans": [{"sbomRel": maven_rel, "rawRel": gm_rel, "rawAbs": os.path.join(rel, gm_rel),
                             "rawSha256": sha256_file(os.path.join(rel, gm_rel)),
                             "rawSize": os.path.getsize(os.path.join(rel, gm_rel))},
                            {"sbomRel": web_rel, "rawRel": gw_rel, "rawAbs": os.path.join(rel, gw_rel),
                             "rawSha256": sha256_file(os.path.join(rel, gw_rel)),
                             "rawSize": os.path.getsize(os.path.join(rel, gw_rel))}]},
        "vulnDecision": {"rel": vd_rel, "abs": os.path.join(rel, vd_rel), "decision": vdec},
        "licenseDecision": {"rel": ld_rel, "abs": os.path.join(rel, ld_rel), "decision": ldec},
        "repositoryLicense": "AGPL-3.0-only",
        "policyFiles": {
            "vulnerabilityExceptions": _cfg_ref(rel, "vulnerability-exceptions.json"),
            "licensePolicy": _cfg_ref(rel, "license-policy.json"),
            "licenseExceptions": _cfg_ref(rel, "license-exceptions.json"),
            "repositoryLicense": _cfg_ref(rel, "repository-license.json")}}
    promoted = promote_manifest(_full_m5b_manifest(), spec)
    _write_json(rel, "release-manifest.json", promoted)
    return rel, promoted


def _write_json(dirpath, name, obj, sort=True):
    with open(os.path.join(dirpath, name), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(obj, f, indent=2, sort_keys=sort)
        f.write('\n')


def _write_normalized(abs_path, doc, timestamp):
    os.makedirs(os.path.dirname(abs_path), exist_ok=True)
    blob = normalize_cyclonedx(doc, timestamp)
    with open(abs_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(blob)


def _ev(rel, abs_path, spec_version, component_count):
    return {"rel": rel, "abs": abs_path, "sha256": sha256_file(abs_path), "size": os.path.getsize(abs_path),
            "specVersion": spec_version, "componentCount": component_count}


def _cfg_ref(rel, name):
    p = SUPPLY_CONFIG_DIR + "/" + name
    return {"rel": p, "abs": os.path.join(rel, p)}


def self_test():
    """Run focused M5-C assertions. Prints a JSON summary and exits 0 iff all pass."""
    import tempfile
    checks = []

    def run(name, fn):
        try:
            ok, detail = fn()
        except Exception as e:
            ok, detail = False, "exception: %s: %s" % (type(e).__name__, e)
        checks.append({"name": name, "passed": bool(ok), "detail": detail})

    NOW = _parse_iso("2026-08-04T00:00:00Z")

    # --- CycloneDX validation: valid + malformed/empty/wrong-spec ---
    def t_cdx_valid():
        errs = validate_cyclonedx(_valid_cdx())
        return (not errs, "errors: %s" % errs)
    run("cyclonedx: valid maven/web docs accepted", t_cdx_valid)

    def t_cdx_malformed():
        bad = {"bomFormat": "SPDX", "specVersion": "1.6", "components": [{"name": "x"}]}
        if not validate_cyclonedx(bad):
            return False, "wrong bomFormat not rejected"
        bad2 = {"bomFormat": "CycloneDX", "specVersion": "2.0", "components": [{"name": "x"}]}
        if not validate_cyclonedx(bad2):
            return False, "wrong specVersion not rejected"
        bad3 = {"bomFormat": "CycloneDX", "specVersion": "1.6", "components": []}
        if not validate_cyclonedx(bad3):
            return False, "empty components not rejected"
        bad4 = "not-json-object"
        if not validate_cyclonedx(bad4):
            return False, "non-object not rejected"
        return True, "malformed/empty/wrong-spec rejected"
    run("cyclonedx: malformed/empty/wrong-spec rejected", t_cdx_malformed)

    # --- 8-artifact mapping ---
    def t_mapping():
        groups = {n: releaselib.artifact_sbom_group(n) for n in [
            "kairo-agent-bundle-1.7.0-rc.1.tar.gz", "kairo-platform-server-1.7.0-rc.1.jar",
            "kairo-cli-1.7.0-rc.1.jar", "kairo-mcp-1.7.0-rc.1.jar", "kairo-sdk-1.7.0-rc.1.jar",
            "kairo-compose-1.7.0-rc.1.tar.gz", "kairo-platform-server:1.7.0-rc.1",
            "kairo-platform-web:1.7.0-rc.1"]}
        expect = {"kairo-agent-bundle-1.7.0-rc.1.tar.gz": "maven",
                  "kairo-platform-server-1.7.0-rc.1.jar": "maven",
                  "kairo-cli-1.7.0-rc.1.jar": "maven", "kairo-mcp-1.7.0-rc.1.jar": "maven",
                  "kairo-sdk-1.7.0-rc.1.jar": "maven",
                  "kairo-compose-1.7.0-rc.1.tar.gz": "not-applicable",
                  "kairo-platform-server:1.7.0-rc.1": "maven",
                  "kairo-platform-web:1.7.0-rc.1": "web"}
        return (groups == expect, "got %s" % groups)
    run("mapping: exact 8-artifact sbom groups", t_mapping)

    # --- vulnerability exceptions ---
    def _vexc(vid="CVE-2026-0001", purl="pkg:maven/g/a/p@1.0.0", expires="2027-01-01"):
        return {"vulnerabilityId": vid, "package": {"purl": purl}, "owner": "sec",
                "reason": "tested", "mitigation": "none", "reviewCondition": "next release",
                "expiresAt": expires}

    def t_vexc_valid():
        errs, excs = validate_vulnerability_exceptions(
            {"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": [_vexc()]}, NOW)
        return (not errs and len(excs) == 1, "errors: %s" % errs)
    run("vuln-exception: valid exact unexpired accepted", t_vexc_valid)

    def t_vexc_expired():
        e = _vexc(expires="2026-01-01")
        errs, _ = validate_vulnerability_exceptions({"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": [e]}, NOW)
        return (bool(errs) and any('expired' in x for x in errs), "errors: %s" % errs)
    run("vuln-exception: expired rejected", t_vexc_expired)

    def t_vexc_wildcard():
        e = _vexc(vid="CVE-2026-*")
        errs, _ = validate_vulnerability_exceptions({"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": [e]}, NOW)
        if not errs:
            return False, "wildcard vulnId not rejected"
        e2 = _vexc(purl="pkg:maven/g/a/p@*")
        errs2, _ = validate_vulnerability_exceptions({"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": [e2]}, NOW)
        return (bool(errs2), "wildcard pkg not rejected: %s" % errs2)
    run("vuln-exception: wildcard/broad rejected", t_vexc_wildcard)

    def t_vexc_broad():
        # PURL without version is broad
        e = _vexc(purl="pkg:maven/g/a/p")
        errs, _ = validate_vulnerability_exceptions({"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": [e]}, NOW)
        return (bool(errs), "broad PURL (no version) not rejected: %s" % errs)
    run("vuln-exception: broad (versionless purl) rejected", t_vexc_broad)

    def t_vexc_dup():
        e = [_vexc(), _vexc()]
        errs, _ = validate_vulnerability_exceptions({"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": e}, NOW)
        return (bool(errs) and any('duplicate' in x for x in errs), "errors: %s" % errs)
    run("vuln-exception: duplicate rejected", t_vexc_dup)

    # --- vulnerability decisions ---
    def t_vdec_blocking():
        gdoc = _grype_doc({"Critical": 1, "High": 1, "Medium": 2, "Low": 1})
        dec = decide_vulnerabilities([("sbom/m.cdx.json", gdoc)], [], NOW)
        ok = (dec["overallStatus"] == "FAILED" and dec["countsByDecision"]["blocked"] == 2
              and dec["countsByDecision"]["reported"] == 3
              and dec["countsBySeverity"]["Critical"] == 1 and dec["countsBySeverity"]["High"] == 1)
        return ok, "dec: %s" % dec["countsByDecision"]
    run("vuln-decision: Critical/High block; Medium/Low reported", t_vdec_blocking)

    def t_vdec_exception_allows():
        # exact exception for the Critical finding allows it; High still blocks
        gdoc = _grype_doc({"Critical": 1})
        f = parse_grype_matches(gdoc)[0]
        exc = {"vulnerabilityId": f["vulnerabilityId"],
               "package": {"purl": f["package"]["purl"]}, "owner": "x", "reason": "x",
               "mitigation": "x", "reviewCondition": "x", "expiresAt": "2027-01-01"}
        dec = decide_vulnerabilities([("sbom/m.cdx.json", gdoc)], [exc], NOW)
        return (dec["overallStatus"] == "PASSED" and dec["countsByDecision"]["exceptionAllowed"] == 1,
                "dec: %s" % dec["countsByDecision"])
    run("vuln-decision: exact unexpired exception allows Critical", t_vdec_exception_allows)

    def t_vdec_wrong_package():
        gdoc = _grype_doc({"Critical": 1})
        exc = {"vulnerabilityId": "CVE-2026-9999", "package": {"purl": "pkg:maven/g/a/other@2.0.0"},
               "owner": "x", "reason": "x", "mitigation": "x", "reviewCondition": "x", "expiresAt": "2027-01-01"}
        errs, excs = validate_vulnerability_exceptions(
            {"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": [exc]}, NOW)
        assert not errs
        dec = decide_vulnerabilities([("sbom/m.cdx.json", gdoc)], excs, NOW)
        # wrong-package exception is unused -> rejected as unused; finding still blocks
        return (dec["overallStatus"] == "FAILED" and bool(dec["unusedExceptions"])
                and dec["countsByDecision"]["blocked"] == 1, "dec: %s" % dec)
    run("vuln-decision: wrong-package exception unused -> rejected", t_vdec_wrong_package)

    def t_vdec_malformed_grype():
        errs = validate_grype_document({"matches": "not-a-list",
                                        "descriptor": {"name": "grype", "version": GRYPE_VERSION}})
        return (bool(errs) and any('matches must be a list' in e for e in errs), "errors: %s" % errs)
    run("vuln-decision: malformed raw grype JSON rejected (not zero findings)", t_vdec_malformed_grype)

    def t_vdec_missing_metadata():
        # grype doc missing descriptor.version / db -> the verifier catches; here parse_grype_matches is fine
        gv = grype_descriptor_version({"descriptor": {"name": "grype"}})
        return (gv is None, "missing version returned None")
    run("vuln-decision: missing scanner metadata detected", t_vdec_missing_metadata)

    # --- license policy + decisions ---
    def _lpolicy():
        return {"schemaVersion": SUPPLY_SCHEMA_VERSION, "repositoryLicense": "AGPL-3.0-only",
                "allow": ["Apache-2.0", "MIT"], "deny": ["GPL-2.0-only"], "review": ["MPL-2.0"]}

    def t_lic_decisions():
        comps = [
            {"type": "library", "name": "a", "version": "1", "purl": "pkg:maven/g/a/a@1",
             "licenses": [{"license": {"id": "Apache-2.0"}}]},  # allowed
            {"type": "library", "name": "b", "version": "1", "purl": "pkg:maven/g/a/b@1",
             "licenses": [{"license": {"id": "GPL-2.0-only"}}]},  # denied
            {"type": "library", "name": "c", "version": "1", "purl": "pkg:maven/g/a/c@1",
             "licenses": [{"license": {"id": "Custom-Weird"}}]},  # unknown
            {"type": "library", "name": "d", "version": "1", "purl": "pkg:maven/g/a/d@1"},  # missing
            {"type": "library", "name": "e", "version": "1", "purl": "pkg:maven/g/a/e@1",
             "licenses": [{"license": {"id": "MPL-2.0"}}]},  # review
        ]
        cdx = _valid_cdx(components=comps)
        dec = decide_licenses([("sbom/m.cdx.json", cdx)], _lpolicy(), [], NOW)
        c = dec["countsByDecision"]
        ok = (c["allowed"] == 1 and c["denied"] == 1 and c["unknown"] == 1 and c["missing"] == 1
              and c["review"] == 1 and dec["overallStatus"] == "FAILED")
        return ok, "counts: %s" % c
    run("license-decision: allow/deny/review/unknown/missing", t_lic_decisions)

    def t_lic_expression_semantics():
        policy = _lpolicy()
        and_decision = _component_decision(["MIT AND GPL-2.0-only"], policy)
        or_decision = _component_decision(["MIT OR GPL-2.0-only"], policy)
        with_unknown = _component_decision(["GPL-2.0-only WITH Classpath-exception-2.0"], policy)
        return (and_decision == 'denied' and or_decision == 'allowed' and with_unknown == 'unknown',
                "AND=%s OR=%s WITH=%s" % (and_decision, or_decision, with_unknown))
    run("license-expression: AND is worst, OR is best, unlisted WITH is unknown", t_lic_expression_semantics)

    def t_lic_unidentified_component_blocks():
        cdx = _valid_cdx(components=[{
            "type": "library", "name": "unversioned", "licenses": [{"license": {"id": "MIT"}}]
        }])
        dec = decide_licenses([("sbom/m.cdx.json", cdx)], _lpolicy(), [], NOW)
        return (dec["overallStatus"] == "FAILED" and dec["countsByDecision"]["unknown"] == 1,
                "counts: %s" % dec["countsByDecision"])
    run("license-decision: component without exact identity blocks instead of being skipped",
        t_lic_unidentified_component_blocks)

    def _lexc(purl="pkg:maven/g/a/b@1", lic="GPL-2.0-only", expires="2027-01-01"):
        return {"component": {"purl": purl}, "license": lic, "owner": "x", "reason": "x",
                "mitigation": "x", "reviewCondition": "x", "expiresAt": expires}

    def t_lexc_valid():
        comps = [{"type": "library", "name": "b", "version": "1", "purl": "pkg:maven/g/a/b@1",
                  "licenses": [{"license": {"id": "GPL-2.0-only"}}]}]
        cdx = _valid_cdx(components=comps)
        errs, excs = validate_license_exceptions(
            {"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": [_lexc()]}, NOW)
        assert not errs
        dec = decide_licenses([("sbom/m.cdx.json", cdx)], _lpolicy(), excs, NOW)
        return (dec["overallStatus"] == "PASSED" and dec["countsByDecision"]["exceptionAllowed"] == 1,
                "counts: %s" % dec["countsByDecision"])
    run("license-exception: valid exact unexpired allows denied", t_lexc_valid)

    def t_lexc_expired():
        errs, _ = validate_license_exceptions(
            {"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": [_lexc(expires="2026-01-01")]}, NOW)
        return (bool(errs) and any('expired' in x for x in errs), "errors: %s" % errs)
    run("license-exception: expired rejected", t_lexc_expired)

    def t_lexc_wildcard():
        e = {"component": {"name": "b", "version": "*"}, "license": "GPL-2.0-only", "owner": "x",
             "reason": "x", "mitigation": "x", "reviewCondition": "x", "expiresAt": "2027-01-01"}
        errs, _ = validate_license_exceptions({"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": [e]}, NOW)
        return (bool(errs), "wildcard component not rejected: %s" % errs)
    run("license-exception: wildcard/broad rejected", t_lexc_wildcard)

    def t_lexc_dup():
        errs, _ = validate_license_exceptions(
            {"schemaVersion": SUPPLY_SCHEMA_VERSION, "exceptions": [_lexc(), _lexc()]}, NOW)
        return (bool(errs) and any('duplicate' in x for x in errs), "errors: %s" % errs)
    run("license-exception: duplicate rejected", t_lexc_dup)

    def t_lexc_wrong_component():
        comps = [{"type": "library", "name": "b", "version": "1", "purl": "pkg:maven/g/a/b@1",
                  "licenses": [{"license": {"id": "GPL-2.0-only"}}]}]
        cdx = _valid_cdx(components=comps)
        errs, excs = validate_license_exceptions(
            {"schemaVersion": SUPPLY_SCHEMA_VERSION,
             "exceptions": [_lexc(purl="pkg:maven/g/a/other@9")]} , NOW)
        assert not errs
        dec = decide_licenses([("sbom/m.cdx.json", cdx)], _lpolicy(), excs, NOW)
        return (dec["overallStatus"] == "FAILED" and bool(dec["unusedExceptions"]),
                "dec: %s" % dec)
    run("license-exception: wrong-component/unused rejected", t_lexc_wrong_component)

    # --- repository license separated ---
    def t_repo_separated():
        rl = {"schemaVersion": SUPPLY_SCHEMA_VERSION, "repositoryLicense": "AGPL-3.0-only"}
        e = _repo_license_errors(rl)
        # the repo license must NOT appear as a third-party allow/deny token that a dependency uses;
        # here AGPL-3.0-only is the repo identity and a dependency with AGPL-3.0-only would be unknown
        # under a policy that does not list AGPL-3.0-only (kept separate).
        comps = [{"type": "library", "name": "z", "version": "1", "purl": "pkg:maven/g/a/z@1",
                  "licenses": [{"license": {"id": "AGPL-3.0-only"}}]}]
        cdx = _valid_cdx(components=comps)
        dec = decide_licenses([("sbom/m.cdx.json", cdx)], _lpolicy(), [], NOW)
        return (not e and dec["countsByDecision"]["unknown"] == 1,
                "repo AGPL separated; dep AGPL classified unknown (not auto-allowed): %s" % dec["countsByDecision"])
    run("license: repository AGPL identity separated from third-party policy", t_repo_separated)

    # --- manifest stage validation ---
    def t_stage_m5b_valid():
        m = _full_m5b_manifest()
        errs = releaselib.validate_manifest(m)
        return (not errs, "M5-B manifest rejected: %s" % errs)
    run("manifest: M5-B pre-supply manifest valid at its stage", t_stage_m5b_valid)

    def t_stage_m5c_omit_evidence():
        # declare M5-C stage but leave sbom as NOT_AVAILABLE (omit evidence) -> rejected
        m = _full_m5b_manifest()
        m["supplyChain"] = {"stage": "M5-C"}
        errs = releaselib.validate_manifest(m)
        return (bool(errs), "promoted M5-C omitting evidence not rejected: %s" % errs)
    run("manifest: promoted M5-C cannot omit evidence", t_stage_m5c_omit_evidence)

    def t_signature_not_marked_verified():
        m = _full_m5b_manifest()
        m["artifacts"][0]["signature"] = {"status": "SIGNED"}
        errs = releaselib.validate_manifest(m)
        return (bool(errs) and any('SKIPPED' in x or 'signature' in x for x in errs),
                "fabricated SIGNED not rejected: %s" % errs)
    run("manifest: signature/provenance not marked verified by M5-C", t_signature_not_marked_verified)

    # --- full release build + verify (offline) ---
    def t_verify_pass():
        with tempfile.TemporaryDirectory(prefix='m5c-') as tmp:
            rel, promoted = _build_release_dir(tmp)
            res = verify_release(os.path.join(rel, "release-manifest.json"), now=NOW)
            return (res["ok"] and res["stage"] == "M5-C",
                    "ok=%s errors=%s" % (res["ok"], res["errors"][:3]))
    run("verify: valid M5-C release PASSES offline", t_verify_pass)

    def t_verify_tamper_hash():
        with tempfile.TemporaryDirectory(prefix='m5c-') as tmp:
            rel, _ = _build_release_dir(tmp)
            # tamper a raw grype report hash in the manifest
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            m["supplyChain"]["vulnerabilityScan"]["rawReports"][0]["sha256"] = "0" * 64
            json.dump(m, open(mp, 'w'), indent=2, sort_keys=True)
            open(mp, 'a').write('\n')
            res = verify_release(mp, now=NOW)
            return (not res["ok"] and any('sha256 mismatch' in e for e in res["errors"]),
                    "errors: %s" % [e for e in res["errors"] if 'sha256' in e][:2])
    run("verify: tampered hash fails closed", t_verify_tamper_hash)

    def t_verify_missing_evidence():
        with tempfile.TemporaryDirectory(prefix='m5c-') as tmp:
            rel, _ = _build_release_dir(tmp)
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            # remove a raw report reference entirely (missing evidence)
            del m["supplyChain"]["vulnerabilityScan"]["rawReports"][0]
            json.dump(m, open(mp, 'w'), indent=2, sort_keys=True)
            open(mp, 'a').write('\n')
            res = verify_release(mp, now=NOW)
            return (not res["ok"], "missing evidence accepted? ok=%s" % res["ok"])
    run("verify: missing evidence fails closed", t_verify_missing_evidence)

    def t_verify_malformed_grype_fails():
        with tempfile.TemporaryDirectory(prefix='m5c-') as tmp:
            rel, _ = _build_release_dir(tmp)
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            rr = m["supplyChain"]["vulnerabilityScan"]["rawReports"][0]
            raw = os.path.join(rel, rr["path"])
            _write_json(os.path.dirname(raw), os.path.basename(raw), {
                "descriptor": {"name": "grype", "version": GRYPE_VERSION}, "matches": "not-a-list"
            })
            rr.update(file_evidence(raw))
            json.dump(m, open(mp, 'w'), indent=2, sort_keys=True)
            open(mp, 'a').write('\n')
            res = verify_release(mp, now=NOW)
            return (not res["ok"] and any('matches must be a list' in e for e in res["errors"]),
                    "errors: %s" % [e for e in res["errors"] if 'matches' in e][:2])
    run("verify: malformed raw Grype schema fails closed", t_verify_malformed_grype_fails)

    def t_verify_path_escape():
        with tempfile.TemporaryDirectory(prefix='m5c-') as tmp:
            rel, _ = _build_release_dir(tmp)
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            # absolute path escape
            m["supplyChain"]["sboms"]["maven"]["path"] = "/etc/passwd"
            json.dump(m, open(mp, 'w'), indent=2, sort_keys=True)
            open(mp, 'a').write('\n')
            res = verify_release(mp, now=NOW)
            ok1 = not res["ok"] and any('absolute' in e or 'outside' in e for e in res["errors"])
            # traversal path
            m = json.load(open(mp))
            m["supplyChain"]["sboms"]["maven"]["path"] = "../../escape.cdx.json"
            json.dump(m, open(mp, 'w'), indent=2, sort_keys=True)
            open(mp, 'a').write('\n')
            res2 = verify_release(mp, now=NOW)
            ok2 = not res2["ok"] and any("traversal" in e or 'outside' in e for e in res2["errors"])
            return (ok1 and ok2, "abs=%s trav=%s" % (ok1, ok2))
    run("verify: absolute/traversal path rejected", t_verify_path_escape)

    def t_verify_symlink_escape():
        with tempfile.TemporaryDirectory(prefix='m5c-') as tmp:
            rel, _ = _build_release_dir(tmp)
            # create a symlink inside sbom/ pointing outside the release root
            outside = os.path.join(tmp, "outside.json")
            _write_normalized(outside, _valid_cdx(), "2026-08-04T00:00:00Z")
            link = os.path.join(rel, SBOM_DIR, "escape.cdx.json")
            os.symlink(outside, link)
            mp = os.path.join(rel, "release-manifest.json")
            m = json.load(open(mp))
            m["supplyChain"]["sboms"]["maven"]["path"] = SBOM_DIR + "/escape.cdx.json"
            # fix sha/size to the symlink target so only the escape is the failure
            m["supplyChain"]["sboms"]["maven"]["sha256"] = sha256_file(link)
            m["supplyChain"]["sboms"]["maven"]["size"] = os.path.getsize(link)
            # also fix artifact sbom refs that pointed to maven sbom
            for a in m["artifacts"]:
                if releaselib.artifact_sbom_group(a["name"]) == "maven":
                    a["sbom"]["path"] = SBOM_DIR + "/escape.cdx.json"
                    a["sbom"]["sha256"] = m["supplyChain"]["sboms"]["maven"]["sha256"]
                    a["sbom"]["size"] = m["supplyChain"]["sboms"]["maven"]["size"]
            json.dump(m, open(mp, 'w'), indent=2, sort_keys=True)
            open(mp, 'a').write('\n')
            res = verify_release(mp, now=NOW)
            return (not res["ok"] and any('outside' in e or 'escape' in e for e in res["errors"]),
                    "errors: %s" % [e for e in res["errors"] if 'outside' in e or 'escape' in e][:2])
    run("verify: symlink escape rejected", t_verify_symlink_escape)

    # --- verifier performs NO network calls ---
    def t_verify_no_network():
        import socket as _sock
        orig_socket = _sock.socket
        def _boom(*a, **k):
            raise AssertionError("verify_release made a network socket call")
        _sock.socket = _boom
        try:
            with tempfile.TemporaryDirectory(prefix='m5c-') as tmp:
                rel, _ = _build_release_dir(tmp)
                res = verify_release(os.path.join(rel, "release-manifest.json"), now=NOW)
        finally:
            _sock.socket = orig_socket
        return (res["ok"], "verify completed with network blocked; ok=%s" % res["ok"])
    run("verify: no network calls (socket blocked)", t_verify_no_network)

    # --- both --help interfaces (CLI smoke) ---
    def t_help():
        import subprocess
        for script in ("run-supply-chain.sh", "verify-supply-chain.sh"):
            p = os.path.join(_HERE, "..", script)
            if not os.path.isfile(p):
                return False, "script missing: %s" % script
        # CLI library help
        r = subprocess.run([sys.executable, __file__, "--help"], capture_output=True, text=True)
        return (r.returncode == 0 and "supply-chain" in r.stdout.lower(),
                "rc=%d" % r.returncode)
    run("cli: library --help present", t_help)

    passed = sum(1 for c in checks if c['passed'])
    failed = len(checks) - passed
    summary = {"passed": passed, "failed": failed, "total": len(checks), "checks": checks}
    print(json.dumps(summary, indent=2))
    return 0 if failed == 0 else 1


# --- CLI ----------------------------------------------------------------------------------

def _cmd_normalize_sbom(args):
    with open(args.input, encoding='utf-8') as f:
        doc = json.load(f)
    errs = validate_cyclonedx(doc)
    if errs:
        for e in errs:
            print("error: %s" % e, file=sys.stderr)
        return 1
    normalize_cyclonedx_file(args.input, args.output, args.timestamp)
    ev = file_evidence(args.output)
    print(json.dumps({'path': args.output, 'sha256': ev['sha256'], 'size': ev['size'],
                      'specVersion': doc.get('specVersion'), 'componentCount': count_components(doc)}))
    return 0


def _cmd_validate_sbom(args):
    with open(args.path, encoding='utf-8') as f:
        doc = json.load(f)
    errs = validate_cyclonedx(doc)
    if errs:
        for e in errs:
            print("error: %s" % e, file=sys.stderr)
        return 1
    print(json.dumps({'ok': True, 'specVersion': doc.get('specVersion'),
                     'componentCount': count_components(doc)}))
    return 0


def _cmd_decide_vulnerabilities(args):
    gdocs = []
    input_errors = []
    for pair in args.grype_docs:
        sbom_rel, raw_path = pair.split('=', 1)
        with open(raw_path, encoding='utf-8') as f:
            doc = json.load(f)
        input_errors.extend("%s: %s" % (raw_path, e) for e in validate_grype_document(doc))
        gdocs.append((sbom_rel, doc))
    with open(args.exceptions, encoding='utf-8') as f:
        exc_doc = json.load(f)
    now = _parse_iso(args.now) if args.now else _utcnow()
    errs, excs = validate_vulnerability_exceptions(exc_doc, now)
    errs = input_errors + errs
    if errs:
        for e in errs:
            print("error: %s" % e, file=sys.stderr)
        return 1
    dec = decide_vulnerabilities(gdocs, excs, now)
    report = {
        'schemaVersion': SUPPLY_SCHEMA_VERSION, 'generatedAt': now.isoformat().replace('+00:00', 'Z'),
        'scanner': {'name': 'grype', 'version': GRYPE_VERSION},
        'policyFile': args.policy_rel,
        'countsBySeverity': dec['countsBySeverity'], 'countsByDecision': dec['countsByDecision'],
        'findings': dec['findings'], 'exceptionsUsed': dec['exceptionsUsed'],
        'unusedExceptions': dec['unusedExceptions'],
        'overallStatus': dec['overallStatus'], 'failureReasons': dec['failureReasons'],
    }
    with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(report, f, indent=2, sort_keys=True)
        f.write('\n')
    print(json.dumps({'overallStatus': dec['overallStatus'],
                     'countsBySeverity': dec['countsBySeverity'],
                     'countsByDecision': dec['countsByDecision']}))
    return 0 if dec['overallStatus'] == 'PASSED' else 2


def _cmd_decide_licenses(args):
    sbom_pairs = []
    input_errors = []
    for pair in args.sbom_docs:
        sbom_rel, sbom_path = pair.split('=', 1)
        with open(sbom_path, encoding='utf-8') as f:
            doc = json.load(f)
        input_errors.extend("%s: %s" % (sbom_path, e) for e in validate_cyclonedx(doc))
        sbom_pairs.append((sbom_rel, doc))
    with open(args.policy, encoding='utf-8') as f:
        policy = json.load(f)
    with open(args.exceptions, encoding='utf-8') as f:
        exc_doc = json.load(f)
    now = _parse_iso(args.now) if args.now else _utcnow()
    perrs = validate_license_policy(policy)
    eerrs, excs = validate_license_exceptions(exc_doc, now)
    if input_errors or perrs or eerrs:
        for e in input_errors + perrs + eerrs:
            print("error: %s" % e, file=sys.stderr)
        return 1
    dec = decide_licenses(sbom_pairs, policy, excs, now)
    report = {
        'schemaVersion': SUPPLY_SCHEMA_VERSION, 'generatedAt': now.isoformat().replace('+00:00', 'Z'),
        'policyFile': args.policy_rel, 'exceptionsFile': args.exceptions_rel,
        'repositoryLicense': {'file': args.repo_rel, 'license': 'AGPL-3.0-only'},
        'firstPartyComponentCount': dec['firstPartyComponentCount'],
        'countsByDecision': dec['countsByDecision'], 'components': dec['components'],
        'exceptionsUsed': dec['exceptionsUsed'], 'unusedExceptions': dec['unusedExceptions'],
        'overallStatus': dec['overallStatus'], 'failureReasons': dec['failureReasons'],
    }
    with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(report, f, indent=2, sort_keys=True)
        f.write('\n')
    print(json.dumps({'overallStatus': dec['overallStatus'], 'countsByDecision': dec['countsByDecision']}))
    return 0 if dec['overallStatus'] == 'PASSED' else 2


def _cmd_promote_manifest(args):
    with open(args.manifest, encoding='utf-8') as f:
        manifest = json.load(f)
    with open(args.spec, encoding='utf-8') as f:
        spec = json.load(f)
    promoted = promote_manifest(manifest, spec)
    with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(promoted, f, indent=2, sort_keys=True)
        f.write('\n')
    print(json.dumps({'ok': True, 'overallStatus': promoted['supplyChain']['overallStatus']}))
    return 0


def _cmd_verify_release(args):
    res = verify_release(args.manifest)
    for n in res['notes']:
        print(n)
    if res['errors']:
        for e in res['errors']:
            print("error: %s" % e, file=sys.stderr)
        return 1
    print("ok: supply-chain verification PASSED (stage=%s)" % res['stage'])
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description="V1.7 M5-C supply-chain gate library")
    sub = p.add_subparsers(dest='cmd')

    sub.add_parser('self-test', help="run focused supply-chain assertions")

    ns = sub.add_parser('normalize-sbom', help="validate + deterministically normalize a CycloneDX JSON SBOM")
    ns.add_argument('--input', required=True)
    ns.add_argument('--output', required=True)
    ns.add_argument('--timestamp', required=True, help="ISO-8601 timestamp for metadata.timestamp")

    vs = sub.add_parser('validate-sbom', help="validate a CycloneDX JSON document; exit 0 iff valid")
    vs.add_argument('path')

    dv = sub.add_parser('decide-vulnerabilities', help="decide the vulnerability gate from raw Grype JSON")
    dv.add_argument('--grype-doc', dest='grype_docs', action='append', required=True,
                    help="sbomRel=rawGrypeJsonPath (repeatable)")
    dv.add_argument('--exceptions', required=True)
    dv.add_argument('--policy-rel', required=True, help="release-root-relative exceptions path")
    dv.add_argument('--now')
    dv.add_argument('--out', required=True)

    dl = sub.add_parser('decide-licenses', help="decide the third-party license gate from SBOMs")
    dl.add_argument('--sbom-doc', dest='sbom_docs', action='append', required=True,
                   help="sbomRel=sbomJsonPath (repeatable)")
    dl.add_argument('--policy', required=True)
    dl.add_argument('--exceptions', required=True)
    dl.add_argument('--policy-rel', required=True)
    dl.add_argument('--exceptions-rel', required=True)
    dl.add_argument('--repo-rel', required=True)
    dl.add_argument('--now')
    dl.add_argument('--out', required=True)

    pm = sub.add_parser('promote-manifest', help="promote an M5-B manifest to M5-C")
    pm.add_argument('--manifest', required=True)
    pm.add_argument('--spec', required=True)
    pm.add_argument('--out', required=True)

    vr = sub.add_parser('verify-release', help="offline-verify a release manifest + supply-chain evidence")
    vr.add_argument('--manifest', required=True)

    args = p.parse_args(argv)
    if args.cmd == 'self-test':
        return self_test()
    if args.cmd == 'normalize-sbom':
        return _cmd_normalize_sbom(args)
    if args.cmd == 'validate-sbom':
        return _cmd_validate_sbom(args)
    if args.cmd == 'decide-vulnerabilities':
        return _cmd_decide_vulnerabilities(args)
    if args.cmd == 'decide-licenses':
        return _cmd_decide_licenses(args)
    if args.cmd == 'promote-manifest':
        return _cmd_promote_manifest(args)
    if args.cmd == 'verify-release':
        return _cmd_verify_release(args)
    p.print_help()
    return 1


if __name__ == '__main__':
    sys.exit(main())
