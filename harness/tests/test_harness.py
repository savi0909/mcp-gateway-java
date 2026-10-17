from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
import unittest
from unittest.mock import patch
import uuid

from harness.common import (Halt, Ownership, atomic_json, clean, digest, git, load_backlog,
                            read_json, scope_hash)
from harness.orchestrator import Runner, configuration, main
from harness.processes import Codex, ProcessFailure, run_process, safe_environment
from harness.validator import MavenVerifier, ValidationFailure, candidate, reports


SOURCE = Path(__file__).resolve().parents[1]
FIXTURES = SOURCE.parent / "target/harness-tests"


def workspace():
    path = FIXTURES / uuid.uuid4().hex
    path.mkdir(parents=True)
    return path


class SimulatedCrash(BaseException):
    pass


class FakeAgent:
    """Real child processes with controllable edits/review; no model or network."""
    def __init__(self, *, reject=0, change="source.txt", blocked=False, review_edit=False):
        self.calls = 0
        self.reviews = 0
        self.reject = reject
        self.change = change
        self.blocked = blocked
        self.review_edit = review_edit

    def preflight(self, *_):
        return {"fake": True}

    def execute(self, root, model, prompt, schema, timeout, cancel, *, review=False):
        if review:
            self.reviews += 1
            if self.review_edit:
                (Path(root) / "source.txt").write_text("review changed source\n", newline="\n")
            if self.reviews <= self.reject:
                return {"verdict": "request_changes", "findings": [{"severity": "high", "code": "correctness",
                        "file": "source.txt", "line": 1}]}, {"exit_code": 0}
            return {"verdict": "approve", "findings": []}, {"exit_code": 0}
        self.calls += 1
        if self.blocked:
            return {"status": "blocked"}, {"exit_code": 0}
        script = "from pathlib import Path; import sys; Path(sys.argv[1]).write_text(sys.argv[2], encoding='utf-8', newline='\\n')"
        result = run_process([sys.executable, "-B", "-c", script, self.change, f"change {self.calls}\n"],
                             root, timeout, cancel=cancel)
        if result["exit_code"]:
            raise Halt("Fake implementation process failed")
        return {"status": "completed"}, {"exit_code": 0, "duration_seconds": result["duration_seconds"]}


class FakeVerifier:
    def __init__(self, failures=0, infrastructure=False, mutate=False):
        self.calls = 0
        self.failures = failures
        self.infrastructure = infrastructure
        self.mutate = mutate

    def verify(self, root, run_dir, timeout, cancel):
        self.calls += 1
        result = run_process([sys.executable, "-B", "-c", "print('independent verification')"], root, timeout,
                             cancel=cancel)
        if self.infrastructure:
            raise Halt("Verification infrastructure failed")
        if self.calls <= self.failures:
            raise ValidationFailure("Required test failed")
        if self.mutate:
            (Path(root) / "source.txt").write_text("verifier modified candidate\n", newline="\n")
        return {"command_id": "fixture-verify", "exit_code": result["exit_code"], "reports": {"fixture": 1}}


def repository():
    root = workspace()
    git(root, "init", "-b", "main")
    git(root, "config", "user.email", "harness-fixture@example.invalid")
    git(root, "config", "user.name", "Harness fixture")
    for relative in ("tasks/schema.json", "harness/config.toml", "harness/implementation-report.schema.json",
                     "harness/review-report.schema.json", "harness/prompts/implement.md", "harness/prompts/review.md",
                     "scripts/verify.ps1", "scripts/verify.sh"):
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SOURCE.parent / relative, destination)
    config = root / "harness/config.toml"
    config.write_text(config.read_text().replace("publish = true", "publish = false"))
    (root / "source.txt").write_text("original\n")
    (root / "spec.md").write_text("Approved fixture contract\n")
    (root / ".gitignore").write_text("/target/\n")
    task = copy.deepcopy(read_json(SOURCE.parent / "tasks/backlog.json")["tasks"][0])
    task.update(id="TEST-01", title="Fixture change", spec_refs=["spec.md"], scope=["source.txt"],
                acceptance_criteria=["Apply fixture change and verify independently"],
                base_commit="0" * 40, requires_human_approval=True)
    data = {"schema_version": 1, "pilot_authorization": None, "tasks": [task]}
    atomic_json(root / "tasks/backlog.json", data)
    git(root, "add", ".")
    git(root, "commit", "-m", "Fixture baseline")
    task.update(base_commit=git(root, "rev-parse", "HEAD"), status="ready", requires_human_approval=False)
    task["approval"] = {"reference": "spec.md", "scope_sha256": scope_hash(task), "approved_by": "owner"}
    data["pilot_authorization"] = "spec.md"
    atomic_json(root / "tasks/backlog.json", data)
    git(root, "add", "tasks/backlog.json")
    git(root, "commit", "-m", "Approve fixture task")
    return root


