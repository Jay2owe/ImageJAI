"""Bounded TCP reply readers shared by the Ollama local-service clients."""

import socket

MAX_TEXT_REPLY_BYTES = 8 * 1024 * 1024


def recv_bounded(sock, max_bytes=MAX_TEXT_REPLY_BYTES, newline=False):
    """Read at most one bounded reply without repeated bytes concatenation.

    With ``newline=True`` the first newline terminates the frame. Otherwise
    EOF or the socket's configured timeout terminates the reply. The receive
    size is limited to the remaining allowance plus one byte so an oversized
    peer is rejected before a larger application buffer is allocated.
    """
    if not isinstance(max_bytes, int) or max_bytes < 1:
        raise ValueError("max_bytes must be a positive integer")

    data = bytearray()
    while True:
        remaining = max_bytes - len(data)
        try:
            chunk = sock.recv(min(65536, remaining + 1))
        except socket.timeout:
            break
        if not chunk:
            break
        if newline:
            boundary = chunk.find(b"\n")
            if boundary >= 0:
                if len(data) + boundary > max_bytes:
                    raise ValueError(
                        "TCP reply frame exceeds {} bytes".format(max_bytes))
                data.extend(chunk[:boundary])
                return bytes(data)
        if len(data) + len(chunk) > max_bytes:
            raise ValueError(
                "TCP reply frame exceeds {} bytes".format(max_bytes))
        data.extend(chunk)
    return bytes(data)
