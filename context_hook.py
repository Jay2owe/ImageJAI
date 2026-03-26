"""
Context hook for ImageJAI — injects Fiji state into Claude conversations.

Session start:  Fiji connection, installed plugins/commands, reference doc list
Every message:  Open images, active image, results table, memory, dialogs, IJ log
"""

import json
import os
import socket
import sys
import argparse

TCP_HOST = "127.0.0.1"
TCP_PORT = 7746
TCP_TIMEOUT = 3  # seconds per request
PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
REFS_DIR = os.path.join(PROJECT_DIR, "agent", "references")


# ---------------------------------------------------------------------------
# TCP helpers
# ---------------------------------------------------------------------------

def tcp_send(command_obj):
    """Send a JSON command to the Fiji TCP server and return parsed response."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(TCP_TIMEOUT)
    try:
        sock.connect((TCP_HOST, TCP_PORT))
        payload = json.dumps(command_obj) + "\n"
        sock.sendall(payload.encode("utf-8"))
        chunks = []
        while True:
            data = sock.recv(8192)
            if not data:
                break
            chunks.append(data)
        raw = b"".join(chunks).decode("utf-8").strip()
        return json.loads(raw)
    finally:
        sock.close()


def fiji_available():
    """Check if Fiji TCP server is reachable."""
    try:
        resp = tcp_send({"command": "ping"})
        return resp.get("ok", False)
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Session-start context items
# ---------------------------------------------------------------------------

def fiji_connection_status():
    """Check if Fiji is running and TCP server is responding."""
    if fiji_available():
        return "Fiji: connected (TCP 7746)"
    else:
        return "Fiji: NOT connected — TCP server on port 7746 is not responding"


def installed_plugins():
    """Get list of installed Fiji commands/plugins via macro."""
    try:
        resp = tcp_send({
            "command": "execute_macro",
            "code": 'list = getList("commands"); result = ""; for (i = 0; i < list.length; i++) result += list[i] + "\\n"; return result;'
        })
        if resp.get("ok") and resp.get("result", {}).get("success"):
            output = resp["result"].get("output", "")
            commands = [c.strip() for c in output.strip().split("\n") if c.strip()]
            if commands:
                return "Installed Fiji commands (" + str(len(commands)) + "): " + ", ".join(commands)
            return "Fiji commands: (empty list returned)"
        return "Fiji commands: could not retrieve — " + resp.get("result", {}).get("error", "unknown error")
    except Exception as e:
        return "Fiji commands: unavailable — " + str(e)


def reference_docs():
    """List available reference documents in agent/references/."""
    try:
        if not os.path.isdir(REFS_DIR):
            return "Reference docs: directory not found"
        files = [f.replace("-reference.md", "").replace(".md", "")
                 for f in sorted(os.listdir(REFS_DIR))
                 if f.endswith(".md") and f != "INDEX.md"]
        if files:
            return "Reference docs (" + str(len(files)) + "): " + ", ".join(files)
        return "Reference docs: none found"
    except Exception as e:
        return "Reference docs: error — " + str(e)


# ---------------------------------------------------------------------------
# Every-message context items
# ---------------------------------------------------------------------------

def fiji_state():
    """Get open images, active image details, results table, and memory."""
    try:
        resp = tcp_send({"command": "get_state"})
        if not resp.get("ok"):
            return "Fiji state: error — " + resp.get("error", "unknown")

        result = resp["result"]
        parts = []

        # Active image
        active = result.get("activeImage")
        if active:
            dims = active.get("width", "?") + "x" + active.get("height", "?") if isinstance(active.get("width"), str) else \
                   str(active.get("width", "?")) + "x" + str(active.get("height", "?"))
            slices = active.get("nSlices", 1)
            channels = active.get("nChannels", 1)
            frames = active.get("nFrames", 1)
            stack_info = ""
            if slices > 1 or channels > 1 or frames > 1:
                stack_info = " [C=" + str(channels) + " Z=" + str(slices) + " T=" + str(frames) + "]"
            cal = active.get("calibration", {})
            cal_info = ""
            if cal and cal.get("unit") and cal.get("unit") != "pixel":
                cal_info = " cal=" + str(cal.get("pixelWidth", "?")) + " " + cal.get("unit", "")
            bit_depth = active.get("bitDepth", active.get("type", "?"))
            roi = active.get("roi")
            roi_info = ""
            if roi and roi != "None":
                roi_info = " ROI=" + str(roi)
            parts.append("Active: \"" + str(active.get("title", "?")) + "\" " +
                         dims + " " + str(bit_depth) + "-bit" + stack_info + cal_info + roi_info)
        else:
            parts.append("Active: none")

        # All images
        all_imgs = result.get("allImages", [])
        if len(all_imgs) > 1:
            titles = [str(img.get("title", "?")) for img in all_imgs]
            parts.append("Open images (" + str(len(titles)) + "): " + ", ".join(titles))
        elif len(all_imgs) == 1:
            parts.append("Open images: 1")
        else:
            parts.append("Open images: 0")

        # Results table
        csv = result.get("resultsTable", "")
        if csv and csv.strip():
            lines = csv.strip().split("\n")
            if lines:
                header = lines[0]
                cols = [c.strip() for c in header.split(",") if c.strip()]
                row_count = len(lines) - 1
                parts.append("Results table: " + str(row_count) + " rows, columns=[" + ", ".join(cols) + "]")
        else:
            parts.append("Results table: empty")

        # Memory
        mem = result.get("memory", {})
        if mem:
            used = mem.get("usedMB", "?")
            max_mem = mem.get("maxMB", "?")
            free = mem.get("freeMB", "?")
            img_count = mem.get("openImageCount", "?")
            parts.append("JVM memory: " + str(used) + "/" + str(max_mem) + " MB used, " +
                         str(free) + " MB free, " + str(img_count) + " images")

        return " | ".join(parts)
    except socket.error:
        return "Fiji state: not connected"
    except Exception as e:
        return "Fiji state: error — " + str(e)


def fiji_dialogs():
    """Get any open dialogs (errors, prompts, warnings)."""
    try:
        resp = tcp_send({"command": "get_dialogs"})
        if not resp.get("ok"):
            return None

        dialogs = resp.get("result", {}).get("dialogs", [])
        if not dialogs:
            return None  # No dialogs = nothing to report

        summaries = []
        for d in dialogs:
            dtype = d.get("type", "info")
            title = d.get("title", "")
            text = d.get("text", "")
            # Truncate long dialog text
            if len(text) > 200:
                text = text[:200] + "..."
            buttons = d.get("buttons", [])
            btn_str = " [" + "/".join(buttons) + "]" if buttons else ""
            summaries.append(dtype.upper() + ": \"" + title + "\" — " + text + btn_str)

        return "Open dialogs: " + " | ".join(summaries)
    except socket.error:
        return None
    except Exception:
        return None


def fiji_progress():
    """Get Fiji progress bar status and status line text."""
    try:
        resp = tcp_send({"command": "get_progress"})
        if not resp.get("ok"):
            return None

        result = resp.get("result", {})
        active = result.get("active", False)
        status = result.get("status", "")
        percent = result.get("percent", 0)

        if active:
            return "Progress bar: " + str(percent) + "% | Status: " + status
        elif status and not status.startswith("(Fiji Is Just)"):
            return "Status: " + status
        return None
    except socket.error:
        return None
    except Exception:
        return None


def fiji_log():
    """Get the IJ log window contents (last few lines)."""
    try:
        resp = tcp_send({"command": "get_log"})
        if not resp.get("ok"):
            return None

        log = resp.get("result", "")
        if not log or not log.strip():
            return None

        lines = log.strip().split("\n")
        # Only show last 10 lines to keep it concise
        recent = lines[-10:] if len(lines) > 10 else lines
        return "IJ Log (last " + str(len(recent)) + " of " + str(len(lines)) + " lines):\n" + "\n".join(recent)
    except socket.error:
        return None
    except Exception:
        return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--timing", choices=["session-start", "every-message"], required=True)
    args = parser.parse_args()

    # Read stdin (hook passes JSON input, but we don't need it)
    try:
        sys.stdin.read()
    except Exception:
        pass

    context_parts = []

    if args.timing == "session-start":
        context_parts.append(fiji_connection_status())
        if fiji_available():
            context_parts.append(installed_plugins())
        context_parts.append(reference_docs())

    elif args.timing == "every-message":
        if not fiji_available():
            context_parts.append("Fiji: not connected")
        else:
            context_parts.append(fiji_state())

            progress = fiji_progress()
            if progress:
                context_parts.append(progress)

            dialogs = fiji_dialogs()
            if dialogs:
                context_parts.append(dialogs)

            log = fiji_log()
            if log:
                context_parts.append(log)

    # Skip output entirely if nothing to report
    if not context_parts:
        print(json.dumps({}))
        return

    msg = "\n".join(context_parts)

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "SessionStart" if args.timing == "session-start" else "UserPromptSubmit",
            "additionalContext": msg
        }
    }))


if __name__ == "__main__":
    main()
