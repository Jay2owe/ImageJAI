from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

import ollama
from PIL import Image

try:
    from .loop import _resolve_default_model, _encode_capture_for_vision
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from agent.gemma4_31b.loop import _resolve_default_model, _encode_capture_for_vision


def _probe_model() -> str:
    """Model to probe with: IMAGEJAI_MODEL, else the resolver's default."""
    return os.environ.get("IMAGEJAI_MODEL", "").strip() or _resolve_default_model()


def _test_image_path() -> Path:
    """Return a PNG path from argv or a generated solid-red fallback image."""
    if len(sys.argv) > 1:
        return Path(sys.argv[1]).resolve()
    img = Image.new('RGB', (256, 256), (255, 0, 0))
    fh = tempfile.NamedTemporaryFile(suffix='.png', delete=False)
    with fh:
        img.save(fh, format='PNG')
    return Path(fh.name)


def _ask(messages: list[dict]) -> str:
    """Send one probe conversation and return the model's text reply."""
    resp = ollama.chat(model=_probe_model(), messages=messages, stream=False)
    return (getattr(resp.message, 'content', '') or '').strip()


def main() -> int:
    path = _test_image_path()
    b64 = _encode_capture_for_vision(str(path))
    if not b64:
        print('Failed to encode test image from {}'.format(path))
        return 1

    tool_msg = {'role': 'tool', 'content': str(path), 'tool_name': 'capture_image', 'images': [b64]}
    base = [
        {'role': 'user', 'content': 'Call capture_image now.'},
        {'role': 'assistant', 'content': '', 'tool_calls': [{'function': {'name': 'capture_image', 'arguments': {'max_size': 1024}}}]},
        tool_msg,
    ]
    a_messages = base + [{'role': 'user', 'content': 'What is in the image the tool returned?'}]
    b_messages = base + [
        {'role': 'user', 'content': 'Synthetic relay of the tool image.', 'images': [b64]},
        {'role': 'user', 'content': 'What is in the image the tool returned?'},
    ]

    print('A: tool message carries images')
    print(_ask(a_messages))
    print()
    print('B: synthetic user message carries images')
    print(_ask(b_messages))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
