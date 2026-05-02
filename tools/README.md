# Local Assistant Phrasebook Tool

`phrasebook_build.py` is a dev-time compiler for the baked Local Assistant phrasebook. The shipped plugin does not call an LLM at runtime.

Offline dry run:

```bash
python tools/phrasebook_build.py --dry-run --provider mock --intent image.pixel_size
```

With `--intent`, dry-run prints the normalised phrasings one per line. Without `--intent`, dry-run prints the generated phrasebook JSON.

Real providers require an API key and fail before any network call if it is missing:

```bash
set ANTHROPIC_API_KEY=...
python tools/phrasebook_build.py --provider claude --model claude-haiku-4-5

set OPENAI_API_KEY=...
python tools/phrasebook_build.py --provider openai --model gpt-5-mini
```

Use `--output <path>` for temporary verification. Do not write mock output over `src/main/resources/phrasebook.json`; stage 04 keeps the stage-03 starter phrasebook intact.

Schema check against the Java loader:

```bash
python tools/phrasebook_build.py --provider mock --output %TEMP%\phrasebook.json --java-load-check
```
