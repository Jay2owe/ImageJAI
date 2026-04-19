# 2026-04-19 — `run("Laplacian")` and `run("Difference of Gaussians")` don't exist

Model: Gemma 4 31B (`gemma4:31b-cloud`) via Ollama.
Context loaded: `agent/gemma4_31b/GEMMA_CLAUDE.md` (post-commit `82c315c`).

User observation: Gemma keeps calling `run("Laplacian")` and
`run("Difference of Gaussians")` as if they were base-Fiji commands. They are
not — neither is registered unless a plugin (typically FeatureJ) is present,
and the recommended recipe for DoG is two Gaussian blurs + ImageCalculator
subtract.

---

## Applied — 2026-04-19

| # | Section | Change | File |
|---|---|---|---|
| F1 | §1 | new bullet in "Common mistakes the lint layer catches": name the non-existent commands, point at `FeatureJ Laplacian` (if installed) and the DoG-from-two-Gaussians recipe, and suggest a pre-flight `findstr` on `.tmp/commands.txt`. | `GEMMA_CLAUDE.md` |

<!-- F1: Gemma repeatedly invoked run("Laplacian") / run("Difference of Gaussians") / run("DoG") — none registered in base Fiji → plan §1, applied -->

**Outstanding action for the user**: close and reopen the Gemma terminal so `GEMMA_CLAUDE.md` reloads. No Java change this round — no Fiji restart needed.