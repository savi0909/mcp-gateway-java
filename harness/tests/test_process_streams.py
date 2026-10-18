"""Real local children exercise stream boundaries without model/network access."""
from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import json
import sys
import threading
import unittest
from unittest.mock import patch

from harness.common import read_json
from harness.orchestrator import Runner
from harness.processes import Codex, ProcessFailure, run_process
from harness.tests.test_harness import SOURCE, FakeVerifier, repository, workspace


MESSAGE = {"type": "item.completed", "item": {
    "type": "agent_message", "text": '{"status":"completed"}'}}
COMPLETED = {"type": "turn.completed", "usage": {
    "input_tokens": 10, "cached_input_tokens": 0, "output_tokens": 5}}
VALID = [json.dumps(event) for event in (
    {"type": "thread.started", "thread_id": "fixture-thread"},
    {"type": "turn.started"}, MESSAGE, COMPLETED)]
CANARY = "private-diagnostic-canary"


class ProcessStreamTests(unittest.TestCase):
    def execute(self, lines=VALID, *, diagnostic=CANARY, exit_code=0, review=False):
        root = workspace()
        child = root / "child.py"
        child.write_text(
            "import sys\nfrom pathlib import Path\n"
            "sys.stdin.read()\n"
            "with Path('launches.txt').open('a') as stream: stream.write('launch\\n')\n"
            f"sys.stderr.write({diagnostic!r})\nsys.stderr.flush()\n"
            f"sys.stdout.write({chr(10).join(lines)!r} + '\\n')\n"
            f"sys.exit({exit_code})\n", encoding="utf-8", newline="\n")
        console = io.StringIO()
        try:
            with redirect_stdout(console), redirect_stderr(console), patch(
                    "harness.processes.codex_executable",
                    return_value=[sys.executable, "-B", str(child)]):
                return Codex().execute(root, {"name": "gpt-6.1-sol", "reasoning_effort": "medium"},
                    "fixture contract", SOURCE / (
                        "review-report.schema.json" if review else "implementation-report.schema.json"),
                    10, threading.Event(), review=review)
        finally:
            self.assertEqual((root / "launches.txt").read_text(), "launch\n")
            self.assertNotIn(CANARY, console.getvalue())

    def test_valid_jsonl_with_diagnostic_stderr_returns_only_safe_metadata(self):
        report, metadata = self.execute()
        self.assertEqual(report, {"status": "completed"})
        self.assertEqual(metadata["event_count"], 4)
        self.assertEqual(metadata["stderr_bytes"], len(CANARY))
        self.assertNotIn(CANARY, json.dumps(metadata))
        self.assertEqual(set(metadata), {
            "exit_code", "event_count", "duration_seconds", "stdout_bytes", "stderr_bytes"})

    def test_review_jsonl_with_diagnostics_uses_read_only_sandbox(self):
        root = workspace()
        child = root / "review.py"
        child.write_text(
            "import json,sys\nfrom pathlib import Path\n"
            "sys.stdin.read()\nPath('argv.json').write_text(json.dumps(sys.argv[1:]))\n"
            "sys.stderr.buffer.write(b'diagnostic\\n')\nsys.stderr.flush()\n"
            "print(json.dumps({'type':'item.completed','item':{'type':'agent_message',"
            "'text':'{\"verdict\":\"approve\",\"findings\":[]}'}}))\n"
            "print(json.dumps({'type':'turn.completed'}))\n", encoding="utf-8")
        with patch("harness.processes.codex_executable", return_value=[sys.executable, "-B", str(child)]):
            report, metadata = Codex().execute(root, {"name": "gpt-6-astra", "reasoning_effort": "high"},
                "review", SOURCE / "review-report.schema.json", 10, threading.Event(), review=True)
        self.assertEqual(report, {"verdict": "approve", "findings": []})
        args = json.loads((root / "argv.json").read_text())
        self.assertEqual(args[args.index("--sandbox") + 1], "read-only")
        self.assertEqual(metadata["stderr_bytes"], 11)

    def test_malformed_stdout_is_rejected_even_with_successful_completion(self):
        for line in (CANARY, "", "[]", '{"item":{}}',
                     '{"type":"item.completed","item":null}',
                     '{"type":"item.completed","item":{"type":"agent_message","text":42}}'):
            with self.subTest(line=line), self.assertRaises(ProcessFailure) as raised:
                self.execute([line, *VALID])
            self.assertNotIn(CANARY, str(raised.exception))
            self.assertNotIn(CANARY, json.dumps(raised.exception.metadata))

    def test_explicit_failure_is_rejected_even_after_completion(self):
        for kind in ("error", "turn.failed"):
            with self.subTest(kind=kind), self.assertRaises(ProcessFailure) as raised:
                self.execute([*VALID, json.dumps({"type": kind, "message": CANARY})])
            self.assertNotIn(CANARY, str(raised.exception))
            self.assertEqual(raised.exception.metadata["stderr_bytes"], len(CANARY))

    def test_missing_completion_or_message_is_rejected(self):
        for lines in (VALID[:-1], [json.dumps(COMPLETED)], []):
            with self.subTest(lines=lines), self.assertRaises(ProcessFailure):
                self.execute(lines)

    def test_protocol_events_on_stderr_cannot_supply_missing_stdout(self):
        with self.assertRaises(ProcessFailure):
            self.execute([], diagnostic="\n".join(VALID))

    def test_invalid_structured_reports_are_rejected_without_leaking_values(self):
        for report in (CANARY, '[]', '{}', '{"status":42}',
                       '{"status":"' + CANARY + '"}', '{"status":"completed","extra":true}'):
            message = {"type": "item.completed", "item": {"type": "agent_message", "text": report}}
            with self.subTest(report=report), self.assertRaises(ProcessFailure) as raised:
                self.execute([json.dumps(message), json.dumps(COMPLETED)])
            self.assertNotIn(CANARY, str(raised.exception))

    def test_nonzero_exit_is_never_retried_or_accepted(self):
        with self.assertRaisesRegex(ProcessFailure, "no automatic infrastructure retry") as raised:
            self.execute(exit_code=17)
        self.assertEqual(raised.exception.metadata["exit_code"], 17)
        self.assertEqual(raised.exception.metadata["stderr_bytes"], len(CANARY))
        self.assertNotIn(CANARY, str(raised.exception) + json.dumps(raised.exception.metadata))

    def test_stdout_and_stderr_have_one_shared_output_budget(self):
        for stdout_size, stderr_size in ((65, 0), (0, 65), (40, 40)):
            with self.subTest(stdout=stdout_size, stderr=stderr_size):
                script = (f"import sys; sys.stdout.write('o'*{stdout_size}); "
                          f"sys.stderr.write('e'*{stderr_size})")
                with self.assertRaisesRegex(ProcessFailure, "output limit") as raised:
                    run_process([sys.executable, "-B", "-c", script], workspace(), 10, max_output=64)
                self.assertGreater(raised.exception.metadata["stdout_bytes"] +
                                   raised.exception.metadata["stderr_bytes"], 64)

    def test_simultaneous_large_streams_drain_without_deadlock(self):
        script = ("import sys,threading; "
                  "t=threading.Thread(target=lambda: sys.stderr.write('e'*100000)); "
                  "t.start(); sys.stdout.write('o'*100000); t.join()")
        result = run_process([sys.executable, "-B", "-c", script], workspace(), 10, max_output=200000)
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(result["output"], "o" * 100000)
        self.assertEqual(result["stdout_bytes"], 100000)
        self.assertEqual(result["stderr_bytes"], 100000)
        self.assertNotIn("stderr", result)

    def test_timeout_drains_stderr_and_records_only_safe_counts(self):
        script = (f"import sys,time; sys.stderr.write({CANARY!r}); sys.stderr.flush(); "
                  "time.sleep(20)")
        with self.assertRaisesRegex(ProcessFailure, "timeout") as raised:
            run_process([sys.executable, "-B", "-c", script], workspace(), .5)
        self.assertEqual(raised.exception.metadata["stderr_bytes"], len(CANARY))
        self.assertNotIn(CANARY, str(raised.exception) + json.dumps(raised.exception.metadata))

    def test_cancellation_stops_children_writing_both_streams(self):
        cancel = threading.Event()
        timer = threading.Timer(.5, cancel.set)
        timer.start()
        script = ("import sys,time\nwhile True:\n"
                  f" sys.stdout.write({CANARY!r}); sys.stdout.flush()\n"
                  f" sys.stderr.write({CANARY!r}); sys.stderr.flush(); time.sleep(.01)\n")
        try:
            with self.assertRaisesRegex(ProcessFailure, "Cancelled") as raised:
                run_process([sys.executable, "-B", "-c", script], workspace(), 10, cancel=cancel)
            self.assertGreater(raised.exception.metadata["stdout_bytes"], 0)
            self.assertGreater(raised.exception.metadata["stderr_bytes"], 0)
            self.assertNotIn(CANARY, str(raised.exception) + json.dumps(raised.exception.metadata))
        finally:
            timer.cancel()

    def test_stderr_flood_is_stopped_before_timeout(self):
        script = "import sys\nwhile True: sys.stderr.buffer.write(b'x'*4096); sys.stderr.flush()\n"
        with self.assertRaisesRegex(ProcessFailure, "output limit") as raised:
            run_process([sys.executable, "-B", "-c", script], workspace(), 5, max_output=64)
        self.assertGreater(raised.exception.metadata["stderr_bytes"], 64)

    def test_runner_persists_only_safe_stream_metadata_on_protocol_failure(self):
        root = repository()
        child = workspace() / "failure.py"
        child.write_text("import sys\nsys.stdin.read()\n"
                         f"print({CANARY!r}, file=sys.stderr)\nprint({CANARY!r})\n", encoding="utf-8")
        agent, verifier = Codex(), FakeVerifier()
        runner = Runner(root, agent=agent, verifier=verifier)
        console = io.StringIO()
        with redirect_stdout(console), redirect_stderr(console), patch.object(
                agent, "preflight", return_value={"fixture": True}), patch(
                "harness.processes.codex_executable", return_value=[sys.executable, "-B", str(child)]):
            with self.assertRaisesRegex(ProcessFailure, "Malformed Codex JSONL"):
                runner.single_task()
        states = list((root / ".git/engineering-harness/runs").glob("*/state.json"))
        self.assertEqual(len(states), 1)
        state = read_json(states[0])
        self.assertEqual((state["stage"], state["attempts"]), ("needs_review", 1))
        self.assertEqual(verifier.calls, 0)
        self.assertEqual(len(state["events"]), 1)
        event = state["events"][0]
        self.assertEqual(event["kind"], "process_failure")
        self.assertGreater(event["stderr_bytes"], 0)
        self.assertNotIn(CANARY, states[0].read_text() + console.getvalue())
