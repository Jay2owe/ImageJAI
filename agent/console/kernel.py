"""Lazy, serial, bounded Python process; Fiji reads go through the host socket."""
from __future__ import annotations
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time


class PythonWorkspace:
    def __init__(self, connection=None):
        self.connection = connection
        self._process = None
        self._lock = threading.Lock()
        self._image_binding = None
        self.variables = []

    def close(self):
        process, self._process = self._process, None
        if process:
            if process.poll() is None:
                process.kill()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                pass
            for stream in (process.stdin, process.stdout):
                if stream:
                    stream.close()
        self._image_binding = None
        self.variables = []

    @staticmethod
    def _identity(result):
        if result.get("image_revision") is None or result.get("image_id", result.get("id")) is None:
            raise ValueError("Image identity/revision is missing; cached arrays cannot be verified")
        return tuple(result.get(key) for key in ("image_id", "image_revision", "display_revision", "channel", "frame", "sliceStart", "sliceEnd"))

    def _start(self):
        env = dict(os.environ, OPENBLAS_NUM_THREADS="1", OMP_NUM_THREADS="1", MKL_NUM_THREADS="1")
        # -I also removes user-installed scientific packages on Windows.
        # Ignore inherited Python environment variables while retaining those
        # packages; the worker removes its script import directory itself.
        self._process = subprocess.Popen([sys.executable, "-E", "-u", str(Path(__file__).with_name("kernel_worker.py"))],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8", env=env,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        self._output = queue.Queue(maxsize=16)
        process, output = self._process, self._output
        def read():
            try:
                while True:
                    line = process.stdout.readline(8_000_001)
                    if not line:
                        output.put(None)
                        return
                    if len(line) > 8_000_000:
                        output.put({"error": "Worker reply exceeds the protocol bound"})
                        return
                    output.put(json.loads(line))
            except (OSError, ValueError):
                try:
                    output.put_nowait(None)
                except queue.Full:
                    pass
        threading.Thread(target=read, daemon=True, name="imagejai-python-output").start()

    def _memory_bytes(self):
        if os.name == "nt":
            import ctypes
            from ctypes import wintypes
            class Counters(ctypes.Structure):
                _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [(name, ctypes.c_size_t) for name in (
                    "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]
            counts = Counters()
            counts.cb = ctypes.sizeof(counts)
            if ctypes.windll.psapi.GetProcessMemoryInfo(wintypes.HANDLE(int(self._process._handle)), ctypes.byref(counts), counts.cb):
                return counts.WorkingSetSize
        elif Path(f"/proc/{self._process.pid}/statm").exists():
            return int(Path(f"/proc/{self._process.pid}/statm").read_text().split()[1]) * os.sysconf("SC_PAGE_SIZE")
        return 0

    def _host_pixels(self, request):
        if self.connection is None:
            raise ValueError("No Fiji connection is assigned to this workspace")
        args = {}
        for key in ("x", "y", "width", "height"):
            value = request.get(key)
            if type(value) is not int or value < (1 if key in {"width", "height"} else 0):
                raise ValueError("Pixel region needs positive dimensions and nonnegative integer coordinates")
            args[key] = value
        if args["width"] * args["height"] > 1_048_576:
            raise ValueError("Read at most 1,048,576 pixels per cell")
        reply = self.connection.command({"command": "get_pixels", **args}, timeout=10)
        result = reply.get("result", {})
        if not reply.get("ok") or not isinstance(result, dict) or result.get("encoding") != "base64_float32_le":
            raise ValueError("Fiji did not return supported raw pixel data")
        binding = self._identity(result)
        if self._image_binding is not None and binding != self._image_binding:
            raise ValueError("The image changed during this cell; reset the workspace before continuing")
        self._image_binding = binding
        return {"result": result}

    def execute(self, code: str, timeout: float = 30, abort=None):
        if not isinstance(code, str) or not code.strip() or len(code) > 65536:
            return {"ok": False, "error": "Provide 1–65,536 characters of Python code"}
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not 0 < timeout <= 120:
            return {"ok": False, "error": "Python timeout must be between 0 and 120 seconds"}
        if not self._lock.acquire(blocking=False):
            return {"ok": False, "error": "A Python cell is already running"}
        try:
            if abort and abort.set_flag:
                return {"ok": False, "error": "Interrupted before execution"}
            if self._image_binding:
                # Fetch tiny pixel metadata so C/Z/T and revision use exactly
                # the same field names as the original crop, without guessing.
                reply = self.connection.command({"command": "get_pixels", "x": 0, "y": 0, "width": 1, "height": 1}, timeout=10)
                if not reply.get("ok") or self._identity(reply.get("result", {})) != self._image_binding:
                    self.close()
                    return {"ok": False, "error": "Fiji image or plane changed; cached workspace cleared. Fetch pixels again."}
            if self._process is None or self._process.poll() is not None:
                self.close()
                self._start()
            process = self._process
            if abort:
                abort.register(self.close)
            process.stdin.write(json.dumps({"kind": "cell", "code": code}) + "\n")
            process.stdin.flush()
            deadline = time.monotonic() + timeout
            while True:
                if abort and abort.set_flag:
                    self.close()
                    return {"ok": False, "error": "Python interrupted; scratchpad cleared"}
                if time.monotonic() >= deadline or self._memory_bytes() > 512 * 1024 * 1024:
                    self.close()
                    return {"ok": False, "error": "Python time/memory limit reached; scratchpad cleared"}
                try:
                    message = self._output.get(timeout=0.05)
                except queue.Empty:
                    continue
                if message is None:
                    self.close()
                    return {"ok": False, "error": "Python process stopped; scratchpad cleared"}
                if message.get("kind") == "host":
                    try:
                        value = self._host_pixels(message)
                    except Exception as exc:
                        value = {"error": str(exc)}
                    process.stdin.write(json.dumps(value) + "\n")
                    process.stdin.flush()
                    continue
                self.variables = message.get("variables", self.variables)
                return message
        except Exception as exc:
            self.close()
            return {"ok": False, "error": f"Python workspace unavailable: {exc}; scratchpad cleared"}
        finally:
            if abort:
                abort.unregister(self.close)
            self._lock.release()
