import json
import pytest
from agent.console.sessions import SessionStore, sessions_dir
from agent.console.config import ConsoleConfig
from agent.console.evidence import EvidenceJournal, ArtifactStore
from agent.console.branching import check_cut

def test_branch_preserves_original_and_uses_fresh_vendor_history():
    store = SessionStore(ConsoleConfig())
    source = store.create("codex-subscription", "default")
    source.messages = [{"role":"user", "content":"count"}, {"role":"assistant", "content":"10 cells"}]
    source.external_session_id = "vendor-original"
    source.usage = [{"cost_usd":2}]
    journal = EvidenceJournal(sessions_dir(), source.id)
    journal.append("user", {"text":"count"})
    journal.append("assistant", {"text":"10 cells"})
    ref = ArtifactStore(sessions_dir(), source.id).save_text("full return", extension="txt")
    child = store.fork(source)
    child.messages[0]["content"] = "different"
    assert source.messages[0]["content"] == "count"
    assert child.parent_session_id == source.id and child.external_session_id is None
    assert child.usage == [] and not child.pending_turns and not child.background_jobs
    assert ArtifactStore(sessions_dir(), child.id).path_for(ref).read_text() == "full return"
    assert EvidenceJournal(sessions_dir(), child.id).read_events()

def test_partial_native_tool_pairs_and_invalid_cuts_are_rejected():
    rows = [{"role":"assistant","tool_calls":[{"id":"a"}]}, {"role":"tool", "tool_call_id":"a", "content":"10"}, {"role":"assistant","content":"done"}]
    with pytest.raises(ValueError): check_cut(rows, 1)
    with pytest.raises(ValueError): check_cut(rows, 2)
    check_cut(rows, 3)
    with pytest.raises(ValueError): check_cut(rows, 4)

@pytest.mark.parametrize("rows", [
    [{"role":"assistant","content":[{"type":"tool_use","id":"a","name":"read"}]},
     {"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"done"}]},
     {"role":"assistant","content":"done"}],
    [{"role":"assistant","content":"","function_calls":[{"name":"read"}]},
     {"role":"user","function_response":{"name":"read","response":{"result":"done"}}},
     {"role":"assistant","content":"done"}],
])
def test_all_native_histories_keep_complete_request_result_pairs(rows):
    with pytest.raises(ValueError): check_cut(rows,1)
    with pytest.raises(ValueError): check_cut(rows,2)
    check_cut(rows,3)
