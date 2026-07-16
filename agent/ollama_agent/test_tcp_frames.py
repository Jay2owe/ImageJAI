from __future__ import annotations

import pytest

from agent.ollama_agent.tcp_frames import recv_bounded


class FakeSocket:
    def __init__(self, payload, chunk_size=11):
        self.payload = bytearray(payload)
        self.chunk_size = chunk_size
        self.request_sizes = []

    def recv(self, size):
        self.request_sizes.append(size)
        if not self.payload:
            return b""
        count = min(size, self.chunk_size, len(self.payload))
        result = bytes(self.payload[:count])
        del self.payload[:count]
        return result


@pytest.mark.parametrize("newline,payload", [
    (False, b"x" * 65),
    (True, b"x" * 65 + b"\n"),
])
def test_rejects_oversize_reply_before_growing_application_buffer(
        newline, payload):
    sock = FakeSocket(payload, chunk_size=65)

    with pytest.raises(ValueError, match="reply frame exceeds 64 bytes"):
        recv_bounded(sock, max_bytes=64, newline=newline)

    assert max(sock.request_sizes) <= 65


def test_newline_reader_returns_only_first_frame_without_joining_chunks():
    sock = FakeSocket(b'{"ok":true}\nignored', chunk_size=4)

    assert recv_bounded(sock, max_bytes=64, newline=True) == b'{"ok":true}'


def test_exact_limit_is_accepted_at_eof():
    sock = FakeSocket(b"x" * 64, chunk_size=9)

    assert recv_bounded(sock, max_bytes=64) == b"x" * 64
