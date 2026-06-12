from __future__ import annotations

import importlib.util
import sys

from agent.providers import to_openai_tool


def test_pep563_caller_module_annotations_resolve_to_primitive_types(tmp_path) -> None:
    module_path = tmp_path / "future_tools.py"
    module_path.write_text(
        "\n".join(
            [
                "from __future__ import annotations",
                "",
                "def future_tool(count: int, label: str, enabled: bool, ratio: float = 1.0) -> str:",
                "    \"\"\"Exercise postponed annotations.\"\"\"",
                "    return label",
                "",
            ]
        ),
        encoding="utf-8",
    )

    spec = importlib.util.spec_from_file_location("future_tools", module_path)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["future_tools"] = module
    assert spec.loader is not None
    spec.loader.exec_module(module)

    parameters = to_openai_tool(module.future_tool)["function"]["parameters"]
    assert parameters["properties"]["count"]["type"] == "integer"
    assert parameters["properties"]["label"]["type"] == "string"
    assert parameters["properties"]["enabled"]["type"] == "boolean"
    assert parameters["properties"]["ratio"]["type"] == "number"
    assert parameters["required"] == ["count", "label", "enabled"]
