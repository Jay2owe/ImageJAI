#!/usr/bin/env python
"""
ImageJAI TCP command helper.

Usage:
    python ij.py ping
    python ij.py state
    python ij.py info
    python ij.py results
    python ij.py capture [name]
    python ij.py macro "run('Blobs (25K)');"
    python ij.py explore Otsu Triangle Li
    python ij.py log
    python ij.py histogram
    python ij.py windows
    python ij.py metadata
    python ij.py raw '{"command": "ping"}'

Can also be imported:
    from ij import imagej_command
    result = imagej_command({"command": "ping"})
"""

import socket
import json
import sys
import os
import base64

HOST = "localhost"
PORT = 7746
TIMEOUT = 60


def imagej_command(cmd, host=HOST, port=PORT, timeout=TIMEOUT):
    """Send a JSON command to ImageJAI TCP server and return parsed response.
    On timeout, automatically checks for open dialogs before returning."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        payload = json.dumps(cmd) + "\n"
        s.sendall(payload.encode("utf-8"))
        # Read response
        data = b""
        while True:
            try:
                chunk = s.recv(65536)
                if not chunk:
                    break
                data += chunk
                # Check if we have a complete JSON response (ends with newline)
                if data.endswith(b"\n"):
                    break
            except socket.timeout:
                # TCP timeout — command may have opened a blocking dialog.
                # Check for dialogs immediately.
                s.close()
                dialogs = _check_dialogs_fallback(host, port)
                return {
                    "ok": False,
                    "error": "TCP timeout after {}s — command may be blocked by a dialog".format(timeout),
                    "dialogs": dialogs,
                }
        return json.loads(data.decode("utf-8"))
    finally:
        try:
            s.close()
        except Exception:
            pass


def _check_dialogs_fallback(host=HOST, port=PORT):
    """Emergency dialog check after a timeout. Uses a short timeout."""
    try:
        s2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s2.settimeout(5)
        s2.connect((host, port))
        s2.sendall((json.dumps({"command": "get_dialogs"}) + "\n").encode("utf-8"))
        data = b""
        while True:
            try:
                chunk = s2.recv(65536)
                if not chunk:
                    break
                data += chunk
                if data.endswith(b"\n"):
                    break
            except socket.timeout:
                break
        s2.close()
        resp = json.loads(data.decode("utf-8"))
        if resp.get("ok") and resp.get("result", {}).get("dialogs"):
            return resp["result"]["dialogs"]
    except Exception:
        pass
    return []


def ping():
    return imagej_command({"command": "ping"})


def get_state():
    return imagej_command({"command": "get_state"})


def get_image_info():
    return imagej_command({"command": "get_image_info"})


def get_results_table():
    return imagej_command({"command": "get_results_table"})


def get_state_context():
    return imagej_command({"command": "get_state_context"})


def execute_macro(code):
    return imagej_command({"command": "execute_macro", "code": code})


def capture_image(max_size=1024):
    return imagej_command({"command": "capture_image", "maxSize": max_size})


def run_pipeline(steps):
    return imagej_command({"command": "run_pipeline", "steps": steps})


def explore_thresholds(methods):
    return imagej_command({"command": "explore_thresholds", "methods": methods})


def get_log():
    return imagej_command({"command": "get_log"})


def get_histogram():
    return imagej_command({"command": "get_histogram"})


def get_open_windows():
    return imagej_command({"command": "get_open_windows"})


def get_metadata():
    return imagej_command({"command": "get_metadata"})


def get_pixels(x=None, y=None, width=None, height=None, slice_num=None, all_slices=False):
    cmd = {"command": "get_pixels"}
    if x is not None: cmd["x"] = x
    if y is not None: cmd["y"] = y
    if width is not None: cmd["width"] = width
    if height is not None: cmd["height"] = height
    if slice_num is not None: cmd["slice"] = slice_num
    if all_slices: cmd["allSlices"] = True
    return imagej_command(cmd)


def viewer3d(action="status", **kwargs):
    cmd = {"command": "3d_viewer", "action": action}
    cmd.update(kwargs)
    return imagej_command(cmd)


def get_dialogs():
    return imagej_command({"command": "get_dialogs"})


def close_dialogs(pattern=None):
    return imagej_command({"command": "close_dialogs", "pattern": pattern})


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    cmd = sys.argv[1].lower()

    try:
        if cmd == "ping":
            print(json.dumps(ping(), indent=2))

        elif cmd == "state":
            print(json.dumps(get_state(), indent=2))

        elif cmd == "info":
            print(json.dumps(get_image_info(), indent=2))

        elif cmd == "results":
            resp = get_results_table()
            if resp.get("ok") and resp.get("result"):
                print(resp["result"])
            else:
                print(json.dumps(resp, indent=2))

        elif cmd == "context":
            resp = get_state_context()
            if resp.get("ok") and resp.get("result"):
                print(resp["result"])
            else:
                print(json.dumps(resp, indent=2))

        elif cmd == "capture":
            resp = capture_image()
            if resp.get("ok") and resp.get("result", {}).get("base64"):
                # Default to .tmp/ dir so captures don't pollute the workspace
                tmp_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tmp")
                os.makedirs(tmp_dir, exist_ok=True)
                name = sys.argv[2] if len(sys.argv) > 2 else "capture"
                outfile = os.path.join(tmp_dir, name + ".png")
                img_data = base64.b64decode(resp["result"]["base64"])
                with open(outfile, "wb") as f:
                    f.write(img_data)
                print("Saved to " + outfile)
            else:
                print(json.dumps(resp, indent=2))

        elif cmd == "macro":
            if len(sys.argv) < 3:
                print("Usage: python ij.py macro \"run('Blobs (25K)');\"")
                sys.exit(1)
            code = " ".join(sys.argv[2:])
            resp = execute_macro(code)
            print(json.dumps(resp, indent=2))
            # Auto-warn about dialogs — check EVERYWHERE they might be
            dlgs = []
            if resp.get("ok") and resp.get("result", {}).get("dialogs"):
                dlgs = resp["result"]["dialogs"]
            elif resp.get("dialogs"):
                dlgs = resp["dialogs"]
            if dlgs:
                print("\n*** DIALOGS DETECTED ({}) ***".format(len(dlgs)))
                for d in dlgs:
                    print("  [{}] {}: {}".format(
                        d.get("type", "?").upper(),
                        d.get("title", ""),
                        d.get("text", "").strip()[:200]
                    ))
                    if d.get("buttons"):
                        print("    Buttons: {}".format(", ".join(d["buttons"])))

        elif cmd == "explore":
            methods = sys.argv[2:] if len(sys.argv) > 2 else ["Otsu", "Triangle", "Li", "Huang", "MaxEntropy"]
            print(json.dumps(explore_thresholds(methods), indent=2))

        elif cmd == "log":
            resp = get_log()
            if resp.get("ok"):
                print(resp["result"])
            else:
                print(json.dumps(resp, indent=2))

        elif cmd == "histogram":
            print(json.dumps(get_histogram(), indent=2))

        elif cmd == "windows":
            print(json.dumps(get_open_windows(), indent=2))

        elif cmd == "metadata":
            print(json.dumps(get_metadata(), indent=2))

        elif cmd == "3d":
            # Sub-commands: status, add, list, snapshot, close
            sub = sys.argv[2] if len(sys.argv) > 2 else "status"
            kwargs = {}
            if sub == "add" and len(sys.argv) > 3:
                kwargs["image"] = sys.argv[3]
                if len(sys.argv) > 4: kwargs["type"] = sys.argv[4]
                if len(sys.argv) > 5: kwargs["threshold"] = int(sys.argv[5])
            elif sub == "snapshot":
                kwargs["width"] = int(sys.argv[3]) if len(sys.argv) > 3 else 512
                kwargs["height"] = int(sys.argv[4]) if len(sys.argv) > 4 else 512
            print(json.dumps(viewer3d(sub, **kwargs), indent=2))

        elif cmd == "dialogs":
            print(json.dumps(get_dialogs(), indent=2))

        elif cmd == "close_dialogs":
            pattern = sys.argv[2] if len(sys.argv) > 2 else None
            print(json.dumps(close_dialogs(pattern), indent=2))

        elif cmd == "raw":
            if len(sys.argv) < 3:
                print("Usage: python ij.py raw '{\"command\": \"ping\"}'")
                sys.exit(1)
            raw_cmd = json.loads(sys.argv[2])
            print(json.dumps(imagej_command(raw_cmd), indent=2))

        else:
            print("Unknown command: " + cmd)
            print(__doc__)
            sys.exit(1)

    except ConnectionRefusedError:
        print("ERROR: Cannot connect to ImageJAI on localhost:" + str(PORT))
        print("Make sure Fiji is running with AI Assistant open and TCP server enabled.")
        sys.exit(1)
    except Exception as e:
        print("ERROR: " + str(e))
        sys.exit(1)


if __name__ == "__main__":
    main()
