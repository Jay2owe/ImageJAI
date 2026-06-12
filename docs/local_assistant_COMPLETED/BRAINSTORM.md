# Local Assistant — Brainstorm and Decisions

Synthesis of a 4-agent research brainstorm on **how to make the Local
Assistant match as many user requests as possible while staying
cheap** — followed by the design decisions taken from it. The
decisions in §6 are the source of truth; the rest is the reasoning
trail for future readers.

## 1. The Landscape

The field has converged on a **two-stage pattern**: a deterministic
matcher (regex / keyword / synonym lookup) for the obvious cases,
then a **semantic matcher** (sentence embeddings + nearest-neighbour)
for the long tail. Every serious offline assistant uses some version
of this:

- **Snips NLU** (Sonos-acquired in 2019; library still on PyPI under
  Apache-2.0). Two-stage `LookupIntentParser` then
  `ProbabilisticIntentParser`. Java story: no first-party binding;
  Rust crate would need a JNI wrapper.
- **Mycroft Adapt + Padatious** (Mycroft AI shut down Feb 2023 after
  a patent-troll lawsuit; community fork OVOS maintains both).
  Python only.
- **Picovoice Rhino** has a real Java SDK and exactly the grammar
  approach we want, but the licence is incompatible with
  redistribution as part of a free Fiji plugin.
- **Rasa NLU / DIET** is heavyweight Python; not embeddable in a
  JVM.
- **VS Code / Slack / fzf-style command palettes** explicitly trade
  recall for stability. They fail on the synonym problem by design —
  biologists who do not know a command is called "Properties" will
  never find pixel-size that way.

The 2024–25 best-evidence approach for ~50 intents with ~5 examples
each is **LLM-distilled training data + tiny embedding classifier**.
PAG-LLM (SIGIR 2024), Springer 2025, and Tandfonline 2024 all show
the same pattern: prompt an LLM **once at dev time** to generate
20–50 paraphrases per intent, ship the static result. ACL 2022:
absolute cosine thresholds do not generalise across domains; use
top-k with a margin.

## 2. Already in the Codebase (we missed this in the first plan)

`src/main/java/imagejai/engine/IntentRouter.java` plus
four TCP commands: `intent`, `intent_teach`, `intent_list`,
`intent_forget`. It is a regex → macro router with hot-reload, mtime
watch, atomic writes, quarantine on bad regexes, and per-mapping hit
telemetry. It persists to `~/.imagej-ai/intent_mappings.json`.

**Decision:** the Local Assistant layers on top of `IntentRouter`,
not in place of it. The curated v1 library lives in a new
`imagejai.local.IntentLibrary`. `IntentRouter` becomes the
user-taught extension layer, surfaced through a `/teach` slash
command in the chat.

`FrictionLog` (in-memory ring buffer of failed TCP responses) is the
natural place to record missed phrases. Caveat: it is not persistent;
cross-session phrase collection needs either a separate JSONL writer
or a persistence layer added to `FrictionLog`.

## 3. Technical Approaches (JVM-friendly)

| Approach | Size | Latency | Accuracy ceiling | Verdict |
|---|---|---|---|---|
| Regex + Snowball stemming + Apache Commons fuzzy | 5–10 MB | <1 ms | 60–75 % | Always-on stage 1 |
| Lucene BM25 over phrasings (`MemoryIndex`) | ~15 MB | <5 ms | 80–88 % | **Skipped** — see §6 |
| all-MiniLM-L6-v2 (INT8) via ONNX Runtime Java | ~50–150 MB | 20–50 ms | 88–95 % | Optional, opt-in download |
| DJL with PyTorch backend | ~400–700 MB | 15–50 ms | 88–95 % | Skip — same accuracy, 4× the bundle |
| llama.cpp via java-llama.cpp + Qwen2.5-0.5B-Q4 | ~400 MB | 200–1500 ms | 90–97 % | Skip — runtime LLM ruled out |
| Python sidecar via existing CrossToolRunner | 0 JVM / 500 MB+ Python | 20–60 ms | 88–95 % | Skip — conflicts with "drop the JAR in plugins/" |

## 4. Ideas (Ranked)

### Tier 1 — Build first

1. **Phrasebook + fuzzy autocomplete.** Hash-lookup compiled
   phrasebook (LLM-distilled at dev time, ~50 phrasings × ~50
   intents) plus Jaro-Winkler fuzzy match for typos and the
   autocomplete chip row.
2. **LLM-distilled phrasebook compiler.** A dev-side
   `tools/phrasebook_build.py` calls Claude/GPT once per release
   with "give me 50 biologist phrasings for this intent". Output
   `resources/phrasebook.json` baked into the JAR. **Developer pays
   the LLM cost once; users pay zero.**
