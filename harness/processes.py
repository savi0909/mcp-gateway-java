"""Bounded process supervision; never persist raw agent stdout or secrets."""
from __future__ import annotations

import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import threading
import time

from harness.common import Halt, require


class ProcessFailure(Halt):
    def __init__(self, message, metadata=None):
        super().__init__(message)
        self.metadata = metadata or {}


def safe_environment():
    allowed = {"PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP",
               "USERPROFILE", "HOME", "LOCALAPPDATA", "APPDATA", "JAVA_HOME", "LANG"}
    env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    env.update(PYTHONDONTWRITEBYTECODE="1", PYTHONUTF8="1", GIT_TERMINAL_PROMPT="0",
               MAVEN_SKIP_RC="true")
    return env


class WindowsJob:
    """Assign a suspended child to a kill-on-close job before any child code runs."""
    def __init__(self):
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel = kernel
        class Basic(ctypes.Structure):
            _fields_ = [("per_process", ctypes.c_longlong), ("per_job", ctypes.c_longlong),
                        ("flags", wintypes.DWORD), ("min_ws", ctypes.c_size_t),
                        ("max_ws", ctypes.c_size_t), ("active", wintypes.DWORD),
                        ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD),
                        ("scheduling", wintypes.DWORD)]
        class IO(ctypes.Structure):
            _fields_ = [(name, ctypes.c_ulonglong) for name in
                        ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]
        class Extended(ctypes.Structure):
            _fields_ = [("basic", Basic), ("io", IO), ("process_memory", ctypes.c_size_t),
                        ("job_memory", ctypes.c_size_t), ("peak_process", ctypes.c_size_t),
                        ("peak_job", ctypes.c_size_t)]
        kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        kernel.CreateJobObjectW.restype = wintypes.HANDLE
        kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.handle = kernel.CreateJobObjectW(None, None)
        require(bool(self.handle), "Cannot create Windows job")
        limits = Extended()
        limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not kernel.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.close()
            raise ProcessFailure("Cannot configure Windows job")

    def attach_and_resume(self, process):
        kernel = self.kernel
        require(bool(kernel.AssignProcessToJobObject(self.handle, int(process._handle))),
                "Cannot contain Windows child process")
        class ThreadEntry(ctypes.Structure):
            _fields_ = [("size", wintypes.DWORD), ("usage", wintypes.DWORD),
                        ("thread", wintypes.DWORD), ("owner", wintypes.DWORD),
                        ("base", wintypes.LONG), ("delta", wintypes.LONG), ("flags", wintypes.DWORD)]
        kernel.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
        kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
        kernel.Thread32First.argtypes = [wintypes.HANDLE, ctypes.POINTER(ThreadEntry)]
        kernel.Thread32Next.argtypes = [wintypes.HANDLE, ctypes.POINTER(ThreadEntry)]
        kernel.OpenThread.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenThread.restype = wintypes.HANDLE
        kernel.ResumeThread.argtypes = [wintypes.HANDLE]
        kernel.ResumeThread.restype = wintypes.DWORD
        snapshot = kernel.CreateToolhelp32Snapshot(4, 0)
        require(snapshot not in (None, ctypes.c_void_p(-1).value), "Cannot inspect suspended child")
        entry = ThreadEntry()
        entry.size = ctypes.sizeof(entry)
        resumed = False
        try:
            present = kernel.Thread32First(snapshot, ctypes.byref(entry))
            while present:
                if entry.owner == process.pid:
                    thread = kernel.OpenThread(2, False, entry.thread)
                    require(bool(thread), "Cannot resume contained child")
                    try:
                        require(kernel.ResumeThread(thread) != 0xFFFFFFFF, "Cannot resume child thread")
                        resumed = True
                    finally:
                        kernel.CloseHandle(thread)
                present = kernel.Thread32Next(snapshot, ctypes.byref(entry))
            require(resumed, "Suspended child thread missing")
        finally:
            kernel.CloseHandle(snapshot)

    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None


