"""Session-local tools available through every model's shared action wrapper."""
from __future__ import annotations


def attach_runtime_tools(agent, *, workspace=None, skill_loader=None):
    agent.tools = list(getattr(agent, "tools", ()))
    agent.host_code_tools = frozenset(getattr(agent, "host_code_tools", ()))
    if workspace is not None:
        def python_cell(code: str, timeout: int = 30) -> dict:
            """Run an approved numerical Python cell in this chat's persistent scratchpad.

            Args:
                code: Python using np, math, optional scipy, and pixels(x,y,width,height).
                timeout: Maximum execution seconds, between 1 and 120.
            """
            workspace.connection = agent.fiji_connection
            return workspace.execute(code, timeout, agent.abort)
        agent.tools = [fn for fn in agent.tools if fn.__name__ != "python_cell"] + [python_cell]
        agent.host_code_tools = frozenset(agent.host_code_tools) | {"python_cell"}
    if skill_loader is not None:
        def load_skill(name: str) -> dict:
            """Load eligible workflow guidance without executing its recipe.

            Args:
                name: Exact skill name from the skill catalog.
            """
            return {"guidance": skill_loader(name)}
        agent.tools = [fn for fn in agent.tools if fn.__name__ != "load_skill"] + [load_skill]
    # API wrappers cache their textual tool catalog in the base prompt.
    if hasattr(agent, "_base_system_prompt"):
        from .wrapped_tools import instructions
        from .agent_loop import _system_prompt
        agent._base_system_prompt = _system_prompt(agent.provider, agent.model) + "\n\n" + instructions(agent)
        agent._rebuild_system_message()
