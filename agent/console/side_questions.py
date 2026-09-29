"""Independent no-Fiji-tools conversations for /btw side questions."""
from __future__ import annotations
from .agent_loop import create_agent, AbortFlag
import time

class SideThoughtBuffer:
    """Batch visible/saved thinking so token deltas do not each fsync a journal."""
    def __init__(self, emit):
        self.emit, self.parts, self.chars, self.last = emit, [], 0, time.monotonic()
    def push(self, text):
        self.parts.append(text)
        self.chars += len(text)
        if self.chars >= 8192 or (self.chars >= 512 and time.monotonic() - self.last >= .2):
            self.flush()
    def flush(self):
        if self.parts:
            text = "".join(self.parts)
            self.parts, self.chars, self.last = [], 0, time.monotonic()
            self.emit(text)

SIDE_PROMPT = (
    "Answer this side question concisely. This is a separate conversation, "
    "not permission to continue or change the main image analysis. No Fiji "
    "actions, host code, shell commands or external tools are available. "
    "If the request requires an action, explain that it belongs in the main chat."
)

def build_side_agent(source, api_key=None, factory=create_agent):
    agent = factory(source.provider, source.model, api_key=api_key,
                    effort=getattr(source, "effort", "default"))
    agent.tools = []
    agent.host_code_tools = frozenset()
    agent.fiji_connection = None
    agent.action_receipts = {}
    agent.abort = AbortFlag()
    agent.messages = []
    agent.external_session_id = None
    agent.resume_vendor_latest = False
    if hasattr(agent, "_base_system_prompt"):
        agent._base_system_prompt = SIDE_PROMPT
        agent.messages = [{"role": "system", "content": SIDE_PROMPT}]
    else:
        agent._instruction_tools = ()
        agent._tool_instructions = SIDE_PROMPT
    agent._harness_digest = agent._skill_catalog = agent._active_skill = ""
    agent._project_instructions = ""
    return agent
