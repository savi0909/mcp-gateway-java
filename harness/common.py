"""Strict task contracts, Git access and durable state primitives (stdlib only)."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import uuid


class Halt(Exception):
    """An explicit stop condition with a safe, non-secret diagnostic."""


def require(condition, message):
    if not condition:
        raise Halt(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON key")
            result[key] = value
        return result
    try:
        require(Path(path).stat().st_size <= 2_000_000, "JSON input too large")
        return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=unique,
                          parse_constant=lambda _: (_ for _ in ()).throw(Halt("Invalid JSON number")))
    except (OSError, ValueError) as error:
        raise Halt("Cannot read valid JSON: " + Path(path).name) from error


def atomic_json(path, value):
    """Replace state atomically; retain no automatic cleanup/deletion operation."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".pending")
    with temporary.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(value, indent=2, ensure_ascii=True) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    if os.name != "nt":
        fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def validate_schema(value, schema, where="document"):
    """Validate the deliberately small JSON Schema subset used by our owned files."""
    supported = {"$schema", "title", "description", "type", "properties", "required",
                 "additionalProperties", "items", "enum", "const", "minLength",
                 "minItems", "minimum", "maximum", "pattern"}
    require(not set(schema) - supported, "Unsupported schema keyword")
    kind = schema.get("type")
    types = {"object": dict, "array": list, "string": str, "integer": int,
             "boolean": bool, "null": type(None)}
    if kind:
        options = kind if isinstance(kind, list) else [kind]
        require(all(k in types for k in options), "Unsupported schema type")
        require(any(type(value) is types[k] for k in options), where + ": wrong type")
    if "enum" in schema:
        require(value in schema["enum"], where + ": unknown value")
    if "const" in schema:
        require(value == schema["const"], where + ": incorrect constant")
    if isinstance(value, dict):
        props = schema.get("properties", {})
        require(set(schema.get("required", [])) <= set(value), where + ": missing field")
        if schema.get("additionalProperties") is False:
            require(not set(value) - set(props), where + ": unknown field")
        for key, item in value.items():
            if key in props:
                validate_schema(item, props[key], where + "." + key)
    if isinstance(value, list):
        require(len(value) >= schema.get("minItems", 0), where + ": empty list")
        if "items" in schema:
            for item in value:
                validate_schema(item, schema["items"], where + "[]")
    if isinstance(value, str):
        require(len(value) >= schema.get("minLength", 0), where + ": empty string")
        if "pattern" in schema:
            require(re.fullmatch(schema["pattern"], value) is not None, where + ": invalid format")
    if type(value) is int:
        require(schema.get("minimum", value) <= value <= schema.get("maximum", value),
                where + ": outside bounds")


def relative_path(value):
    require(isinstance(value, str) and bool(value), "Empty path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and ".." not in path.parts and "." not in path.parts
            and str(path) == value and "\\" not in value and ":" not in value
            and not any(c in value for c in "*?[]\x00\r\n"), "Unsafe relative path")
    require(all(not part.startswith(".") for part in path.parts), "Hidden path not allowed")
    return value


def contained(root, relative):
    relative_path(relative)
    root = Path(root).resolve()
    candidate = root / relative
    cursor = root
    for part in PurePosixPath(relative).parts:
        cursor = cursor / part
        require(not cursor.is_symlink() and not (hasattr(cursor, "is_junction") and cursor.is_junction()),
                "Symlink/junction path denied")
    require(candidate.resolve().is_relative_to(root), "Path escapes root")
    return candidate


def git(root, *args, check=True):
    # No inherited Git injection/configuration; no shell interpolation or global hook execution.
    env = {k: v for k, v in os.environ.items() if not k.upper().startswith("GIT_")}
    env.update(GIT_TERMINAL_PROMPT="0", GIT_CONFIG_NOSYSTEM="1")
    result = subprocess.run(["git", "-c", "core.hooksPath=" + os.devnull,
                             "-C", str(root), *args], capture_output=True, env=env, timeout=60)
    if check and result.returncode:
        raise Halt("Git operation failed: " + args[0])
    return result.stdout.decode("utf-8", errors="strict").rstrip("\r\n") if check else result


