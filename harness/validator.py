"""Independent candidate and Maven report validation, controlled by the runner."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import time
import uuid
import xml.etree.ElementTree as ET

from harness.common import Halt, contained, digest, git, require
from harness.processes import ProcessFailure, run_process


class ValidationFailure(Halt):
    """A source/test failure eligible for a bounded repair."""


def candidate(root, base, task):
    require(git(root, "rev-parse", "HEAD") == base, "Agent changed Git history")
    require(not git(root, "diff", "--cached", "--name-only"), "Agent staged files")
    status = git(root, "diff", "--name-status", "--no-renames", "-z", base).split("\0")
    changes = {}
    for i in range(0, len(status) - 1, 2):
        code, name = status[i:i + 2]
        require(code in ("M", "A"), "Deletion, rename or type change requires explicit permission")
        changes[name] = code
    for name in git(root, "ls-files", "--others", "--exclude-standard", "-z").split("\0"):
        if name:
            changes[name] = "A"
    require(bool(changes), "Task produced no candidate changes")
    require(set(changes) <= set(task["scope"]), "Candidate exceeds approved exact file scope")
    for name in changes:
        path = contained(root, name)
        require(path.is_file() and path.stat().st_size <= 2_000_000, "Candidate is not a bounded regular file")
        require(path.stat().st_nlink == 1, "Hard-linked candidate file denied")
    require(git(root, "diff", "--check", check=False).returncode == 0, "Candidate whitespace check failed")
    return {"files": sorted(changes), "sha256": digest({name: hashlib.sha256(contained(root, name).read_bytes()).hexdigest()
                                                      for name in sorted(changes)})}


def reports(build, baseline, started):
    """Require fresh successful reports and every known suite, without a fixed total count."""
    build = Path(build)
    summary = {}
    for kind in ("surefire", "failsafe"):
        directory = build / (kind + "-reports")
        files = list(directory.glob("TEST-*.xml"))
        require(bool(files), "Missing Maven reports")
        found, totals = {}, {key: 0 for key in ("tests", "failures", "errors", "skipped")}
        for path in files:
            require(not path.is_symlink() and path.resolve().is_relative_to(build.resolve()), "Report path escape")
            require(path.stat().st_mtime >= started - 2 and path.stat().st_size <= 20_000_000,
                    "Stale or oversized Maven report")
            try:
                data = path.read_bytes()
                require(b"<!DOCTYPE" not in data and b"<!ENTITY" not in data, "Unsafe XML report")
                suite = ET.fromstring(data)
                require(suite.tag == "testsuite", "Unexpected report root")
                values = {key: int(suite.attrib[key]) for key in totals}
                require(all(v >= 0 for v in values.values()), "Invalid test counts")
                cases = list(suite.findall("testcase"))
                require(len(cases) == values["tests"], "Report counts disagree with test cases")
                if any(values[key] for key in ("failures", "errors", "skipped")) or any(
                        case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
                    raise ValidationFailure("Required Maven tests failed or skipped")
                name = suite.attrib["name"]
                require(name not in found, "Duplicate report suite")
                found[name] = values["tests"]
                for key in totals:
                    totals[key] += values[key]
            except (ET.ParseError, KeyError, ValueError) as error:
                raise Halt("Malformed Maven report") from error
        for name, minimum in baseline[kind].items():
            require(found.get(name, 0) >= minimum, "Required test coverage disappeared: " + name)
        summary[kind] = totals
    require(any(build.glob("*.jar")), "Packaged JAR missing")
    return summary


class MavenVerifier:
    def __init__(self, trusted_root, config):
        self.root, self.config = Path(trusted_root), config

    def verify(self, worktree, run_dir, timeout, cancel):
        run_id = "verify-" + uuid.uuid4().hex
        build = Path(worktree) / "target/harness" / run_id
        require(not build.exists(), "Verification directory already exists")
        started = time.time()
        if os.name == "nt":
            command = ["pwsh", "-NoProfile", "-File", str(self.root / "scripts/verify.ps1"),
                       "-Repository", str(worktree), "-RunId", run_id]
        else:
            command = ["sh", str(self.root / "scripts/verify.sh"), str(worktree), run_id]
        result = run_process(command, self.root, timeout, cancel=cancel, max_output=8_000_000,
                             heartbeat=lambda: print("Independent Maven verification running.", flush=True))
        if result["exit_code"]:
            # Classify source/test failures only when actual report evidence exists.
            # Network/toolchain/plugin errors are infrastructure stops, never agent repair loops.
            text = result["output"]
            if "COMPILATION ERROR" in text or "There are test failures" in text:
                raise ValidationFailure("Compilation or Maven tests failed")
            raise ProcessFailure("Verification infrastructure failed",
                                 {k: result[k] for k in ("exit_code", "duration_seconds")})
        summary = reports(build, self.config["baseline"], started)
        return {"command_id": "maven-verify", "exit_code": 0, "duration_seconds": result["duration_seconds"],
                "build_directory": str(build), "reports": summary}


def review_report(report, scope):
    require(report["verdict"] in ("approve", "request_changes"), "Invalid review verdict")
    findings = []
    for finding in report["findings"]:
        require(finding["file"] in scope, "Review references unapproved path")
        findings.append({k: finding[k] for k in ("severity", "code", "file", "line")})
    # Conservative for the first pilot: any unresolved finding blocks completion.
    return {"approved": report["verdict"] == "approve" and not findings, "findings": findings}
