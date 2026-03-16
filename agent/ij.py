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
    """Send a JSON command to ImageJAI TCP server and return parsed response."""
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
                break
        return json.loads(data.decode("utf-8"))
    finally:
        s.close()


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
            print(json.dumps(execute_macro(code), indent=2))

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