def clean(root, except_paths=()):
    # Porcelain -z leaves spaces intact, unlike human status parsing.
    output = git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all")
    entries = output.split("\0") if output else []
    require(all(len(e) >= 4 and e[3:] in except_paths and e[:2] in (" M", "M ", "MM")
                for e in entries if e), "Unsafe dirty Git state")


def trusted_fingerprint(root):
    paths = git(root, "ls-files", "harness", "scripts/verify.ps1", "scripts/verify.sh", "tasks/schema.json").splitlines()
    require(bool(paths), "Harness must be committed before execution")
    return digest({p: hashlib.sha256((Path(root) / p).read_bytes()).hexdigest() for p in paths})


APPROVED_FIELDS = ("id", "title", "priority", "risk", "dependencies", "spec_refs", "scope",
                   "acceptance_criteria", "tests", "requires_human_approval", "review_required",
                   "model_role", "phase", "base_commit")


def scope_hash(task):
    return digest({key: task[key] for key in APPROVED_FIELDS})


def load_backlog(root):
    root = Path(root)
    data = read_json(root / "tasks/backlog.json")
    validate_schema(data, read_json(root / "tasks/schema.json"))
    tasks = {t["id"]: t for t in data["tasks"]}
    require(len(tasks) == len(data["tasks"]), "Duplicate task IDs")
    for task in tasks.values():
        require(len(set(task["scope"])) == len(task["scope"]), "Duplicate scope paths")
        require(len(set(task["dependencies"])) == len(task["dependencies"]), "Duplicate dependencies")
        require(set(task["dependencies"]) <= set(tasks), "Missing dependency")
        require(task["id"] not in task["dependencies"], "Self dependency")
        for ref in task["spec_refs"]:
            require(contained(root, ref).is_file(), "Missing specification reference")
        for path in task["scope"]:
            contained(root, path)
            require(not path.startswith(("harness/", "tasks/", "scripts/", "docs/requirements/"))
                    and path not in ("AGENTS.md", "CLAUDE.md", "pom.xml", "mvnw", "mvnw.cmd"),
                    "Protected task path; harness/bootstrap changes require direct reviewed work")
        approval = task["approval"]
        if task["status"] in ("ready", "in_progress", "validating", "completed"):
            require(approval is not None and approval["scope_sha256"] == scope_hash(task),
                    "Missing or stale scope approval")
            require(not task["requires_human_approval"], "Unresolved human approval")
            require(contained(root, approval["reference"]).is_file(), "Missing approval reference")
        if task["status"] == "completed":
            require(task["content_commit"] is not None and task["evidence"] is not None,
                    "Completion lacks commit/evidence")
        for field, pattern in (("run_id", r"[a-f0-9]{32}"), ("evidence", r"[a-f0-9]{32}"),
                               ("content_commit", r"[a-f0-9]{40}")):
            require(task[field] is None or re.fullmatch(pattern, task[field]) is not None,
                    "Invalid completion/ownership reference")
    visiting, visited = set(), set()
    def visit(identifier):
        require(identifier not in visiting, "Dependency cycle")
        if identifier in visited:
            return
        visiting.add(identifier)
        for dependency in tasks[identifier]["dependencies"]:
            visit(dependency)
        visiting.remove(identifier)
        visited.add(identifier)
    for identifier in tasks:
        visit(identifier)
    return data


class Ownership:
    """OS lock released on crash; stable file retained, never PID-based stealing."""
    def __init__(self, path):
        self.path = Path(path)
        self.stream = None

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.stream = self.path.open("a+b")
        if self.path.stat().st_size == 0:
            self.stream.write(b"0")
            self.stream.flush()
        self.stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            self.stream.close()
            raise Halt("Another runner owns the repository") from error
        return self

    def __exit__(self, *_):
        self.stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(self.stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(self.stream, fcntl.LOCK_UN)
        self.stream.close()
