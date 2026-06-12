# Local Assistant Phrasebook Tool

`phrasebook_build.py` is a dev-time compiler for the baked Local Assistant
phrasebook. The shipped plugin does not call an LLM at runtime — only the
developer runs this tool, periodically, to regenerate the phrasebook.

Providers shell out to a CLI the developer is already authenticated with
via subscription, so no API keys or per-call billing are involved.

## Default: Gemini CLI

```bash
python tools/phrasebook_build.py --provider gemini --model gemini-3.1-pro-preview
```

Prerequisites: `gemini --version` should print a version (the Gemini CLI is
on `PATH`).

## Other CLIs

Available for parity; require the corresponding CLI on `PATH`:

```bash
python tools/phrasebook_build.py --provider claude
python tools/phrasebook_build.py --provider codex
```

## Offline dry run (no CLI required)

```bash
python tools/phrasebook_build.py --dry-run --provider mock --intent image.pixel_size
```

With `--intent`, dry-run prints the normalised phrasings one per line.
Without `--intent`, dry-run prints the generated phrasebook JSON.

## One-intent regeneration

```bash
python tools/phrasebook_build.py --provider gemini --intent image.pixel_size
```

Merges the regenerated intent into the existing `src/main/resources/phrasebook.json`,
preserving every other intent. Useful for fixing a single bad intent without
re-billing the entire compile.

## Schema check against the Java loader

```bash
python tools/phrasebook_build.py --provider mock --output %TEMP%\phrasebook.json --java-load-check
```

## Menu-command expansion

`tools/menu-commands.txt` is the canonical build-time input for menu phrase
generation. Keep it updated by dumping `ij.Menus.getCommands()` keys from a
fresh Fiji install:

```bash
# from inside Fiji's macro recorder, with the plugin loaded:
print(Menus.getCommands().keySet().toArray().join("\n"))
```

Then regenerate menu phrasings:

```bash
python tools/phrasebook_build.py --provider gemini --menu-dump tools/menu-commands.txt --keep
```

`--keep` preserves all hand-curated intents; only `menu.*` IDs that are not
already in the phrasebook get LLM-distilled phrasings.

`agent/scan_plugins.py` is a legacy agent-side scanner, not the Java Local
Assistant source.
