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

Menu-command expansion dry run:

```bash
python tools/phrasebook_build.py --menu-dump tools/menu-commands.txt --provider mock --dry-run | python -c "import json,sys; data=json.load(sys.stdin); wanted=sum(1 for line in open('tools/menu-commands.txt', encoding='utf-8') if line.strip()); got=sum(1 for row in data['intents'] if row['id'].startswith('menu.')); assert got == wanted, (got, wanted); print(f'menu intents: {got}')"
```

`tools/menu-commands.txt` is the canonical build-time input for menu
phrase generation. `agent/scan_plugins.py` is a legacy agent-side scanner,
not the Java Local Assistant source.
