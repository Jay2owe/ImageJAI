"""Optional local helpers loaded by ``imagej-use-auto`` from this workspace.

This module receives a read-only ``core`` mapping. Exported names must not
shadow preloaded core helpers such as ``session``, ``get_state``, or
``run_macro``.
"""


def require_open_image():
    """Return state or raise a clear error when Fiji has no image open."""
    response = core["get_state"]()
    result = response.get("result", {}) if isinstance(response, dict) else {}
    if (not isinstance(response, dict) or not response.get("ok")
            or not result.get("activeImage")):
        raise RuntimeError("No image is open in Fiji")
    return response


__all__ = ["require_open_image"]
