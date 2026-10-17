"""A single-task, fail-closed engineering runner. Invoke with python -B -m harness.orchestrator."""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
import re
import signal
import threading
import time
import tomllib
import uuid

from harness.common import (Halt, Ownership, atomic_json, canonical, clean, contained, digest,
                            git, load_backlog, read_json, require, scope_hash, trusted_fingerprint)
from harness.processes import Codex, ProcessFailure
from harness.validator import MavenVerifier, ValidationFailure, candidate, review_report


def configuration(root):
    with (Path(root) / "harness/config.toml").open("rb") as stream:
        config = tomllib.load(stream)
    require(set(config) == {"schema_version", "batch_enabled", "publish", "limits", "models", "baseline"},
            "Unknown/missing harness configuration")
    require(config["schema_version"] == 1 and config["batch_enabled"] is False,
            "Batch is disabled in Phase 1")
    require(type(config["publish"]) is bool, "Invalid publication setting")
    caps = {"run_seconds": 3600, "agent_seconds": 1200, "verify_seconds": 900,
            "review_seconds": 600, "max_repairs": 3}
    require(set(config["limits"]) == set(caps), "Invalid limit fields")
    for key, maximum in caps.items():
        value = config["limits"][key]
        require(type(value) is int and (0 if key == "max_repairs" else 1) <= value <= maximum,
                "Unsafe runtime/repair limit")
    require(set(config["models"]) == {"implement", "complex_implement", "review"}, "Invalid model roles")
    for model in config["models"].values():
        require(set(model) == {"name", "reasoning_effort"}, "Invalid model settings")
        require(re.fullmatch(r"[a-zA-Z0-9.-]+", model["name"]) is not None
                and model["reasoning_effort"] in ("low", "medium", "high"), "Unsafe model setting")
    require(set(config["baseline"]) == {"surefire", "failsafe"}, "Missing report baseline")
    for suites in config["baseline"].values():
        require(bool(suites) and all(type(n) is int and n > 0 for n in suites.values()), "Invalid test baseline")
    return config


