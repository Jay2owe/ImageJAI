"""Shared provider credentials for the Python console and Fiji plugin.

On Windows, values are encrypted with the current user's Data Protection API
(DPAPI) before being written to ``~/.imagej-ai/secrets/<provider>.cred``. The
Java plugin uses the same file format. Existing plaintext files are migrated
after a successful read.
"""
from __future__ import annotations

import base64
import ctypes
import json
import os
from ctypes import wintypes
from pathlib import Path
from typing import Mapping

MAGIC = b"IMAGEJAI-DPAPI-1\n"


class CredentialStoreError(RuntimeError):
    """The operating-system credential store could not be used."""


class _DataBlob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]


def _blob(data: bytes) -> tuple[_DataBlob, object]:
    buffer = ctypes.create_string_buffer(data)
    return _DataBlob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte))), buffer


def _dpapi(data: bytes, *, protect: bool) -> bytes:
    if os.name != "nt":
        raise CredentialStoreError("DPAPI is only available on Windows")
    source, source_buffer = _blob(data)
    destination = _DataBlob()
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    if protect:
        ok = crypt32.CryptProtectData(
            ctypes.byref(source), "ImageJAI credentials", None, None, None,
            0, ctypes.byref(destination),
        )
    else:
        ok = crypt32.CryptUnprotectData(
            ctypes.byref(source), None, None, None, None, 0,
            ctypes.byref(destination),
        )
    del source_buffer
    if not ok:
        raise CredentialStoreError(
            f"Windows credential protection failed ({ctypes.GetLastError()})"
        )
    try:
        return ctypes.string_at(destination.pbData, destination.cbData)
    finally:
        kernel32.LocalFree(destination.pbData)


def credential_path(provider: str, secrets_dir: Path) -> Path:
    return secrets_dir / f"{provider}.cred"


def legacy_path(provider: str, secrets_dir: Path) -> Path:
    return secrets_dir / f"{provider}.env"


def _parse_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return values
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key.strip()] = value
    return values


def save_entries(provider: str, entries: Mapping[str, str], secrets_dir: Path) -> None:
    if os.name != "nt":
        secrets_dir.mkdir(parents=True, exist_ok=True)
        target = legacy_path(provider, secrets_dir)
        temp = target.with_suffix(".env.tmp")
        lines: list[str] = []
        for key, value in entries.items():
            clean = str(value).replace("\r", "").replace("\n", "")
            lines.append(f"{key}={clean}")
        temp.write_text("\n".join(lines) + "\n", encoding="utf-8")
        temp.chmod(0o600)
        os.replace(temp, target)
        return
    secrets_dir.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(dict(entries), separators=(",", ":")).encode("utf-8")
    protected = _dpapi(payload, protect=True)
    target = credential_path(provider, secrets_dir)
    temp = target.with_suffix(".cred.tmp")
    temp.write_bytes(MAGIC + base64.b64encode(protected) + b"\n")
    os.replace(temp, target)


def load_entries(provider: str, secrets_dir: Path, *, migrate: bool = True) -> dict[str, str]:
    target = credential_path(provider, secrets_dir)
    if target.is_file():
        try:
            body = target.read_bytes()
            if not body.startswith(MAGIC):
                raise CredentialStoreError(f"unsupported credential format: {target}")
            clear = _dpapi(base64.b64decode(body[len(MAGIC):].strip()), protect=False)
            value = json.loads(clear.decode("utf-8"))
            return {str(k): str(v) for k, v in value.items()} if isinstance(value, dict) else {}
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            raise CredentialStoreError(f"could not read {target.name}: {exc}") from exc

    old = legacy_path(provider, secrets_dir)
    values = _parse_env(old)
    if values and migrate and os.name == "nt":
        save_entries(provider, values, secrets_dir)
        old.unlink(missing_ok=True)
    return values


def clear_entries(provider: str, secrets_dir: Path) -> None:
    credential_path(provider, secrets_dir).unlink(missing_ok=True)
    legacy_path(provider, secrets_dir).unlink(missing_ok=True)