def run_process(argv, cwd, timeout, *, stdin="", cancel=None, heartbeat=None, max_output=2_000_000):
    """Capture stdout, count/discard stderr, and bound their combined bytes.

    Kill descendants on success, timeout or cancel. Diagnostics never enter the
    protocol stream or returned metadata, including on failure.
    """
    require(timeout > 0, "Runtime budget exhausted")
    job = WindowsJob() if os.name == "nt" else None
    process = None
    failure = None
    output = bytearray()
    counts = {"stdout_bytes": 0, "stderr_bytes": 0}
    output_lock = threading.Lock()
    too_large = threading.Event()
    readers = []
    read_failed = threading.Event()
    started = time.monotonic()
    try:
        process = subprocess.Popen(argv, cwd=cwd, env=safe_environment(), stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   creationflags=0x4 if os.name == "nt" else 0,
                                   start_new_session=os.name != "nt")
        if job:
            job.attach_and_resume(process)
        def drain(stream, name):
            try:
                while chunk := stream.read1(4096):
                    with output_lock:
                        counts[name] += len(chunk)
                        if counts["stdout_bytes"] + counts["stderr_bytes"] > max_output:
                            too_large.set()
                        elif name == "stdout_bytes" and not too_large.is_set():
                            output.extend(chunk)
            except OSError:
                read_failed.set()
        def feed():
            try:
                process.stdin.write(stdin.encode("utf-8"))
                process.stdin.close()
            except (BrokenPipeError, OSError):
                pass
        readers = [threading.Thread(target=drain, args=(stream, name), daemon=True)
                   for stream, name in ((process.stdout, "stdout_bytes"), (process.stderr, "stderr_bytes"))]
        writer = threading.Thread(target=feed, daemon=True)
        for reader in readers:
            reader.start()
        writer.start()
        next_heartbeat = started + 15
        while process.poll() is None:
            now = time.monotonic()
            if cancel and cancel.is_set():
                raise ProcessFailure("Cancelled")
            if now - started >= timeout:
                raise ProcessFailure("Process timeout")
            if too_large.is_set():
                raise ProcessFailure("Process output limit exceeded")
            if read_failed.is_set():
                raise ProcessFailure("Cannot read process output")
            if heartbeat and now >= next_heartbeat:
                heartbeat()
                next_heartbeat = now + 15
            time.sleep(0.05)
        if job:
            job.close()
        else:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        for reader in readers:
            reader.join(timeout=5)
        require(not any(reader.is_alive() for reader in readers), "Process output pipe did not close")
        require(not read_failed.is_set(), "Cannot read process output")
        require(not too_large.is_set(), "Process output limit exceeded")
        return {"exit_code": process.returncode, "output": output.decode("utf-8", errors="replace"),
                "duration_seconds": round(time.monotonic() - started, 3), **counts}
    except (Halt, OSError) as error:
        failure = ProcessFailure(str(error) if isinstance(error, Halt) else "Cannot launch required executable",
                                 {"duration_seconds": round(time.monotonic() - started, 3)})
        raise failure from error
    finally:
        if job:
            job.close()
        if process:
            if os.name != "nt":
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            elif process.poll() is None:
                process.kill()
            process.wait(timeout=5)
            for reader in readers:
                reader.join(timeout=5)
            if failure:
                failure.metadata["exit_code"] = process.returncode
                with output_lock:
                    failure.metadata.update(counts)
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream and not stream.closed:
                    stream.close()


def codex_executable():
    executable = shutil.which("codex.exe") if os.name == "nt" else shutil.which("codex")
    if executable:
        return [executable]
    # npm's Windows shell shim is avoided; invoke its JS entry point with node directly.
    shim = shutil.which("codex.cmd")
    node = shutil.which("node.exe")
    entry = Path(shim).parent / "node_modules/@openai/codex/bin/codex.js" if shim else None
    require(node and entry and entry.is_file(), "Codex executable unavailable")
    return [node, str(entry)]