class BacklogTests(unittest.TestCase):
    def setUp(self):
        self.root = repository()
        self.path = self.root / "tasks/backlog.json"
        self.data = read_json(self.path)

    def invalid(self, data, message):
        atomic_json(self.path, data)
        with self.assertRaisesRegex(Halt, message):
            load_backlog(self.root)

    def test_approved_scope_must_match_exact_record(self):
        self.assertEqual(load_backlog(self.root)["tasks"][0]["status"], "ready")
        self.data["tasks"][0]["acceptance_criteria"].append("Unapproved new requirement")
        self.invalid(self.data, "stale scope")

    def test_unknown_fields_and_duplicate_keys_are_rejected(self):
        self.data["untrusted_command"] = "arbitrary command"
        self.invalid(self.data, "unknown field")
        self.path.write_text('{"schema_version":1,"schema_version":2}')
        with self.assertRaisesRegex(Halt, "Duplicate JSON key"):
            read_json(self.path)

    def test_duplicate_ids_missing_dependencies_and_cycles_rejected(self):
        self.data["tasks"].append(copy.deepcopy(self.data["tasks"][0]))
        self.invalid(self.data, "Duplicate task")
        self.data["tasks"].pop()
        task = self.data["tasks"][0]
        task.update(status="pending", dependencies=["MISSING-01"])
        self.invalid(self.data, "Missing dependency")
        second = copy.deepcopy(task)
        second.update(id="TEST-02", dependencies=["TEST-01"])
        task["dependencies"] = ["TEST-02"]
        self.data["tasks"].append(second)
        self.invalid(self.data, "cycle")

    def test_paths_and_protected_files_cannot_be_approved(self):
        task = self.data["tasks"][0]
        task["status"] = "pending"
        for path in ("../outside", "C:/outside", "source*.txt", "harness/config.toml", "pom.xml"):
            with self.subTest(path=path):
                task["scope"] = [path]
                self.invalid(self.data, "path|Protected")

    def test_completion_requires_commit_and_evidence(self):
        self.data["tasks"][0]["status"] = "completed"
        self.invalid(self.data, "Completion lacks")

    def test_dry_run_has_no_files_or_model_side_effects(self):
        before = git(self.root, "status", "--porcelain=v1")
        agent = FakeAgent()
        result = Runner(self.root, agent=agent).dry_run()
        self.assertEqual(result["selected_task"], "TEST-01")
        self.assertEqual(result["model_calls"], 0)
        self.assertEqual(agent.calls, 0)
        self.assertEqual(git(self.root, "status", "--porcelain=v1"), before)
        self.assertFalse((self.root / ".git/engineering-harness").exists())

    def test_no_pilot_approval_or_not_ready_prevents_execution(self):
        self.data["pilot_authorization"] = None
        atomic_json(self.path, self.data)
        with self.assertRaisesRegex(Halt, "pilot approval"):
            Runner(self.root).dry_run()
        self.data["tasks"][0]["status"] = "pending"
        atomic_json(self.path, self.data)
        self.assertIsNone(Runner(self.root).dry_run()["selected_task"])

    def test_source_change_after_approval_requires_reapproval(self):
        (self.root / "source.txt").write_text("changed source\n")
        git(self.root, "add", "source.txt")
        git(self.root, "commit", "-m", "Unapproved new source baseline")
        with self.assertRaisesRegex(Halt, "Source changed"):
            Runner(self.root).dry_run()

    def test_noncompleted_dependency_is_not_selected(self):
        task = self.data["tasks"][0]
        second = copy.deepcopy(task)
        second.update(id="TEST-02", status="pending")
        task["dependencies"] = ["TEST-02"]
        task["approval"]["scope_sha256"] = scope_hash(task)
        self.data["tasks"].append(second)
        atomic_json(self.path, self.data)
        self.assertIsNone(Runner(self.root).eligible(load_backlog(self.root)))

    def test_forged_completed_dependency_without_evidence_is_rejected(self):
        data = self.data
        task = data["tasks"][0]
        dependency = copy.deepcopy(task)
        dependency.update(id="TEST-02", status="completed", content_commit=git(self.root, "rev-parse", "HEAD"),
                          evidence="a" * 32)
        dependency["approval"]["scope_sha256"] = scope_hash(dependency)
        task["dependencies"] = ["TEST-02"]
        task["approval"]["scope_sha256"] = scope_hash(task)
        data["tasks"].append(dependency)
        atomic_json(self.path, data)
        with self.assertRaisesRegex(Halt, "Cannot read valid JSON"):
            Runner(self.root).eligible(load_backlog(self.root))

    def test_verified_dependency_must_be_in_approved_source_ancestry(self):
        data = self.data
        base = git(self.root, "rev-parse", "HEAD")
        # Create a separate commit object without changing the working tree or main.
        tree = git(self.root, "rev-parse", "HEAD^{tree}")
        detached = git(self.root, "commit-tree", tree, "-p", base, "-m", "Unintegrated dependency")
        dependency = copy.deepcopy(data["tasks"][0])
        dependency.update(id="TEST-02", status="completed", content_commit=detached, evidence="b" * 32)
        dependency["approval"]["scope_sha256"] = scope_hash(dependency)
        task = data["tasks"][0]
        task["dependencies"] = ["TEST-02"]
        task["approval"]["scope_sha256"] = scope_hash(task)
        data["tasks"].append(dependency)
        atomic_json(self.path, data)
        runner = Runner(self.root)
        atomic_json(runner.control / "runs" / ("b" * 32) / "state.json",
                    {"task_id": "TEST-02", "content_commit": detached, "verification": {"exit_code": 0},
                     "review": {"approved": True}, "stage": "published"})
        with self.assertRaisesRegex(Halt, "not been integrated"):
            runner.eligible(load_backlog(self.root))

    def test_configuration_cannot_enable_batch_or_increase_limits(self):
        path = self.root / "harness/config.toml"
        original = path.read_text()
        for text in (original.replace("batch_enabled = false", "batch_enabled = true"),
                     original.replace("max_repairs = 3", "max_repairs = 4")):
            path.write_text(text)
            with self.assertRaises(Halt):
                configuration(self.root)


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.root = repository()

    def runner(self, agent=None, verifier=None, fault=None):
        return Runner(self.root, agent=agent or FakeAgent(), verifier=verifier or FakeVerifier(), fault=fault)

    def state(self):
        paths = list((self.root / ".git/engineering-harness/runs").glob("*/state.json"))
        self.assertEqual(len(paths), 1)
        return read_json(paths[0])

    def test_single_task_verifies_reviews_commits_and_retains_worktree(self):
        agent, verifier = FakeAgent(), FakeVerifier()
        result = self.runner(agent, verifier).single_task()
        self.assertEqual((agent.calls, agent.reviews, verifier.calls), (1, 1, 1))
        self.assertEqual(result["status"], "completed")
        task = load_backlog(self.root)["tasks"][0]
        self.assertEqual(task["content_commit"], result["content_commit"])
        self.assertEqual((self.root / "source.txt").read_text(), "original\n")
        self.assertEqual((Path(result["worktree_retained"]) / "source.txt").read_text(), "change 1\n")
        clean(self.root)
        self.assertEqual(self.runner(agent, verifier).resume(result["run_id"])["status"], "completed")
        self.assertEqual(agent.calls, 1)
        inspected = self.runner().inspect(result["run_id"])
        self.assertEqual(inspected["content_commit"], result["content_commit"])
        self.assertNotIn("backlog", inspected)

    def test_dirty_primary_and_untracked_changes_are_preserved(self):
        (self.root / "unrelated.txt").write_text("preserve me")
        with self.assertRaisesRegex(Halt, "dirty Git"):
            self.runner().single_task()
        self.assertEqual((self.root / "unrelated.txt").read_text(), "preserve me")

    def test_agent_cannot_change_scope_or_report_only_success(self):
        with self.assertRaisesRegex(Halt, "exceeds approved"):
            self.runner(FakeAgent(change="unapproved.txt")).single_task()
        self.assertEqual(self.state()["stage"], "needs_review")
        self.assertIsNone(load_backlog(self.root)["tasks"][0]["content_commit"])

    def test_agent_blocked_does_not_run_verifier(self):
        verifier = FakeVerifier()
        with self.assertRaisesRegex(Halt, "reported blocked"):
            self.runner(FakeAgent(blocked=True), verifier).single_task()
        self.assertEqual(verifier.calls, 0)

    def test_three_repairs_max_and_counter_survives_resume(self):
        agent = FakeAgent(reject=100)
        with self.assertRaisesRegex(Halt, "Repair limit"):
            self.runner(agent).single_task()
        self.assertEqual(agent.calls, 4)
        state = self.state()
        self.assertEqual(state["attempts"], 4)
        with self.assertRaisesRegex(Halt, "manual review"):
            self.runner(agent).resume(state["run_id"])
        self.assertEqual(agent.calls, 4)

    def test_failed_tests_receive_focused_repair_then_full_verification(self):
        agent, verifier = FakeAgent(), FakeVerifier(failures=1)
        result = self.runner(agent, verifier).single_task()
        self.assertEqual(result["status"], "completed")
        self.assertEqual((agent.calls, verifier.calls, agent.reviews), (2, 2, 1))

    def test_infrastructure_failure_stops_without_source_repairs(self):
        agent = FakeAgent()
        with self.assertRaisesRegex(Halt, "infrastructure"):
            self.runner(agent, FakeVerifier(infrastructure=True)).single_task()
        self.assertEqual(agent.calls, 1)

    def test_failed_agent_exit_is_recorded_without_raw_output(self):
        class FailingAgent(FakeAgent):
            def execute(self, *args, **kwargs):
                raise ProcessFailure("Codex process failed", {"exit_code": 17, "duration_seconds": 0.1})
        with self.assertRaisesRegex(Halt, "Codex process failed"):
            self.runner(FailingAgent()).single_task()
        self.assertEqual(self.state()["events"][-1]["exit_code"], 17)

    def test_reviewer_mutation_and_verifier_mutation_are_rejected(self):
        with self.assertRaisesRegex(Halt, "Review modified"):
            self.runner(FakeAgent(review_edit=True)).single_task()
        self.root = repository()
        with self.assertRaisesRegex(Halt, "Verification modified"):
            self.runner(verifier=FakeVerifier(mutate=True)).single_task()

    def test_content_commit_crash_is_reconciled_without_agent_replay(self):
        agent = FakeAgent()
        def crash(point):
            if point == "content_commit":
                raise SimulatedCrash()
        with self.assertRaises(SimulatedCrash):
            self.runner(agent, fault=crash).single_task()
        state = self.state()
        self.assertEqual(state["stage"], "prepared")
        result = self.runner(agent).resume(state["run_id"])
        self.assertEqual(result["status"], "completed")
        self.assertEqual(agent.calls, 1)

    def test_metadata_commit_crash_is_reconciled_without_duplicate_commits(self):
        agent = FakeAgent()
        def crash(point):
            if point == "metadata_commit":
                raise SimulatedCrash()
        with self.assertRaises(SimulatedCrash):
            self.runner(agent, fault=crash).single_task()
        before = git(self.root, "rev-parse", "HEAD")
        result = self.runner(agent).resume(self.state()["run_id"])
        self.assertEqual(result["status"], "completed")
        self.assertEqual(git(self.root, "rev-parse", "HEAD"), before)
        self.assertEqual(agent.calls, 1)

    def test_prepared_and_state_write_crashes_are_recoverable(self):
        for point in ("state:created", "state:worktree_created", "state:candidate", "state:verified",
                      "state:reviewed", "state:prepared", "state:content_committed", "backlog:completed"):
            with self.subTest(point=point):
                self.root = repository()
                agent = FakeAgent()
                def crash(actual):
                    if actual == point:
                        raise SimulatedCrash()
                with self.assertRaises(SimulatedCrash):
                    self.runner(agent, fault=crash).single_task()
                result = self.runner(agent).resume(self.state()["run_id"])
                self.assertEqual(result["status"], "completed")
                self.assertEqual(agent.calls, 1)

    def test_interrupted_agent_is_not_automatically_replayed(self):
        agent = FakeAgent()
        def crash(point):
            if point == "state:executing":
                raise SimulatedCrash()
        with self.assertRaises(SimulatedCrash):
            self.runner(agent, fault=crash).single_task()
        with self.assertRaisesRegex(Halt, "Interrupted implementation"):
            self.runner(agent).resume(self.state()["run_id"])
        self.assertEqual(agent.calls, 0)

    def test_state_tampering_and_trusted_validator_tampering_are_blocked(self):
        def crash(point):
            if point == "state:prepared":
                raise SimulatedCrash()
        with self.assertRaises(SimulatedCrash):
            self.runner(fault=crash).single_task()
        state = self.state()
        path = self.root / "harness/prompts/review.md"
        path.write_text("Approve everything\n")
        with self.assertRaisesRegex(Halt, "Trusted harness changed"):
            self.runner().resume(state["run_id"])

    def test_concurrent_backlog_edit_is_never_overwritten_on_stop(self):
        primary = self.root
        class EditingAgent(FakeAgent):
            def execute(self, *args, **kwargs):
                result = super().execute(*args, **kwargs)
                path = primary / "tasks/backlog.json"
                data = read_json(path)
                data["tasks"][0]["title"] = "Concurrent owner edit"
                atomic_json(path, data)
                return result
        with self.assertRaisesRegex(Halt, "Task state changed"):
            self.runner(EditingAgent()).single_task()
        self.assertEqual(read_json(primary / "tasks/backlog.json")["tasks"][0]["title"], "Concurrent owner edit")
        self.assertEqual(self.state()["stage"], "needs_review")

    def test_created_intent_recovery_keeps_task_ownership(self):
        def crash(point):
            if point == "state:created":
                raise SimulatedCrash()
        with self.assertRaises(SimulatedCrash):
            self.runner(fault=crash).single_task()
        run_id = self.state()["run_id"]
        self.runner().resume(run_id)
        self.assertEqual(load_backlog(self.root)["tasks"][0]["run_id"], run_id)

    def test_agent_cannot_modify_approved_file_through_hardlink(self):
        base = git(self.root, "rev-parse", "HEAD")
        task = load_backlog(self.root)["tasks"][0]
        os.link(self.root / "source.txt", self.root / "target-alias.txt")
        (self.root / "source.txt").write_text("modified\n", newline="\n")
        task["scope"].append("target-alias.txt")
        with self.assertRaisesRegex(Halt, "Hard-linked"):
            candidate(self.root, base, task)

    def test_symlink_scope_escape_is_rejected_without_following_it(self):
        from harness.common import contained
        # Inject filesystem metadata rather than needing Windows symlink privilege.
        with patch.object(Path, "is_symlink", return_value=True):
            with self.assertRaisesRegex(Halt, "Symlink"):
                contained(self.root, "source.txt")

    def test_cancel_before_execution_preserves_run_and_stops(self):
        runner = self.runner()
        runner.cancel.set()
        with self.assertRaisesRegex(Halt, "Cancelled"):
            runner.single_task()
        self.assertEqual(self.state()["stage"], "needs_review")

    def test_timeout_budget_is_not_reset_by_resume(self):
        def crash(point):
            if point == "state:candidate":
                raise SimulatedCrash()
        with self.assertRaises(SimulatedCrash):
            self.runner(fault=crash).single_task()
        state = self.state()
        state["deadline"] = time.time() - 1
        atomic_json(self.root / ".git/engineering-harness/runs" / state["run_id"] / "state.json", state)
        with self.assertRaisesRegex(Halt, "budget exhausted"):
            self.runner().resume(state["run_id"])

    def test_push_failure_keeps_completed_work_and_resume_only_publishes(self):
        path = self.root / "harness/config.toml"
        path.write_text(path.read_text().replace("publish = false", "publish = true"))
        # Review/config approval precedes the new source base and task reapproval.
        git(self.root, "add", "harness/config.toml")
        git(self.root, "commit", "-m", "Enable fixture publication")
        data = read_json(self.root / "tasks/backlog.json")
        task = data["tasks"][0]
        task["base_commit"] = git(self.root, "rev-parse", "HEAD")
        task["approval"]["scope_sha256"] = scope_hash(task)
        atomic_json(self.root / "tasks/backlog.json", data)
        git(self.root, "add", "tasks/backlog.json")
        git(self.root, "commit", "-m", "Reapprove fixture")
        agent = FakeAgent()
        with self.assertRaisesRegex(Halt, "Git operation failed: push"):
            self.runner(agent).single_task()
        self.assertEqual(load_backlog(self.root)["tasks"][0]["status"], "completed")
        state = self.state()
        remote = workspace()
        git(remote, "init", "--bare")
        git(self.root, "remote", "add", "origin", str(remote))
        result = self.runner(agent).resume(state["run_id"])
        self.assertTrue(result["published"])
        self.assertEqual(agent.calls, 1)

    def test_no_candidate_or_deleted_file_cannot_commit(self):
        base = git(self.root, "rev-parse", "HEAD")
        task = load_backlog(self.root)["tasks"][0]
        with self.assertRaisesRegex(Halt, "no candidate"):
            candidate(self.root, base, task)
        # Synthetic Git output tests the denial without deleting any real file.
        real_git = git
        def deleted(root, *args, **kwargs):
            if args[:2] == ("diff", "--name-status"):
                return "D\0source.txt\0"
            return real_git(root, *args, **kwargs)
        with patch("harness.validator.git", side_effect=deleted):
            with self.assertRaisesRegex(Halt, "Deletion"):
                candidate(self.root, base, task)