class Runner:
    def __init__(self, root, *, agent=None, verifier=None, fault=None):
        self.root = Path(root).resolve()
        require(Path(git(self.root, "rev-parse", "--show-toplevel")).resolve() == self.root,
                "Run from the controlling repository root")
        self.config = configuration(self.root)
        self.common = Path(git(self.root, "rev-parse", "--path-format=absolute", "--git-common-dir"))
        self.control = self.common / "engineering-harness"
        self.worktrees = self.root.parent / ("." + self.root.name + "-harness-worktrees")
        self.agent = agent or Codex()
        self.verifier = verifier or MavenVerifier(self.root, self.config)
        self.cancel = threading.Event()
        self.fault = fault or (lambda _: None)  # deterministic crash injection for isolated tests

    def bootstrap(self):
        # Shipped control files are the template. Validate without overwriting existing files.
        data = load_backlog(self.root)
        for name in ("implement", "review"):
            require((self.root / f"harness/prompts/{name}.md").is_file(), "Prompt template missing")
        for name in ("implementation", "review"):
            read_json(self.root / f"harness/{name}-report.schema.json")
        require((self.root / "scripts/verify.ps1").is_file() and (self.root / "scripts/verify.sh").is_file(),
                "Verification entry point missing")
        return {"mode": "bootstrap", "valid": True, "tasks": len(data["tasks"]), "files_changed": []}

    def status(self):
        data = load_backlog(self.root)
        return {"tasks": [{k: t[k] for k in ("id", "status", "dependencies", "attempts", "run_id",
                                                  "content_commit", "block_reason")} for t in data["tasks"]],
                "pilot_authorized": data["pilot_authorization"] is not None, "batch_enabled": False}

    def inspect(self, run_id):
        require(isinstance(run_id, str) and re.fullmatch(r"[a-f0-9]{32}", run_id) is not None, "Invalid run ID")
        state = read_json(self.control / "runs" / run_id / "state.json")
        keys = ("run_id", "task_id", "stage", "attempts", "deadline", "worktree", "branch", "base_commit",
                "candidate", "verification", "review", "events", "content_commit", "metadata_commit", "stop_reason")
        return {key: state[key] for key in keys if key in state}

    def eligible(self, data, requested=None):
        tasks = {t["id"]: t for t in data["tasks"]}
        if requested:
            require(requested in tasks, "Unknown task ID")
        ready = [t for t in data["tasks"] if t["status"] == "ready" and (not requested or t["id"] == requested)
                 and all(tasks[d]["status"] == "completed" for d in t["dependencies"])]
        if not ready:
            return None
        require(data["pilot_authorization"] is not None, "Phase 2 pilot approval is required")
        require(contained(self.root, data["pilot_authorization"]).is_file(), "Pilot approval record missing")
        ready.sort(key=lambda t: ({"high": 0, "medium": 1, "low": 2}[t["priority"]], t["id"]))
        task = ready[0]
        require(task["phase"] == 2, "Post-pilot tasks require a later execution policy")
        for dependency in task["dependencies"]:
            completed = tasks[dependency]
            require(git(self.root, "cat-file", "-t", completed["content_commit"]) == "commit",
                    "Dependency commit missing")
            require(completed["evidence"] is not None, "Dependency evidence missing")
            record = read_json(self.control / "runs" / completed["evidence"] / "state.json")
            require(record.get("content_commit") == completed["content_commit"]
                    and record.get("task_id") == dependency
                    and record.get("verification", {}).get("exit_code") == 0
                    and record.get("review", {}).get("approved") is True
                    and record.get("stage") in ("metadata_committed", "published"), "Dependency not independently completed")
            require(git(self.root, "merge-base", "--is-ancestor", completed["content_commit"],
                        task["base_commit"], check=False).returncode == 0,
                    "Dependency code has not been integrated into the approved source base")
        return task

    def dry_run(self, requested=None):
        data = load_backlog(self.root)
        task = self.eligible(data, requested)
        result = {"mode": "dry-run", "selected_task": None, "batch_enabled": False,
                  "limits": self.config["limits"], "files_changed": [], "model_calls": 0}
        if task is None:
            result["reason"] = "No approved ready task with completed dependencies"
            result["pending_tasks"] = [t["id"] for t in data["tasks"] if t["status"] == "pending"]
            return result
        clean(self.root)
        self._approved_base(task, data)
        result.update(selected_task=task["id"], base_commit=task["base_commit"], scope=task["scope"],
                      spec_refs=task["spec_refs"], model=self.config["models"][task["model_role"]],
                      commands=["fresh codex exec in isolated worktree", "trusted scripts/verify.ps1 or verify.sh",
                                "fresh read-only codex review", "task-scoped content commit", "backlog metadata commit"],
                      intended_git_effects=["new task branch/worktree", "update tasks/backlog.json",
                                            "publish task branch and controlling metadata branch" if self.config["publish"] else "local commits only"])
        return result

    def _approved_base(self, task, data):
        # Approval metadata cannot contain the SHA of its own commit. Permit only the
        # reviewed approval/backlog commit(s) after the approved source revision.
        require(git(self.root, "merge-base", "--is-ancestor", task["base_commit"], "HEAD", check=False).returncode == 0,
                "Approved source revision is not an ancestor")
        changed = set(git(self.root, "diff", "--name-only", task["base_commit"], "HEAD").splitlines())
        allowed = {"tasks/backlog.json", task["approval"]["reference"], data["pilot_authorization"]}
        require(changed <= allowed, "Source changed after task approval; reapproval required")

    def _record(self, state, stage=None):
        if stage:
            state["stage"] = stage
        state["updated_at"] = time.time()
        atomic_json(self.control / "runs" / state["run_id"] / "state.json", state)
        self.fault("state:" + state["stage"])

    def _task_state(self, state, status, **values):
        # Intent first: recovery accepts only the old or exactly intended next backlog.
        previous = copy.deepcopy(state["backlog"])
        task = next(t for t in state["backlog"]["tasks"] if t["id"] == state["task_id"])
        task.update(status=status, **values)
        state["previous_backlog_sha256"] = digest(previous)
        self._record(state)
        atomic_json(self.root / "tasks/backlog.json", state["backlog"])
        self.fault("backlog:" + status)

    def _budget(self, state, kind):
        require(not self.cancel.is_set(), "Cancelled")
        remaining = state["deadline"] - time.time()
        require(remaining > 0, "Run runtime budget exhausted")
        return min(remaining, self.config["limits"][kind + "_seconds"])

    def _guard(self, state):
        require(trusted_fingerprint(self.root) == state["trusted_sha256"], "Trusted harness changed during run")
        require(digest(read_json(self.root / "tasks/backlog.json")) == digest(state["backlog"]),
                "Task state changed outside runner")
        clean(self.root, ("tasks/backlog.json",))
        require(git(self.root, "symbolic-ref", "--short", "HEAD") == state["control_branch"], "Controlling branch changed")
        expected = state.get("metadata_commit") or state["base_commit"]
        require(git(self.root, "rev-parse", "HEAD") == expected, "Controlling history changed")
        if Path(state["worktree"]).exists():
            path = Path(state["worktree"])
            require(not path.is_symlink() and not path.is_junction()
                    and not self.worktrees.is_symlink() and not self.worktrees.is_junction(), "Worktree path redirect denied")
            require(git(state["worktree"], "symbolic-ref", "--short", "HEAD") == state["branch"], "Task branch changed")
            require(Path(git(path, "rev-parse", "--path-format=absolute", "--git-common-dir")) == self.common,
                    "Task worktree belongs to a different repository")
        task = next(t for t in state["backlog"]["tasks"] if t["id"] == state["task_id"])
        require(scope_hash(task) == state["approved_scope_sha256"], "Scope approval changed")

    def _prompt(self, task, review=False, repairs=None):
        template = (self.root / f"harness/prompts/{'review' if review else 'implement'}.md").read_text(encoding="utf-8")
        payload = {k: task[k] for k in ("id", "title", "spec_refs", "scope", "acceptance_criteria", "base_commit")}
        payload["repair_findings"] = repairs or []
        return template + "\nApproved task:\n" + json.dumps(payload, indent=2)

    def single_task(self, requested=None):
        with Ownership(self.control / "runner.lock"):
            clean(self.root)
            data = load_backlog(self.root)
            task = self.eligible(data, requested)
            require(task is not None, "No approved ready task")
            require(not any(t["status"] in ("in_progress", "validating", "needs_review") for t in data["tasks"]),
                    "Reconcile outstanding task before starting another")
            base = git(self.root, "rev-parse", "HEAD")
            self._approved_base(task, data)
            require(task["attempts"] == 0 and task["run_id"] is None, "Previously attempted task requires reconciliation")
            preflight = self.agent.preflight(self.root, self.config)
            run_id = uuid.uuid4().hex
            state = {"run_id": run_id, "task_id": task["id"], "stage": "created", "base_commit": base,
                     "control_branch": git(self.root, "symbolic-ref", "--short", "HEAD"),
                     "branch": "harness/" + task["id"].lower() + "-" + run_id,
                     "worktree": str(self.worktrees / run_id),
                     "trusted_sha256": trusted_fingerprint(self.root), "approved_scope_sha256": scope_hash(task),
                     "backlog": data, "previous_backlog_sha256": digest(data), "attempts": 0,
                     "deadline": time.time() + self.config["limits"]["run_seconds"], "preflight": preflight,
                     "events": []}
            self._record(state)
            self._task_state(state, "in_progress", run_id=run_id)
            return self._continue(state)

    def _continue(self, state):
        try:
            return self._lifecycle(state)
        except (Halt, KeyboardInterrupt, OSError, ValueError, KeyError, TypeError) as error:
            # Preserve commit-prepared stages so resume can finalize, never reimplement them.
            state["stop_reason"] = ("Cancelled" if isinstance(error, KeyboardInterrupt) else str(error)
                                    if isinstance(error, Halt) else "Unavailable infrastructure or invalid state")
            if isinstance(error, ProcessFailure):
                state["events"].append({"kind": "process_failure", **error.metadata})
            if state["stage"] not in ("prepared", "content_committed", "metadata_committed", "published"):
                # Never overwrite someone else's concurrent edit when the guard detects drift.
                try:
                    unchanged = digest(read_json(self.root / "tasks/backlog.json")) == digest(state["backlog"])
                except Halt:
                    unchanged = False
                if unchanged:
                    self._task_state(state, "needs_review", attempts=state["attempts"], run_id=state["run_id"],
                                     block_reason=state["stop_reason"])
                self._record(state, "needs_review")
            else:
                self._record(state)
            raise

    def _lifecycle(self, state):
        self._guard(state)
        task = next(t for t in state["backlog"]["tasks"] if t["id"] == state["task_id"])
        worktree = Path(state["worktree"])
        if state["stage"] == "created":
            self._task_state(state, "in_progress", run_id=state["run_id"])
            self._budget(state, "agent")
            require(not worktree.exists(), "Unexpected existing worktree")
            require(not self.worktrees.is_symlink() and not self.worktrees.is_junction(), "Worktree parent redirect denied")
            worktree.parent.mkdir(parents=True, exist_ok=True)
            git(self.root, "worktree", "add", "-b", state["branch"], str(worktree), state["base_commit"])
            self._record(state, "worktree_created")
        if state["stage"] == "executing":
            raise Halt("Interrupted implementation requires review; no automatic replay")
        while state["stage"] in ("worktree_created", "repair", "candidate", "verified", "reviewed"):
            self._guard(state)
            if state["stage"] in ("worktree_created", "repair"):
                require(state["attempts"] < 1 + self.config["limits"]["max_repairs"], "Repair limit exhausted")
                state["attempts"] += 1
                self._record(state, "executing")
                self._task_state(state, "in_progress", attempts=state["attempts"])
                report, metadata = self.agent.execute(worktree, self.config["models"][task["model_role"]],
                    self._prompt(task, repairs=state.get("findings")), self.root / "harness/implementation-report.schema.json",
                    self._budget(state, "agent"), self.cancel)
                require(report["status"] == "completed", "Implementation agent reported blocked")
                state["events"].append({"kind": "implementation", "attempt": state["attempts"], **metadata})
                self._guard(state)
                state["candidate"] = candidate(worktree, state["base_commit"], task)
                self._record(state, "candidate")
            if state["stage"] == "candidate":
                require(candidate(worktree, state["base_commit"], task) == state["candidate"], "Candidate changed before verification")
                self._task_state(state, "validating")
                try:
                    evidence = self.verifier.verify(worktree, self.control / "runs" / state["run_id"],
                                                    self._budget(state, "verify"), self.cancel)
                except ValidationFailure:
                    state["findings"] = [{"code": "tests_failed"}]
                    state["events"].append({"kind": "verification", "attempt": state["attempts"], "passed": False})
                    self._record(state, "repair")
                    continue
                self._guard(state)
                require(candidate(worktree, state["base_commit"], task) == state["candidate"], "Verification modified candidate")
                state["verification"] = evidence
                self._record(state, "verified")
            if state["stage"] == "verified":
                report, metadata = self.agent.execute(worktree, self.config["models"]["review"],
                    self._prompt(task, review=True), self.root / "harness/review-report.schema.json",
                    self._budget(state, "review"), self.cancel, review=True)
                review = review_report(report, task["scope"])
                self._guard(state)
                require(candidate(worktree, state["base_commit"], task) == state["candidate"], "Review modified candidate")
                state["events"].append({"kind": "review", "attempt": state["attempts"], **metadata})
                state["review"] = review
                if not review["approved"]:
                    state["findings"] = review["findings"] or [{"code": "evidence_gap"}]
                    self._record(state, "repair")
                    continue
                self._record(state, "reviewed")
            if state["stage"] == "reviewed":
                self._budget(state, "agent")
                require(candidate(worktree, state["base_commit"], task) == state["candidate"], "Candidate changed after review")
                self._record(state, "prepared")
        if state["stage"] == "prepared":
            require(state.get("review", {}).get("approved") and state.get("verification", {}).get("exit_code") == 0,
                    "Prepared completion missing independent gates")
            head = git(worktree, "rev-parse", "HEAD")
            marker = "Harness content " + state["run_id"]
            if head == state["base_commit"]:
                # If interrupted after staging, compare the complete prepared tree before committing.
                staged = git(worktree, "diff", "--cached", "--name-only").splitlines()
                if staged:
                    require(sorted(staged) == state["candidate"]["files"], "Unexpected staged candidate")
                    require(not git(worktree, "diff", "--name-only"), "Staged content differs from working files")
                    self._check_files(state)
                else:
                    require(candidate(worktree, state["base_commit"], task) == state["candidate"], "Prepared candidate changed")
                    git(worktree, "add", "--", *state["candidate"]["files"])
                git(worktree, "commit", "-m", marker)
                self.fault("content_commit")
                head = git(worktree, "rev-parse", "HEAD")
            require(git(worktree, "log", "-1", "--format=%s") == marker
                    and git(worktree, "rev-parse", "HEAD^") == state["base_commit"], "Unrecognized content commit")
            self._check_files(state)
            clean(worktree)
            require(sorted(git(worktree, "diff", "--name-only", "HEAD^", "HEAD").splitlines()) == state["candidate"]["files"],
                    "Content commit exceeds validated scope")
            state["content_commit"] = head
            self._record(state, "content_committed")
        if state["stage"] == "content_committed":
            self._task_state(state, "completed", content_commit=state["content_commit"],
                             evidence=state["run_id"], block_reason=None)
            marker = "Harness completion " + state["run_id"]
            head = git(self.root, "rev-parse", "HEAD")
            if head == state["base_commit"]:
                git(self.root, "add", "--", "tasks/backlog.json")
                require(git(self.root, "diff", "--cached", "--name-only") == "tasks/backlog.json", "Unexpected metadata stage")
                git(self.root, "commit", "-m", marker)
                self.fault("metadata_commit")
                head = git(self.root, "rev-parse", "HEAD")
            require(git(self.root, "log", "-1", "--format=%s") == marker
                    and git(self.root, "rev-parse", "HEAD^") == state["base_commit"], "Unrecognized metadata commit")
            state["metadata_commit"] = head
            self._record(state, "metadata_committed")
        if state["stage"] == "metadata_committed":
            self._guard(state)
            clean(worktree)
            require(git(worktree, "rev-parse", "HEAD") == state["content_commit"], "Completed content history changed")
            self._check_files(state)
            if self.config["publish"]:
                # Explicit refs; never force-push or merge task code into the controlling branch.
                git(self.root, "push", "origin", state["branch"] + ":" + state["branch"])
                git(self.root, "push", "origin", state["control_branch"] + ":" + state["control_branch"])
                self._record(state, "published")
            return {"task": state["task_id"], "run_id": state["run_id"], "status": "completed",
                    "content_commit": state["content_commit"], "metadata_commit": state["metadata_commit"],
                    "published": state["stage"] == "published", "worktree_retained": str(worktree)}
        require(state["stage"] == "published", "Run requires manual review before resumption")
        return {"task": state["task_id"], "status": "completed", "run_id": state["run_id"], "published": True}

    def _check_files(self, state):
        import hashlib
        current = {name: hashlib.sha256(contained(state["worktree"], name).read_bytes()).hexdigest()
                   for name in state["candidate"]["files"]}
        require(digest(current) == state["candidate"]["sha256"], "Validated content hash mismatch")

    def resume(self, run_id):
        require(re.fullmatch(r"[a-f0-9]{32}", run_id) is not None, "Invalid run ID")
        with Ownership(self.control / "runner.lock"):
            state = read_json(self.control / "runs" / run_id / "state.json")
            require(state["run_id"] == run_id and Path(state["worktree"]) == self.worktrees / run_id,
                    "Run ownership mismatch")
            require(trusted_fingerprint(self.root) == state["trusted_sha256"], "Trusted harness changed")
            current = digest(read_json(self.root / "tasks/backlog.json"))
            require(current in (digest(state["backlog"]), state["previous_backlog_sha256"]), "Backlog diverged during interruption")
            clean(self.root, ("tasks/backlog.json",))
            atomic_json(self.root / "tasks/backlog.json", state["backlog"])
            # Reconcile an already-created metadata commit before the ordinary guard checks HEAD.
            head = git(self.root, "rev-parse", "HEAD")
            if head != state["base_commit"] and not state.get("metadata_commit"):
                require(state["stage"] == "content_committed"
                        and git(self.root, "log", "-1", "--format=%s") == "Harness completion " + run_id
                        and git(self.root, "rev-parse", "HEAD^") == state["base_commit"]
                        and git(self.root, "diff", "--name-only", "HEAD^", "HEAD") == "tasks/backlog.json",
                        "Unexpected commit during recovery")
                state["metadata_commit"] = head
                self._record(state, "metadata_committed")
            if state["stage"] == "created" and Path(state["worktree"]).exists():
                require(git(state["worktree"], "rev-parse", "HEAD") == state["base_commit"], "Unrecognized recovered worktree")
                clean(state["worktree"])
                self._record(state, "worktree_created")
            return self._continue(state)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("bootstrap", "status", "inspect", "dry-run", "preflight", "single-task", "resume", "batch"))
    parser.add_argument("--root", default=".")
    parser.add_argument("--task")
    parser.add_argument("--run-id")
    args = parser.parse_args(argv)
    try:
        runner = Runner(args.root)
        require(runner.root == Path(__file__).resolve().parents[1],
                "Launch the committed harness from its controlling checkout")
        signal.signal(signal.SIGINT, lambda *_: runner.cancel.set())
        signal.signal(signal.SIGTERM, lambda *_: runner.cancel.set())
        if args.mode == "batch":
            raise Halt("Batch disabled; Phase 1 never starts batch execution")
        if args.mode == "preflight":
            result = runner.agent.preflight(runner.root, runner.config)
        elif args.mode in ("bootstrap", "status"):
            result = getattr(runner, args.mode)()
        elif args.mode == "dry-run":
            result = runner.dry_run(args.task)
        elif args.mode == "single-task":
            result = runner.single_task(args.task)
        elif args.mode == "inspect":
            result = runner.inspect(args.run_id)
        else:
            require(args.run_id is not None, "resume requires --run-id")
            result = runner.resume(args.run_id)
        print(json.dumps(result, indent=2))
        return 0
    except Halt as error:
        print(json.dumps({"status": "stopped", "reason": str(error)}))
        return 2
    except (OSError, ValueError, KeyError, TypeError):
        print(json.dumps({"status": "stopped", "reason": "Invalid state or unavailable infrastructure; preserve files for review"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
