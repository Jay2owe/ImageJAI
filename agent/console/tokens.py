"""Token minting that the Fiji plugin can reverse.

The chat input inserts a pseudonym instead of a file name when the posture
requires it. If the console minted that token itself, the plugin could not
turn it back into a path when the model later sends it inside a macro: the
plugin's map uses a JVM-side salt. So the console asks the plugin to mint
(TCP command ``pseudonymise_paths``) and keeps the pair locally as well.

When Fiji is not reachable the console falls back to a local token. That token
still works for everything the console does itself; it only fails if the model
sends it back while Fiji is offline, at which point nothing would work anyway.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .browse import PathTokenMap


class PluginTokenMap(PathTokenMap):
    """A PathTokenMap that mints through Fiji, with a local fallback."""

    def __init__(self, connection: Any = None, salt: "bytes | None" = None) -> None:
        super().__init__(salt)
        self.connection = connection
        self.remote_mints = 0
        self.local_mints = 0
        self.last_error: str | None = None

    def token_for_path(self, path: "str | Path") -> str:
        if path is None:
            raise ValueError("path is required")
        normalised = self._normalise(Path(str(path)))
        cached = self._path_to_token.get(str(normalised))
        if cached is not None:
            return cached
        token = self._remote_token(normalised)
        if token is not None:
            self.remote_mints += 1
            return self.adopt(token, normalised)
        self.local_mints += 1
        return super().token_for_path(normalised)

    def _remote_token(self, path: Path) -> "str | None":
        """Ask the plugin for the token; None when it cannot answer."""
        connection = self.connection
        if connection is None:
            return None
        minter = getattr(connection, "pseudonymise_paths", None)
        if minter is None:
            return None
        try:
            response = minter([str(path)])
        except Exception as exc:
            self.last_error = str(exc)
            return None
        result = response.get("result", response) if isinstance(response, dict) else {}
        for mapping in (result or {}).get("mappings") or []:
            token = mapping.get("token")
            if token:
                return str(token)
        return None