3. **Wire into existing `IntentRouter`.** `LocalAssistant.handle()`
   consults `IntentLibrary` first, falls back to
   `IntentRouter.resolve()`, then misses. Reuses the persistence,
   hot-reload, telemetry, and `/teach` UX that already ships.
4. **Macro recorder as intent backbone.** Mine
   `Menus.getCommands()` (the codebase already does this in
   `agent/scan_plugins.py`) to auto-generate intent stubs for every
   menu command in the user's actual Fiji install — third-party
   plugins included. Run the phrasebook compiler over each command
   name. Coverage scales to all of Fiji with no per-plugin work.
5. **Benchmark file.** `tests/benchmark/biologist_phrasings.jsonl`
   built from recipe descriptions, `practice.py` task names, and
   eventually FrictionLog misses. Every commit reports top-1 / top-3
   accuracy and confusion matrix. Without this, every other change
   is steering blind. The repo has zero real biologist transcripts
   today, so collecting them is itself a deliverable.

### Tier 2 — Build next

6. **Disambiguation chips.** When top-1 and top-2 are within 0.05,
   render three clickable suggestions. Click → run + log as positive
   training example. Failure becomes a learning signal.
7. **Slot-filling mini-parser.** Each intent declares typed slots
   (number, path, ROI index, channel). Lets "set threshold to 5000
   on the green channel" parse cleanly.
8. **FrictionLog → phrase log → library growth.** Misses write to
   `agent/local_log.jsonl`. A "improve from my chat history" button
   either appends user-corrected phrasings or sends the unresolved
   batch to a one-shot LLM call **with explicit user consent**.
9. **Conversational memory.** Track "last image", "last ROI", "last
   threshold", "last macro". Resolve "do that again", "measure it",
   "the next image".

### Tier 3 — Stretch

10. **Teach mode.** "Teach me this" button: user types a phrasing,
    demonstrates the action manually while the macro recorder
    captures it, assistant saves the (phrase, macro) pair as a
    learned intent via `IntentRouter`.
11. **Tiny LLM as reranker.** When top-3 candidates are within
    margin, ask a Qwen2.5-0.5B-Q4 (~400 MB, opt-in) "which of these
    matches: [user query]?" — sub-second; only fires on ambiguity.

### Dropped

- **Local Whisper voice input.** Out of scope for v1.
- **Runtime LLM fallback** (Gemma/Qwen via Ollama on miss). Cut.
  Misses go to "did you mean" chips instead.
- **Opaque embedding-only architectures.** Antonym confusion (small
  models cluster "open" near "close") combined with the lack of a
  cheap deterministic floor makes them fragile.

## 5. Antonym confusion, plain English

Small embedding models think two phrases are "close" if they tend to
appear in similar sentences. "Open the image" and "close the image"
both look like *commands telling the system to do something to an
image*, so the model files them next to each other in its mental
map — even though they mean opposite things.

**Example.** User types `close all`. Model embeds it, looks for the
nearest intent vector, finds `open all images` is closer than `close
all images` (by 0.01), runs the wrong macro. Three windows pop open
instead of closing.

**Metaphor.** It is a librarian who shelves books by topic-of-conver-
sation rather than by what the book says. *How to bake a cake* and
*How to NOT bake a cake* go on the same shelf because they are both
cake-talk.

The chosen architecture (phrasebook + fuzzy match on literal
characters) sidesteps the antonym risk entirely. Embeddings are
opt-in; if a user enables them, the deterministic stage still runs
first and short-circuits "close all" before the embedding stage gets
a chance to mis-rank it.

## 6. Decisions Taken

These are the choices that drive `PLAN.md`.

1. **Two-tier matcher only.** Phrasebook hash-lookup, then Jaro-
   Winkler fuzzy match against the same phrasebook. No BM25, no
   Lucene index, no runtime LLM. ~5 MB total, <5 ms p99. The
   embedding tier (`all-MiniLM-L6-v2` via ONNX) is **opt-in and
   downloaded on demand**.
2. **No runtime LLM, ever.** LLMs are used **only at dev time** to
   generate the phrasebook via `tools/phrasebook_build.py`. The
   shipped plugin makes zero outbound LLM calls.
3. **Fuzzy match doubles as autocomplete.** As the user types, the
   same Jaro-Winkler index renders the top-3 candidate phrasings as
   chips below the input. Click or tab-to-accept fills the chat
   input. Most users converge on a known phrase before hitting
   enter, which is why two tiers are enough.
4. **Layer onto `IntentRouter`.** Match order:
   `IntentLibrary` (built-in) → `IntentRouter.resolve()` (user-
   taught) → "I don't recognise that" with top-3 fuzzy chips.
5. **Models & Agents installer panel** in Local Assistant settings.
   One row per agent, with per-row `[Install]` / `[Download]`
   buttons. Local Assistant works with no install; the semantic
   boost (~80 MB MiniLM) is a one-click download. External CLI
   agents (Claude Code, Codex, Ollama) get one-click installs that
   shell out to `npm i -g <pkg>` or the Ollama installer. **Ollama
   models are cloud-served**, so only the Ollama CLI itself needs
   installing — no multi-GB local model pulls.
