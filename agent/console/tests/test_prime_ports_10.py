from datetime import datetime, timedelta, timezone
import pytest
from agent.console.harness import HarnessStore, HarnessValidationError, HarnessConflictError
from agent.console.memory_review import review_rows, renew
from agent.console.tests.test_prime_ports_09 import frozen
from agent.console.validation import run_validation, save_receipt
from agent.console.memory_review import attach_validation

def test_expiry_conflict_review_and_optimistic_renewal(tmp_path):
    store = HarnessStore(tmp_path / "state.json", tmp_path / "events.jsonl")
    entry = store.propose(kind="fact", scope="user", title="Calibration", content="Use verified metadata", conflicts=["other"], expires_at=datetime.now(timezone.utc)-timedelta(days=1))
    rows = review_rows([("user",store)])
    assert rows[0]["reasons"] == ["Expired", "Conflicting knowledge"]
    with pytest.raises(ValueError): renew(store, entry, days=30, evidence="")
    updated = renew(store, entry, days=30, evidence="Rechecked metadata")
    assert updated.version == 2 and updated.status == "candidate"
    assert review_rows([("user",store)])[0]["reasons"] == ["Conflicting knowledge"]
    with pytest.raises(HarnessConflictError): renew(store, entry, days=30, evidence="Stale review")

def test_procedure_cannot_be_scientifically_promoted_without_validation(tmp_path):
    store = HarnessStore(tmp_path / "state.json", tmp_path / "events.jsonl")
    entry = store.propose(kind="procedure",scope="project",title="Counting",content="Count in calibrated images")
    for _ in range(2):
        entry = store.promote(entry.id, expected_version=entry.version, reviewer="user", evidence="Reviewed")
    with pytest.raises(HarnessValidationError, match="frozen procedure"):
        store.promote(entry.id, expected_version=entry.version, reviewer="user", evidence="Looks fine")

def test_complete_frozen_evidence_allows_human_promotion_and_changes_invalidate(frozen):
    store = HarnessStore(frozen.path.parent / "state.json", frozen.path.parent / "events.jsonl")
    entry = store.propose(kind="procedure",scope="project",title="Counting",content=frozen.macro)
    for _ in range(2):
        entry = store.promote(entry.id, expected_version=entry.version, reviewer="user", evidence="Reviewed")
    receipt = run_validation(frozen, lambda path,macro,case:{"count":10+int(case["id"])})
    path = save_receipt(frozen.path.parent,receipt)
    entry = attach_validation(store,entry,path)
    assert entry.status == "project_approved"
    promoted = store.promote(entry.id,expected_version=entry.version,reviewer="user",evidence="Reviewed all test counts")
    assert promoted.status == "validated"
    edited = store.update(promoted.id,expected_version=promoted.version,content="changed procedure")
    assert edited.status == "candidate" and not store.retrieve(allowed_statuses=("session_confirmed", "project_approved", "validated"))
    assert "Procedure changed; validation needs review" in review_rows([("project",store)])[0]["reasons"]
    from agent.console.memory_review import require_current_validation
    with pytest.raises(ValueError): require_current_validation(edited)

def test_forged_pass_flag_does_not_override_wrong_measurements(frozen):
    store = HarnessStore(frozen.path.parent / "state.json", frozen.path.parent / "events.jsonl")
    entry = store.propose(kind="procedure",scope="project",title="Counting",content=frozen.macro)
    receipt = run_validation(frozen, lambda *args:{"count":0})
    receipt["passed"] = True
    for row in receipt["cases"]:
        row["passed"] = True
        for check in row["checks"]: check["passed"] = True
    with pytest.raises(ValueError): attach_validation(store,entry,save_receipt(frozen.path.parent,receipt))