class ProcessTests(unittest.TestCase):
    def test_environment_excludes_secrets_and_build_overrides(self):
        with patch.dict(os.environ, {"OPENAI_API_KEY": "secret-canary", "MAVEN_ARGS": "-DskipTests",
                                     "GIT_CONFIG_COUNT": "1", "JAVA_TOOL_OPTIONS": "unsafe"}):
            environment = safe_environment()
            self.assertTrue(all(key not in environment for key in
                                ("OPENAI_API_KEY", "MAVEN_ARGS", "GIT_CONFIG_COUNT", "JAVA_TOOL_OPTIONS")))

    def test_argument_spaces_and_metacharacters_are_literal(self):
        value = 'space & dollar $() quote " backtick `'
        result = run_process([sys.executable, "-B", "-c", "import sys; print(sys.argv[1])", value], workspace(), 10)
        self.assertEqual(result["output"].strip(), value)

    def test_process_timeout_cancellation_and_output_limit(self):
        root = workspace()
        with self.assertRaisesRegex(ProcessFailure, "timeout") as timeout:
            run_process([sys.executable, "-B", "-c", "import time; time.sleep(20)"], root, .2)
        self.assertIsInstance(timeout.exception.metadata["exit_code"], int)
        cancel = threading.Event()
        cancel.set()
        with self.assertRaisesRegex(Halt, "Cancelled"):
            run_process([sys.executable, "-B", "-c", "import time; time.sleep(20)"], root, 10, cancel=cancel)
        with self.assertRaisesRegex(Halt, "output limit"):
            run_process([sys.executable, "-B", "-c", "print('x'*100000)"], root, 10, max_output=100)

    def test_timeout_kills_child_descendants(self):
        root = workspace()
        child = root / "child.py"
        marker = root / "escaped.txt"
        child.write_text("import time\nfrom pathlib import Path\ntime.sleep(2)\nPath('escaped.txt').write_text('escaped')\n")
        parent = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-B','child.py']); time.sleep(20)"
        with self.assertRaisesRegex(Halt, "timeout"):
            run_process([sys.executable, "-B", "-c", parent], root, .5)
        time.sleep(2)
        self.assertFalse(marker.exists())

    def test_successful_parent_exit_also_terminates_descendants(self):
        root = workspace()
        (root / "child.py").write_text("import time\nfrom pathlib import Path\ntime.sleep(2)\nPath('escaped.txt').write_text('escaped')\n")
        run_process([sys.executable, "-B", "-c", "import subprocess,sys; subprocess.Popen([sys.executable,'-B','child.py'])"], root, 10)
        time.sleep(2)
        self.assertFalse((root / "escaped.txt").exists())

    def test_repository_lock_rejects_second_owner_and_releases_on_exit(self):
        path = workspace() / "runner.lock"
        with Ownership(path):
            with self.assertRaisesRegex(Halt, "Another runner"):
                with Ownership(path):
                    self.fail("Second owner acquired lock")
        with Ownership(path):
            self.assertTrue(path.exists())

    def test_lock_is_released_when_holder_process_dies(self):
        root = workspace()
        path = root / "lock"
        script = "import sys,time; sys.path.insert(0,sys.argv[1]); from harness.common import Ownership; " \
                 "lock=Ownership(sys.argv[2]); lock.__enter__(); print('locked',flush=True); time.sleep(20)"
        with self.assertRaisesRegex(Halt, "timeout"):
            run_process([sys.executable, "-B", "-c", script, str(SOURCE.parent), str(path)], root, .5)
        with Ownership(path):
            self.assertTrue(path.exists())

    def test_codex_jsonl_adapter_uses_safe_flags_and_drops_raw_output(self):
        root = workspace()
        fake = root / "fake.py"
        fake.write_text("import json,sys\nfrom pathlib import Path\n"
                        "Path('argv.json').write_text(json.dumps(sys.argv[1:]))\n"
                        "assert 'contract' in sys.stdin.read()\n"
                        "print(json.dumps({'type':'item.completed','item':{'type':'command_execution','output':'secret-canary'}}))\n"
                        "print(json.dumps({'type':'item.completed','item':{'type':'agent_message','text':'{\\\"status\\\":\\\"completed\\\"}'}}))\n"
                        "print(json.dumps({'type':'turn.completed'}))\n")
        with patch("harness.processes.codex_executable", return_value=[sys.executable, "-B", str(fake)]):
            report, metadata = Codex().execute(root, {"name": "gpt-6-astra", "reasoning_effort": "high"},
                "contract", SOURCE / "implementation-report.schema.json", 10, threading.Event())
        self.assertEqual(report, {"status": "completed"})
        self.assertNotIn("secret-canary", json.dumps(metadata))
        args = read_json(root / "argv.json")
        self.assertIn("--ignore-user-config", args)
        self.assertIn("workspace-write", args)
        self.assertNotIn("danger-full-access", args)
        self.assertEqual(args[:2], ["-a", "never"])

    def test_malformed_or_failed_codex_events_never_count_as_success(self):
        for text in ("not json", '{"type":"turn.failed"}', '{"type":"turn.completed"}'):
            with self.subTest(text=text):
                root = workspace()
                fake = root / "fake.py"
                fake.write_text("import sys; sys.stdin.read(); print(" + repr(text) + ")")
                with patch("harness.processes.codex_executable", return_value=[sys.executable, "-B", str(fake)]):
                    with self.assertRaises(Halt):
                        Codex().execute(root, {"name": "gpt-6-astra", "reasoning_effort": "high"}, "contract",
                            SOURCE / "implementation-report.schema.json", 10, threading.Event())


