"""List every button declaration, keyboard binding and console slash command."""
import argparse
import ast
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def inventory():
    buttons, bindings = [], []
    for path in sorted((ROOT / "agent/console").glob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for cls in [node for node in tree.body if isinstance(node, ast.ClassDef)]:
            for node in ast.walk(cls):
                if not isinstance(node, ast.Call):
                    continue
                name = ast.unparse(node.func)
                if name == "Button":
                    identifier = next((kw.value for kw in node.keywords if kw.arg == "id"), None)
                    buttons.append({"file": str(path.relative_to(ROOT)), "screen": cls.name,
                        "line": node.lineno, "id": ast.literal_eval(identifier) if isinstance(identifier, ast.Constant) else ast.unparse(identifier) if identifier else None,
                        "label": ast.unparse(node.args[0]) if node.args else ""})
                elif name == "Binding" and len(node.args) >= 2:
                    bindings.append({"screen": cls.name, "key": ast.literal_eval(node.args[0]),
                                     "action": ast.literal_eval(node.args[1])})
    # Reading only declaration data: no App, credentials, network or Fiji starts.
    from agent.console.tui import SLASH_COMMANDS
    from agent.console.rail import rail_items
    return {"buttons": buttons, "bindings": bindings,
            "slash_commands": [{"command": c, "description": d} for c, d in SLASH_COMMANDS],
            "rail_actions": [{"id": item.id, "label": item.label} for item in rail_items()]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = inventory()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(json.dumps({key: len(value) for key, value in output.items()}))