6. **GSD detection for Claude.** On launching Claude Code, check
   whether GSD is installed. If yes, spawn with
   `--dangerously-skip-permissions` (faster, unlocks full
   potential). If no, show a one-time prompt: *"Install GSD to skip
   permission prompts and run Claude at full speed? [Install]
   [Skip]"*. **GSD is not safer; it is faster.**
7. **`IntentRouter` mappings are visible** to the chat user. List
   them via `/intents`; teach via `/teach <phrase> => <macro>`;
   forget via `/forget <id>`.
8. **`FrictionLog` captures Local Assistant misses** for telemetry
   and library growth. Persistence layer to be added since the
   current ring buffer is in-memory only.
9. **No fire-and-verify gap.** Local Assistant is scoped to **simple
   task automation, not complex analysis**. The user verifies
   results visually. Capture+LLM-Read style verification is the
   external-agent path, not the Local Assistant path.
10. **Slash commands ported from gemma4_31b**: `/help`, `/clear`
    only. Not porting `/save-recipe`, `/interrupt`, `/think`,
    `/mode`, `/ccommands`, `/queue` (LLM-loop specific or out of
    scope).
11. **New slash commands**: `/macros` (list saved Fiji macros + ones
    learned during the session), `/info` (table of all open images),
    `/close` (active / all / all-but-active / by substring),
    `/teach`, `/intents`, `/forget`. Natural-language equivalents
    work too — "run my split images macro" matches against macro
    filenames.

## 7. Risks Still Open

- **Phrasebook regeneration cadence.** Manual `tools/phrasebook_-
  build.py` per release works, but no scheduled refresh from missed
  queries — would need a privacy-respecting opt-in flow.
- **`FrictionLog` persistence.** Current implementation is in-memory
  only; cross-session learning needs a JSONL writer or a real
  persistence layer.
- **Macro-recorder backbone scaling.** Mining `Menus.getCommands()`
  yields ~400+ commands; phrasebook compiler must run against all
  of them at build time (cost the developer pays once).
- **Disambiguation UI clutter.** Chips are useful when correctly
  calibrated; annoying when shown too often. Threshold tuning needs
  the benchmark file (decision §6.5/6.8).
- **Intent ID collisions** between built-in `IntentLibrary` and
  user-taught `IntentRouter` mappings. Resolve by namespacing
  (`builtin.image.pixel_size` vs `user.foo`).

## 8. Sources

Web sources cited by the four research agents; full URLs in the raw
agent transcripts:

- Snips NLU Medium intro; Snips Voice Platform paper (arXiv
  1805.10190).
- Mycroft Adapt and Padatious docs; OVOS Padatious plugin.
- Picovoice Rhino Java API and pricing.
- Rasa DIET classifier blog and docs.
- VS Code command palette fuzzy-match issue (microsoft/vscode#1964).
- Sentence Transformers in Java with ONNX (Medium); DJL sentence-
  encoding demo; Spring AI ONNX embeddings.
- 2025 embedding leaderboard guides (BentoML, ailog.fr).
- Centroid intent classification (Marc Puig, Medium); Kong AI
  Gateway semantic-similarity threshold guidance.
- ACL 2022: problems with cosine similarity on embeddings.
- RapidFuzz; RapidFuzz vs FuzzyWuzzy.
- LLM utterance augmentation: Springer 2025; PAG-LLM (arXiv
  2406.17163); Tandfonline 2024; Text Data Augmentation survey 2025
  (arXiv 2501.18845); Google Research EMNLP 2025 small-models post.
- ImageJ Macro Language reference; ImageJ User Guide macros chapter.
- Maven Central artefacts: `com.microsoft.onnxruntime:onnxruntime`,
  Apache Commons Text similarity, Lucene `BM25Similarity`.
- Java bindings: `kherud/java-llama.cpp`.

In-repo sources:
- `src/main/java/imagejai/engine/AgentLauncher.java`
- `src/main/java/imagejai/ui/ChatPanel.java`
- `src/main/java/imagejai/ConversationLoop.java`
- `src/main/java/imagejai/engine/TCPCommandServer.java`
- `src/main/java/imagejai/engine/IntentRouter.java`
- `src/main/java/imagejai/engine/FrictionLog.java`
- `src/main/java/imagejai/engine/Settings.java`
- `agent/recipes/` (27 YAML workflows)
- `agent/references/macro-reference.md`
- `agent/practice.py` (TASKS list)
- `agent/scan_plugins.py`
- `agent/gemma4_31b/loop.py` (slash command implementations)
- `docs/agentconsole_makeover_COMPLETED/06_friction_log.md`