class ReportTests(unittest.TestCase):
    def setUp(self):
        self.root = workspace()
        self.baseline = {"surefire": {"UnitTest": 1}, "failsafe": {"ProtocolIT": 1}}
        self.started = time.time() - 1
        (self.root / "app.jar").write_bytes(b"fixture")
        for kind, name in (("surefire", "UnitTest"), ("failsafe", "ProtocolIT")):
            directory = self.root / (kind + "-reports")
            directory.mkdir()
            (directory / ("TEST-" + name + ".xml")).write_text(
                '<testsuite name="' + name + '" tests="1" failures="0" errors="0" skipped="0"><testcase name="required"/></testsuite>')

    def test_complete_fresh_suites_are_required(self):
        result = reports(self.root, self.baseline, self.started)
        self.assertEqual(result["surefire"]["tests"], 1)
        self.baseline["surefire"]["MissingTest"] = 1
        with self.assertRaisesRegex(Halt, "coverage disappeared"):
            reports(self.root, self.baseline, self.started)

    def test_stale_reports_are_rejected(self):
        path = self.root / "surefire-reports/TEST-UnitTest.xml"
        os.utime(path, (1, 1))
        with self.assertRaisesRegex(Halt, "Stale"):
            reports(self.root, self.baseline, self.started)

    def test_failures_skips_and_fake_counts_are_rejected(self):
        path = self.root / "surefire-reports/TEST-UnitTest.xml"
        original = path.read_text()
        for xml in (original.replace('skipped="0"', 'skipped="1"'),
                    original.replace('failures="0"', 'failures="1"'),
                    original.replace('tests="1"', 'tests="99"'),
                    original.replace('<testcase name="required"/>', '<testcase name="required"><failure/></testcase>')):
            with self.subTest(xml=xml):
                path.write_text(xml)
                with self.assertRaises(Halt):
                    reports(self.root, self.baseline, self.started)

    def test_malformed_or_entity_xml_is_rejected(self):
        path = self.root / "surefire-reports/TEST-UnitTest.xml"
        for text in ("<broken", '<!DOCTYPE x [<!ENTITY a "bad">]><testsuite/>'):
            path.write_text(text)
            with self.assertRaises(Halt):
                reports(self.root, self.baseline, self.started)


