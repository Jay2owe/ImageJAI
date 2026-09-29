"""Dismiss a blocking ImageJ error while the console launches Fiji.

ImageJ paints the error message itself, so Win32 cannot read its text. Match
the native dialog shape and the selected Fiji executable instead. The console
only calls this during a launch it started, unless the user disables it.
"""
from __future__ import annotations

import ctypes
import os
from pathlib import Path


def _is_selected_fiji_dialog(
    root: Path, executable: str, title: str, class_name: str,
    children: list[tuple[str, str]],
) -> bool:
    process = Path(executable)
    if process.name.casefold() not in {
        "imagej-win64.exe", "imagej-win32.exe", "imagej.exe",
        "fiji-windows-x64.exe", "fiji-win64.exe",
    }:
        return False
    if str(process.parent).casefold() != str(root).casefold():
        return False
    return (
        title == "(Fiji Is Just) ImageJ"
        and class_name == "SunAwtDialog"
        and sum(cls == "Button" and label.strip() == "OK"
                for cls, label in children) == 1
        and all(cls in {"Button", "SunAwtCanvas"} for cls, _ in children)
        and any(cls == "SunAwtCanvas" for cls, _ in children)
    )


def dismiss_startup_error(root: Path) -> bool:
    """Post WM_CLOSE to the selected Fiji's native startup error, if present."""
    if os.name != "nt":
        return False
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
    user32.EnumChildWindows.argtypes = [wintypes.HWND, callback_type, wintypes.LPARAM]
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    user32.IsWindowVisible.restype = wintypes.BOOL
    user32.GetClassNameW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    user32.PostMessageW.restype = wintypes.BOOL
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.QueryFullProcessImageNameW.argtypes = [
        wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    kernel32.QueryFullProcessImageNameW.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]

    def window_string(hwnd: int, function) -> str:
        buffer = ctypes.create_unicode_buffer(512)
        function(hwnd, buffer, len(buffer))
        return buffer.value

    def process_path(pid: int) -> str:
        handle = kernel32.OpenProcess(0x1000, False, pid)  # query limited information
        if not handle:
            return ""
        try:
            buffer = ctypes.create_unicode_buffer(32768)
            length = wintypes.DWORD(len(buffer))
            if kernel32.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(length)):
                return buffer.value
            return ""
        finally:
            kernel32.CloseHandle(handle)

    closed = False

    @callback_type
    def inspect(hwnd: int, _lparam: int) -> bool:
        nonlocal closed
        if not user32.IsWindowVisible(hwnd):
            return True
        title = window_string(hwnd, user32.GetWindowTextW)
        class_name = window_string(hwnd, user32.GetClassNameW)
        if title != "(Fiji Is Just) ImageJ" or class_name != "SunAwtDialog":
            return True
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        executable = process_path(pid.value)
        children: list[tuple[str, str]] = []

        @callback_type
        def inspect_child(child: int, _child_lparam: int) -> bool:
            children.append((window_string(child, user32.GetClassNameW),
                             window_string(child, user32.GetWindowTextW)))
            return True

        user32.EnumChildWindows(hwnd, inspect_child, 0)
        if _is_selected_fiji_dialog(root, executable, title, class_name, children):
            closed = bool(user32.PostMessageW(hwnd, 0x0010, 0, 0))  # WM_CLOSE
        return not closed

    user32.EnumWindows(inspect, 0)
    return closed
