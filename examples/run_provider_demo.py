"""Manual demo for the ImageJAI provider clients.

Examples:
  python examples/run_provider_demo.py --provider openai --model gpt-5-mini
  python examples/run_provider_demo.py --provider anthropic --model claude-sonnet-4-6
  python examples/run_provider_demo.py --provider gemini --model gemini-2.5-flash

Proxy providers require the LiteLLM Proxy from Phase A to be running.
Native providers read their normal SDK environment variables.
"""
from __future__ import annotations

import argparse
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agent.providers import PROVIDER_KEYS, ToolCall, router  # noqa: E402


def multiply(a: int, b: int) -> int:
    """Multiply two integers.

    Args:
        a: First integer.
        b: Second integer.
    """
    return a * b


TOOLS: list[Callable[..., Any]] = [multiply]
TOOL_MAP = {tool.__name__: tool for tool in TOOLS}


def main() -> int:
    args = parse_args()
    client = router.get_client(args.provider, args.model, base_url=args.base_url)
    messages: list[dict[str, Any]] = [{"role": "user", "content": args.prompt}]

    for _ in range(args.max_rounds):
        response = client.chat(
            messages,
            TOOLS,
            args.model,
            temperature=args.temperature,
        )
        client.append_assistant(messages, response)
        calls = client.extract_tool_calls(response)
        if not calls:
            print(client.extract_text(response))
            return 0
        for call in calls:
            result = dispatch(call)
            client.append_tool_result(messages, call, result)

    print(f"Stopped after {args.max_rounds} tool rounds without a final answer.", file=sys.stderr)
    return 2


def dispatch(call: ToolCall) -> str:
    if call.error:
        return f"ERROR: {call.error}"
    tool = TOOL_MAP.get(call.name)
    if tool is None:
        return f"ERROR: unknown tool '{call.name}'"
    try:
        return str(tool(**call.args))
    except Exception as exc:
        return f"ERROR: tool '{call.name}' failed: {exc}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a provider client against a multiply tool.")
    parser.add_argument("--provider", required=True, choices=PROVIDER_KEYS)
    parser.add_argument("--model", required=True)
    parser.add_argument(
        "--prompt",
        default="Use the multiply tool to calculate 6 * 7, then reply with the number.",
    )
    parser.add_argument("--base-url", default="http://localhost:4000")
    parser.add_argument("--temperature", type=float, default=0.0)
    parser.add_argument("--max-rounds", type=int, default=4)
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(main())
