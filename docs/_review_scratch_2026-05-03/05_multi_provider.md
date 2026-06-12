# Multi-Provider Code Review — 2026-05-03

**Scope:** Phases C–H (commits 0f58dea through dd95678)
**Status:** 13 findings (4 BUG, 4 ISSUE, 5 INEFFICIENCY)

## Findings

### BUG — High Severity

**[BUG]** `src/main/java/imagejai/engine/picker/ProviderRegistry.java:186-189` — Provider status hardcoded to NEEDS_SETUP even when models exist

- Lines 186–189 show that when a provider HAS curated models (list is not empty), the status is still assigned NEEDS_SETUP. The conditional logic is broken: both the empty-list case (line 187) and the non-empty-list case (line 189) assign the same status.
- This causes all configured providers with curated models to display ⚠ (needs setup) in the dropdown instead of ✓ (ready), contradicting Phase D's acceptance criteria.
- Change line 189 to `status = ProviderEntry.Status.READY;` to mark providers with models as READY.
- **Phase D**

---

**[BUG]** `src/main/java/imagejai/config/Settings.java` — No migration from selectedAgentName to selectedProvider/selectedModelId on flag flip

- The `migrateIfNeeded()` method does not migrate users' existing `selectedAgentName` to the new multi-provider fields (`selectedProvider` + `selectedModelId`).
- Per Phase D spec, when a user flips the flag on, there should be seamless resolution to the right provider/model pair. Today, the flag flip leaves both fields null, breaking the "re-launch last" button.
- Add migration logic in `migrateIfNeeded()` that maps known legacy agent names to provider/model pairs (e.g., `claude_agent` → `{anthropic, claude-sonnet-4-6}`; `gemma4_31b_agent` → `{ollama, gemma4:31b-cloud}`).
- **Phase D**

---

**[BUG]** `agent/contexts/loader.py:58-61` — Missing family overlay files silently skipped, no warning

- Models with `family: gemini` and `family: other` will have incomplete context because the corresponding `.md` files do not exist on disk.
- The loader silently skips missing files with no log entry or warning. Silently dropping context fragments is a correctness issue.
- (1) Create the missing files (`gemini.md`, `other.md`); (2) add a log warning in the loader if a referenced family file is missing.
- **Phase F**

---

**[BUG]** `src/main/java/imagejai/ui/picker/ProviderTierGate.java:95-103` — "Don't ask again" logic only checks provider, not individual model

- The `shouldShowPaid()` method adds `entry.providerId()` to `paidDontAsk`, suppressing the paid dialog for ALL models from that provider.
- Scenario: user picks `claude-opus-4-7` (paid), ticks "don't ask again". Next click on `claude-haiku-4-5` (same provider) — dialog does NOT fire. This violates the user's intent to suppress "this dialog", not "all dialogs for this provider".
- Change suppression key from `providerId` to `providerId + modelId` for finer-grained control.
- **Phase H**

---

### ISSUE — Medium Severity

**[ISSUE]** `src/main/java/imagejai/engine/picker/AgentLaunchOrchestrator.java:75-93` — Missing error handling for launcher failures; null returns silently propagate

- The `launch()` method returns `null` from skeleton launchers. Callers in `AiRootPanel` do not check for null, so failures are silent.
- When real implementations land, network errors or invalid model IDs will fail invisibly.
- Add a null check in `AiRootPanel` after calling `launch()` — if null, surface a dialog with the underlying error.
- **Phase D**

---

**[ISSUE]** `src/main/java/imagejai/ui/picker/FirstUseDialog.java:70-78` — Dialog modality race condition on result field

- The `result` field is not volatile. The window-close event (line 74–78) and button-click handlers (line 122–128) both write to it without synchronization.
- Although unlikely in practice, a stale result is theoretically possible.
- Make `result` volatile, or use `CountDownLatch` / `CompletableFuture` for thread-safe result handshake.
- **Phase H**

---

**[ISSUE]** `agent/providers/models.yaml` — Gemini and other models missing family overlay files

- 11+ Gemini models list `family: gemini` with `curated: true`, but `agent/contexts/families/gemini.md` does not exist.
- Users who pick these models receive incomplete context — family-specific tuning is lost.
- Create `agent/contexts/families/gemini.md` and `agent/contexts/families/other.md`, or remove the `family:` field from models with no overlay.
- **Phase F**

---

**[ISSUE]** `src/main/java/imagejai/ui/AiRootPanel.java:493-501` — Re-launch button enabled for incomplete launchers

- The ▶ button enables if the model is in the registry, regardless of whether the launcher is ready.
- In Phase D, skeleton launchers (Proxy, Native) return null and fail silently. The button should remain disabled for non-Ollama models until their launchers are fully implemented.
- Check the transport type and only enable for READY transports. Disable proxy/native with a "launcher not ready" tooltip in Phase D.
- **Phase D**

---

### INEFFICIENCY

**[INEFFICIENCY]** `src/main/java/imagejai/ui/picker/ModelPickerButton.java` — Hover-card lifecycle may create new JWindow on every hover

- Per the spec, the hover-card is a singleton JWindow. If the implementation creates one per hover, the UI will flicker, lag, and leak resources.
- Verify the JWindow is created once in the constructor and reused across all model rows. Check for `new JWindow()` inside mouse handlers.
- **Phase D**

---

**[INEFFICIENCY]** `agent/providers/models.yaml` — Hardcoded entries with no auto-discovery in Phase D

- Phase D uses static YAML. This is intentional per the plan, but testing Phase D in isolation is awkward — users with only Groq credentials see no Groq models until Phase G.
- Consider adding a Phase D test fixture with live `/models` data, or a script that patches the YAML for manual testing.
- **Phase D**

---

**[INEFFICIENCY]** `src/main/java/imagejai/config/Settings.java:113` — Flag flip default changed to true with minimal documentation

- Line 113: `useMultiProviderPicker = true` (default flipped in Phase H). The Settings class doesn't explain where to find the opt-out.
- Add a docstring near line 113 explaining the flip and pointing to the Settings UI opt-out.
- **Phase H**

---

**[INEFFICIENCY]** Phase D — No explicit test criteria for flag-off regression

- The plan says the "existing Ollama path" must work, but there's no test verifying the old `JComboBox` path functions side-by-side with the new picker.
- Add a unit test in Phase D verifying the old launcher path operates without regression when `useMultiProviderPicker=false`.
- **Phase D**

---

**[INEFFICIENCY]** `src/main/java/imagejai/ui/picker/FirstUseDialog.java:156-193` — HTML building is fragile, no attribute escaping

- The HTML is built by string concatenation. The `escape()` function handles `&<>` but not quotes. If a display name contains `"`, it could break HTML attributes.
- Replace HTML string building with a proper templating library or JTextPane (which handles HTML safely).
- **Phase H**

---

**[INEFFICIENCY]** `src/main/java/imagejai/engine/picker/NativeAgentLauncher.java:38-47` — Duplicate log statements, confusing debug output

- Lines 38–47 log "[NativeAgentLauncher] not yet wired" twice (once line 41, once line 44). Both messages are identical.
- Simplify: log once per branch (no features vs. features present).
- **Phase D**

---

## Summary

- **BUG:** 4 findings
- **ISSUE:** 4 findings
- **INEFFICIENCY:** 5 findings

**Critical blockers before Phase H ships:**
1. Fix ProviderRegistry status assignment (line 189)
2. Add Settings migration for selectedAgentName → selectedProvider/selectedModelId
3. Create missing family overlay files (gemini.md, other.md)