class Codex:
    def preflight(self, root, config):
        require(not (Path(root) / ".codex").exists(), "Project Codex configuration requires manual review")
        for relative in (".claude/settings.json", ".mcp.json"):
            require(not (Path(root) / relative).exists(), "Project hooks/connectors require manual review")
        command = codex_executable()
        result = run_process(command + ["exec", "--help"], root, 15)
        require(result["exit_code"] == 0, "Codex help unavailable")
        for flag in ("--ignore-user-config", "--sandbox", "--json", "--output-schema", "--ephemeral"):
            require(flag in result["output"], "Required Codex CLI flag unavailable")
        # Never print the cache/configuration or access auth files.
        cache = Path.home() / ".codex/models_cache.json"
        from harness.common import read_json
        models = {m["slug"]: m for m in read_json(cache).get("models", [])}
        for role in ("implement", "complex_implement", "review"):
            setting = config["models"][role]
            model = models.get(setting["name"], {})
            require(model.get("supported_in_api") is True and model.get("visibility") == "list",
                    "Configured model is not advertised by installed runtime")
            require(setting["reasoning_effort"] in {r["effort"] for r in model.get("supported_reasoning_levels", [])},
                    "Configured reasoning effort unavailable")
        version = run_process(command + ["--version"], root, 15)
        require(version["exit_code"] == 0, "Codex version unavailable")
        return {"cli_version": version["output"].strip(), "model_cache_checked": True,
                "sandbox_execution": "unverified until a separately approved pilot"}

    def execute(self, root, model, prompt, schema, timeout, cancel, *, review=False):
        command = codex_executable() + ["-a", "never", "exec", "--ignore-user-config", "--ephemeral",
                  "--sandbox", "read-only" if review else "workspace-write", "--cd", str(root),
                  "--model", model["name"], "-c", 'model_reasoning_effort="' + model["reasoning_effort"] + '"',
                  "--json", "--output-schema", str(schema), "-"]
        result = run_process(command, root, timeout, stdin=prompt, cancel=cancel,
                             heartbeat=lambda: print("Agent process running; deadline remains enforced.", flush=True))
        metadata = {k: result[k] for k in ("exit_code", "duration_seconds", "stdout_bytes", "stderr_bytes")}
        if result["exit_code"] != 0:
            raise ProcessFailure("Codex process failed; no automatic infrastructure retry", metadata)
        messages, event_count, completed = [], 0, False
        for line in result["output"].splitlines():
            try:
                event = json.loads(line)
            except ValueError as error:
                raise ProcessFailure("Malformed Codex JSONL", metadata) from error
            if not isinstance(event, dict) or not isinstance(event.get("type"), str):
                raise ProcessFailure("Malformed Codex event", metadata)
            event_count += 1
            if event["type"] in ("error", "turn.failed"):
                raise ProcessFailure("Codex reported failure", metadata)
            completed |= event.get("type") == "turn.completed"
            if event["type"] == "item.completed":
                item = event.get("item")
                if not isinstance(item, dict) or not isinstance(item.get("type"), str):
                    raise ProcessFailure("Malformed Codex item", metadata)
                if item["type"] == "agent_message":
                    if not isinstance(item.get("text"), str):
                        raise ProcessFailure("Malformed Codex message", metadata)
                    messages.append(item["text"])
        if not completed or not messages:
            raise ProcessFailure("Missing completed Codex response", metadata)
        try:
            report = json.loads(messages[-1])
        except ValueError as error:
            raise ProcessFailure("Malformed structured agent report", metadata) from error
        from harness.common import read_json, validate_schema
        try:
            validate_schema(report, read_json(schema))
        except Halt as error:
            raise ProcessFailure("Invalid structured agent report", metadata) from error
        # Only whitelisted status/count metadata is persisted by the caller.
        return report, {**metadata, "event_count": event_count}