class WrapperTests(unittest.TestCase):
    def test_powershell_wrapper_propagates_exit_and_refuses_old_output(self):
        if os.name != "nt":
            # The POSIX wrapper is the required executed entry point on POSIX hosts.
            return
        root = workspace()
        (root / "mvnw.cmd").write_text("@echo off\r\n> invocation.txt echo %*\r\nexit /b 17\r\n", newline="")
        command = ["pwsh", "-NoProfile", "-File", str(SOURCE.parent / "scripts/verify.ps1"),
                   "-Repository", str(root), "-RunId", "verify-fixture"]
        result = run_process(command, root, 15)
        self.assertEqual(result["exit_code"], 17)
        args = (root / "invocation.txt").read_text()
        self.assertIn("-B -ntp -Dgateway.build-directory=target/harness/verify-fixture verify", args)
        self.assertNotIn("clean", args)
        self.assertNotIn("skipTests", args)
        (root / "target/harness/verify-fixture").mkdir(parents=True)
        before = (root / "invocation.txt").stat().st_mtime_ns
        self.assertNotEqual(run_process(command, root, 15)["exit_code"], 0)
        self.assertEqual((root / "invocation.txt").stat().st_mtime_ns, before)

    def test_posix_wrapper_propagates_exit_and_rejects_unsafe_run_id(self):
        shell = shutil.which("sh")
        if not shell:
            self.skipTest("POSIX shell unavailable")
        root = workspace()
        (root / "mvnw").write_text('#!/bin/sh\nprintf "%s\\n" "$@" > invocation.txt\nexit 17\n', newline="\n")
        command = [shell, (SOURCE.parent / "scripts/verify.sh").as_posix(), root.as_posix(), "verify-fixture"]
        self.assertEqual(run_process(command, root, 15)["exit_code"], 17)
        args = (root / "invocation.txt").read_text().splitlines()
        self.assertEqual(args, ["-B", "-ntp", "-Dgateway.build-directory=target/harness/verify-fixture", "verify"])
        for run_id in ("../escape", "verify-", "verify-../escape"):
            self.assertNotEqual(run_process(command[:-1] + [run_id], root, 15)["exit_code"], 0)
        (root / "target/harness/verify-fixture").mkdir(parents=True)
        self.assertNotEqual(run_process(command, root, 15)["exit_code"], 0)


if __name__ == "__main__":
    unittest.main()
