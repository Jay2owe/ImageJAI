#!/usr/bin/env python3
"""Build the Local Assistant phrasebook.

This is a dev-time tool. The shipped plugin makes zero LLM calls at runtime;
the developer regenerates the phrasebook periodically by running this tool.

Providers shell out to a CLI that the developer is already authenticated with
via subscription, so no API keys or per-call billing are involved:

  - gemini: shells out to `gemini -p <prompt> -m <model> -o json -y` (default)
  - claude: shells out to `claude -p <prompt>`
  - codex:  shells out to `codex exec <prompt>`
  - mock:   deterministic, offline, for unit tests

Default model for `gemini` is `gemini-3.1-pro-preview`.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

try:
    import yaml
except ImportError as exc:  # pragma: no cover - exercised on dev machines only.
    raise SystemExit("Missing dependency: pyyaml. Run: python -m pip install -r tools/requirements.txt") from exc


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INTENTS = ROOT / "tools" / "intents.yaml"
DEFAULT_PHRASEBOOK = ROOT / "src" / "main" / "resources" / "phrasebook.json"
PROMPT = """\
Generate 50 plain-English phrasings a biologist might type to ask for **{description}** in a Fiji image-analysis chat.
Include typos, abbreviations, partial phrases, and questions disguised as commands.
Output one phrasing per line, no numbering, no markdown.

Two examples to anchor the style: {seeds}
"""

PUNCT = re.compile(r"[^a-z0-9 ]")
WS = re.compile(r"\s+")


def normalise(text: str) -> str:
    """Mirror imagejai.local.IntentMatcher.normalise."""
    text = text.lower()
    text = PUNCT.sub(" ", text)
    return WS.sub(" ", text).strip()


def slugify(text: str) -> str:
    """Mirror imagejai.local.MenuIntentImporter.slugify."""
    slug = re.sub(r"[^a-z0-9]+", ".", text.lower())
    return slug.strip(".")


@dataclass(frozen=True)
class IntentDef:
    id: str
    description: str
    seeds: Tuple[str, ...]
    slots: Tuple[dict, ...]


class LlmProvider:
    def generate(self, prompt: str, intent: IntentDef) -> str:
        raise NotImplementedError


class MockProvider(LlmProvider):
    """Deterministic offline generator for tests and CI."""

    TEMPLATES: Tuple[str, ...] = (
        "{desc}",
        "please {desc}",
        "can you {desc}",
        "could you {desc}",
        "would you {desc}",
        "i need to {desc}",
        "i want to {desc}",
        "how do i {desc}",
        "help me {desc}",
        "show me how to {desc}",
        "run {desc}",
        "do {desc}",
        "{desc} please",
        "{desc} now",
        "{desc} for this image",
        "{desc} on the active image",
        "{desc} in fiji",
        "{desc} in imagej",
        "quick {desc}",
        "just {desc}",
        "check {desc}",
        "get {desc}",
        "tell me {desc}",
        "what is {desc}",
        "what are {desc}",
        "show {desc}",
        "find {desc}",
        "report {desc}",
        "calculate {desc}",
        "compute {desc}",
        "{short}",
        "{short} please",
        "show {short}",
        "get {short}",
        "check {short}",
        "run {short}",
        "do {short}",
        "{id_words}",
        "{id_words} please",
        "show {id_words}",
        "get {id_words}",
        "check {id_words}",
        "run {id_words}",
        "do {id_words}",
        "{short}?",
        "{desc}?",
        "pls {short}",
        "plz {short}",
        "{short} for img",
        "{id_words} img",
        "need {id_words}",
        "can u {short}",
        "wht is {short}",
        "{short} now pls",
        "local assistant {id_words}",
    )

    def generate(self, prompt: str, intent: IntentDef) -> str:
        del prompt
        desc = intent.description
        short = desc
        for prefix in (
            "Report the active image's ",
            "Report the active image ",
            "Report ",
            "Show ",
            "List ",
            "Open ",
            "Create ",
            "Apply ",
            "Measure ",
            "Save ",
        ):
            if short.lower().startswith(prefix.lower()):
                short = short[len(prefix):]
                break
        short = short.rstrip(".")
        id_words = intent.id.replace(".", " ").replace("_", " ")

        lines: List[str] = list(intent.seeds)
        for template in self.TEMPLATES:
            lines.append(template.format(desc=desc, short=short, id_words=id_words))
        return "\n".join(lines[:58])


class CliProvider(LlmProvider):
    """Shell out to a developer-authenticated CLI (no API key required)."""

    name: str = ""
    default_model: Optional[str] = None
    timeout_seconds: int = 180

    def __init__(self, model: Optional[str]) -> None:
        resolved = shutil.which(self.name)
        if resolved is None:
            raise RuntimeError(f"`{self.name}` CLI not found on PATH for --provider {self.name}")
        # On Windows shutil.which honours PATHEXT and returns e.g. "gemini.cmd".
        # subprocess's CreateProcess does NOT do PATHEXT lookup, so we MUST pass
        # the resolved full path with extension as argv[0].
        self.executable = resolved
        self.model = model or self.default_model

    def build_command(self, prompt: str) -> Tuple[List[str], Optional[str]]:
        """Return (argv, stdin_text). stdin_text=None means no stdin piped.

        argv[0] is just the CLI's basename — `generate()` swaps in the resolved
        absolute path before invoking subprocess.
        """
        raise NotImplementedError

    def parse_response(self, stdout: str) -> str:
        """Extract the model's text response from raw CLI stdout."""
        return stdout

    def generate(self, prompt: str, intent: IntentDef) -> str:
        del intent
        argv, stdin_text = self.build_command(prompt)
        argv = [self.executable, *argv[1:]]
        try:
            result = subprocess.run(
                argv,
                input=stdin_text,
                text=True,
                capture_output=True,
                timeout=self.timeout_seconds,
            )
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError(f"{self.name} CLI timed out after {self.timeout_seconds}s") from exc
        if result.returncode != 0:
            raise RuntimeError(
                f"{self.name} CLI exited {result.returncode}: {result.stderr.strip() or result.stdout.strip()}"
            )
        return self.parse_response(result.stdout)


class GeminiCliProvider(CliProvider):
    name = "gemini"
    default_model = "gemini-3.1-pro-preview"

    def build_command(self, prompt: str) -> Tuple[List[str], Optional[str]]:
        # Pass prompt via stdin to dodge shell-escape pitfalls on multi-line text.
        # `-p ""` puts gemini in headless mode and still consumes stdin.
        argv = ["gemini", "-p", "", "-m", self.model, "-o", "json", "-y", "--skip-trust"]
        return argv, prompt

    def parse_response(self, stdout: str) -> str:
        # The gemini CLI prints a JSON object then sometimes appends informational
        # lines like "Shell cwd was reset to ...". Use raw_decode to pull just the
        # leading JSON object.
        decoder = json.JSONDecoder()
        try:
            data, _ = decoder.raw_decode(stdout.lstrip())
        except json.JSONDecodeError as exc:
            raise RuntimeError(f"gemini CLI did not return valid JSON: {exc}; stdout head={stdout[:200]!r}") from exc
        response = data.get("response")
        if not isinstance(response, str):
            raise RuntimeError(f"gemini CLI JSON had no 'response' string: keys={list(data)}")
        return response


class ClaudeCliProvider(CliProvider):
    name = "claude"
    default_model = None  # claude CLI picks its own default model

    def build_command(self, prompt: str) -> Tuple[List[str], Optional[str]]:
        argv = ["claude", "-p"]  # `-p` reads the prompt from stdin / argv
        if self.model:
            argv += ["--model", self.model]
        # Claude CLI accepts the prompt as a positional after -p OR via stdin.
        # Use stdin to avoid escape pitfalls.
        return argv, prompt

    def parse_response(self, stdout: str) -> str:
        return stdout.strip()


class CodexCliProvider(CliProvider):
    name = "codex"
    default_model = None  # codex CLI picks its own default

    def build_command(self, prompt: str) -> Tuple[List[str], Optional[str]]:
        argv = ["codex", "exec"]
        if self.model:
            argv += ["-m", self.model]
        # codex exec takes the prompt as a positional. For multi-line safety use stdin.
        argv.append("-")
        return argv, prompt

    def parse_response(self, stdout: str) -> str:
        return stdout.strip()


def load_intents(path: Path) -> List[IntentDef]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError(f"{path} must contain a YAML list of intents")
    intents: List[IntentDef] = []
    seen: set[str] = set()
    for index, row in enumerate(data, start=1):
        if not isinstance(row, dict):
            raise ValueError(f"intent #{index} must be a mapping")
        intent_id = str(row.get("id", "")).strip()
        description = str(row.get("description", "")).strip()
        if not intent_id or not description:
            raise ValueError(f"intent #{index} must have id and description")
        if intent_id in seen:
            raise ValueError(f"duplicate intent id: {intent_id}")
        seen.add(intent_id)
        seeds = tuple(str(seed) for seed in row.get("seeds", []) if str(seed).strip())
        slots = tuple(row.get("slots", []) or [])
        intents.append(IntentDef(intent_id, description, seeds, slots))
    return intents


def load_menu_intents(path: Path) -> List[IntentDef]:
    commands = [line.strip() for line in path.read_text(encoding="utf-8").splitlines()
                if line.strip()]
    intents: List[IntentDef] = []
    seen: set[str] = set()
    for command in commands:
        slug = slugify(command)
        if not slug:
            continue
        intent_id = "menu." + slug
        if intent_id in seen:
            continue
        seen.add(intent_id)
        intents.append(IntentDef(
            intent_id,
            f"Open menu command: {command}",
            (command.lower(),),
            (),
        ))
    return intents


def provider_from_args(name: str, model: Optional[str]) -> LlmProvider:
    if name == "mock":
        return MockProvider()
    if name == "gemini":
        return GeminiCliProvider(model)
    if name == "claude":
        return ClaudeCliProvider(model)
    if name == "codex":
        return CodexCliProvider(model)
    raise ValueError(f"unknown provider: {name}")


def prompt_for(intent: IntentDef) -> str:
    seeds = ", ".join(intent.seeds[:2]) if intent.seeds else "(none)"
    return PROMPT.format(description=intent.description, seeds=seeds)


def collect_phrases(provider: LlmProvider, intent: IntentDef, minimum: int) -> List[str]:
    import time
    start = time.monotonic()
    print(f"  -> {intent.id} ...", end="", file=sys.stderr, flush=True)
    raw = provider.generate(prompt_for(intent), intent)
    phrases = sorted({normalise(line) for line in raw.splitlines() if normalise(line)})
    phrases = [phrase for phrase in phrases if normalise(phrase) == phrase]
    elapsed = time.monotonic() - start
    print(f" {len(phrases)} phrases in {elapsed:.1f}s", file=sys.stderr, flush=True)
    if len(phrases) < minimum:
        raise ValueError(
            f"{intent.id} produced {len(phrases)} unique normalised phrases; expected at least {minimum}"
        )
    return phrases


def existing_phrasebook(path: Path) -> Dict[str, object]:
    if not path.exists():
        return {"version": 1, "intents": []}
    return json.loads(path.read_text(encoding="utf-8"))


def build_phrasebook(
    intents: Sequence[IntentDef],
    provider: LlmProvider,
    *,
    minimum: int,
    existing: Optional[Dict[str, object]] = None,
    keep: bool = False,
    resume: bool = False,
    checkpoint_path: Optional[Path] = None,
) -> Dict[str, object]:
    existing_by_id: Dict[str, dict] = {}
    if existing:
        for entry in existing.get("intents", []):
            if isinstance(entry, dict) and isinstance(entry.get("id"), str):
                existing_by_id[entry["id"]] = entry

    # --resume reads the checkpoint and reuses any intent that already has
    # >= minimum phrases. Failed mid-run intents (or thin pre-existing entries)
    # get regenerated.
    resume_by_id: Dict[str, dict] = {}
    if resume and checkpoint_path and checkpoint_path.exists():
        try:
            checkpoint = json.loads(checkpoint_path.read_text(encoding="utf-8"))
            for entry in checkpoint.get("intents", []):
                if not isinstance(entry, dict):
                    continue
                phrases = entry.get("phrases", [])
                if isinstance(entry.get("id"), str) and isinstance(phrases, list) and len(phrases) >= minimum:
                    resume_by_id[entry["id"]] = entry
            if resume_by_id:
                print(f"resume: reusing {len(resume_by_id)} intent(s) from {checkpoint_path}", file=sys.stderr, flush=True)
        except (OSError, json.JSONDecodeError) as exc:
            print(f"resume: ignoring unreadable checkpoint at {checkpoint_path}: {exc}", file=sys.stderr, flush=True)

    built: List[dict] = []
    phrase_owner: Dict[str, str] = {}
    dropped_collisions: List[Tuple[str, str, str]] = []
    total = len(intents)

    def write_checkpoint() -> None:
        if checkpoint_path is None:
            return
        rendered = json.dumps({"version": 1, "intents": built}, indent=2, ensure_ascii=False) + "\n"
        tmp = checkpoint_path.with_suffix(checkpoint_path.suffix + ".tmp")
        checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
        tmp.write_text(rendered, encoding="utf-8")
        os.replace(tmp, checkpoint_path)

    for index, intent in enumerate(intents, start=1):
        print(f"[{index}/{total}] {intent.id}", file=sys.stderr, flush=True)
        if intent.id in resume_by_id:
            entry = resume_by_id[intent.id]
            phrases = sorted({normalise(str(p)) for p in entry.get("phrases", []) if normalise(str(p))})
            description = str(entry.get("description") or intent.description)
            print(f"  -> {intent.id} ... {len(phrases)} phrases (resumed)", file=sys.stderr, flush=True)
        elif keep and intent.id in existing_by_id:
            entry = existing_by_id[intent.id]
            phrases = sorted({normalise(str(p)) for p in entry.get("phrases", []) if normalise(str(p))})
            description = str(entry.get("description") or intent.description)
        else:
            phrases = collect_phrases(provider, intent, minimum)
            description = intent.description

        # First-insertion wins: drop phrases already owned by an earlier intent.
        # The Java IntentMatcher uses a hash map with the same semantics, so doing
        # the resolution here keeps the JSON in sync with runtime behaviour.
        kept: List[str] = []
        for phrase in phrases:
            owner = phrase_owner.get(phrase)
            if owner and owner != intent.id:
                dropped_collisions.append((phrase, owner, intent.id))
                continue
            phrase_owner[phrase] = intent.id
            kept.append(phrase)

        if len(kept) < minimum and intent.id not in resume_by_id:
            raise ValueError(
                f"{intent.id} has only {len(kept)} unique phrases after collision resolution; expected at least {minimum}"
            )

        built.append({"id": intent.id, "description": description, "phrases": kept})
        write_checkpoint()

    if dropped_collisions:
        for phrase, first, later in dropped_collisions:
            print(f"resolved collision: dropped {phrase!r} from {later} (kept in {first})", file=sys.stderr)
        print(f"resolved {len(dropped_collisions)} cross-intent phrase collision(s)", file=sys.stderr)

    return {"version": 1, "intents": built}


def select_intents(intents: Sequence[IntentDef], intent_id: Optional[str]) -> List[IntentDef]:
    if not intent_id:
        return list(intents)
    selected = [intent for intent in intents if intent.id == intent_id]
    if not selected:
        raise ValueError(f"unknown intent id: {intent_id}")
    return selected


def validate_schema(document: Dict[str, object], *, require_normalised_phrases: bool = True) -> None:
    if document.get("version") != 1:
        raise ValueError("phrasebook version must be 1")
    intents = document.get("intents")
    if not isinstance(intents, list):
        raise ValueError("phrasebook intents must be a list")
    for index, entry in enumerate(intents, start=1):
        if not isinstance(entry, dict):
            raise ValueError(f"intent #{index} must be an object")
        if not isinstance(entry.get("id"), str) or not entry["id"]:
            raise ValueError(f"intent #{index} must have an id")
        if not isinstance(entry.get("description"), str) or not entry["description"]:
            raise ValueError(f"intent #{index} must have a description")
        phrases = entry.get("phrases")
        if not isinstance(phrases, list) or not phrases:
            raise ValueError(f"{entry['id']} must have phrases")
        for phrase in phrases:
            if not isinstance(phrase, str):
                raise ValueError(f"{entry['id']} has an invalid phrase: {phrase!r}")
            if require_normalised_phrases and normalise(phrase) != phrase:
                raise ValueError(f"{entry['id']} has an unnormalised phrase: {phrase!r}")


def check_cross_intent_collisions(document: Dict[str, object]) -> None:
    owner: Dict[str, str] = {}
    collisions: List[Tuple[str, str, str]] = []
    for entry in document.get("intents", []):
        if not isinstance(entry, dict):
            continue
        intent_id = str(entry.get("id", ""))
        for phrase in entry.get("phrases", []):
            phrase = str(phrase)
            first = owner.get(phrase)
            if first and first != intent_id:
                collisions.append((phrase, first, intent_id))
            else:
                owner[phrase] = intent_id
    if collisions:
        for phrase, first, second in collisions:
            print(f"warning: phrase collision {phrase!r} in {first} and {second}", file=sys.stderr)
        raise ValueError(f"{len(collisions)} cross-intent phrase collision(s) detected")


def merge_selected_intent(existing: Dict[str, object], generated: Dict[str, object]) -> Dict[str, object]:
    generated_by_id = {
        entry["id"]: entry
        for entry in generated.get("intents", [])
        if isinstance(entry, dict) and isinstance(entry.get("id"), str)
    }
    merged: List[dict] = []
    seen: set[str] = set()
    for entry in existing.get("intents", []):
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            continue
        intent_id = entry["id"]
        merged.append(generated_by_id.get(intent_id, entry))
        seen.add(intent_id)
    for intent_id, entry in generated_by_id.items():
        if intent_id not in seen:
            merged.append(entry)
    return {"version": 1, "intents": merged}


def remove_existing_phrase_collisions(existing: Dict[str, object],
                                      generated: Dict[str, object]) -> Dict[str, object]:
    existing_phrases: set[str] = set()
    for entry in existing.get("intents", []):
        if not isinstance(entry, dict):
            continue
        for phrase in entry.get("phrases", []):
            existing_phrases.add(normalise(str(phrase)))

    filtered: List[dict] = []
    for entry in generated.get("intents", []):
        if not isinstance(entry, dict):
            continue
        phrases = [
            phrase for phrase in entry.get("phrases", [])
            if normalise(str(phrase)) not in existing_phrases
        ]
        if phrases:
            copy = dict(entry)
            copy["phrases"] = phrases
            filtered.append(copy)
    return {"version": 1, "intents": filtered}


def java_load_check(json_path: Path) -> None:
    """Confirm a generated file matches the IntentLibrary resource schema.

    This intentionally uses Maven's existing test classpath and a temporary
    classpath resource directory, without modifying src/main/resources.
    """
    import subprocess
    import tempfile

    with tempfile.TemporaryDirectory(prefix="phrasebook_java_load_") as temp:
        temp_path = Path(temp)
        resource_dir = temp_path / "resources"
        helper_src = temp_path / "PhrasebookLoadCheck.java"
        resource_dir.mkdir()
        (resource_dir / "phrasebook.json").write_text(json_path.read_text(encoding="utf-8"), encoding="utf-8")
        helper_src.write_text(
            "\n".join(
                [
                    "import imagejai.local.IntentLibrary;",
                    "public class PhrasebookLoadCheck {",
                    "  public static void main(String[] args) {",
                    "    IntentLibrary lib = IntentLibrary.load();",
                    "    if (lib.allPhrases().size() < 1) throw new RuntimeException(\"no phrases loaded\");",
                    "    System.out.println(\"IntentLibrary.load phrases=\" + lib.allPhrases().size());",
                    "  }",
                    "}",
                    "",
                ]
            ),
            encoding="utf-8",
        )
        cp_result = subprocess.run(
            ["cmd", "/c", "mvn", "-q", "dependency:build-classpath", "-Dmdep.outputFile=target\\phrasebook-tool-classpath.txt"],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if cp_result.returncode != 0:
            raise RuntimeError(cp_result.stderr.strip() or cp_result.stdout.strip())
        dependency_cp = (ROOT / "target" / "phrasebook-tool-classpath.txt").read_text(encoding="utf-8").strip()
        classpath = os.pathsep.join([str(resource_dir), str(ROOT / "target" / "classes"), dependency_cp])
        compile_result = subprocess.run(
            ["javac", "-cp", classpath, str(helper_src)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if compile_result.returncode != 0:
            raise RuntimeError(compile_result.stderr.strip() or compile_result.stdout.strip())
        run_result = subprocess.run(
            ["java", "-cp", os.pathsep.join([classpath, str(temp_path)]), "PhrasebookLoadCheck"],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if run_result.returncode != 0:
            raise RuntimeError(run_result.stderr.strip() or run_result.stdout.strip())
        print(run_result.stdout.strip())


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build src/main/resources/phrasebook.json from tools/intents.yaml")
    parser.add_argument("--intents-file", type=Path, default=DEFAULT_INTENTS)
    parser.add_argument("--output", type=Path, default=DEFAULT_PHRASEBOOK)
    parser.add_argument("--intent", help="Regenerate one intent id")
    parser.add_argument("--menu-dump", type=Path, help="Read ImageJ/Fiji menu commands, one per line, and add menu.* intents")
    parser.add_argument("--provider", choices=("mock", "gemini", "claude", "codex"), default=os.environ.get("LOCAL_ASSISTANT_LLM_PROVIDER", "gemini"))
    parser.add_argument("--model", help="Provider model override")
    parser.add_argument("--dry-run", action="store_true", help="Print generated JSON to stdout without writing")
    parser.add_argument("--keep", action="store_true", help="Keep existing intent entries instead of regenerating them")
    parser.add_argument("--resume", action="store_true", help="Reuse intents from <output>.partial.json that already have at least --minimum phrases. Combine with mid-run failure recovery.")
    parser.add_argument("--minimum", type=int, default=40, help="Minimum unique normalised phrases per intent")
    parser.add_argument("--java-load-check", action="store_true", help="After writing, verify the output through IntentLibrary.load()")
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    try:
        provider = provider_from_args(args.provider, args.model)
        # Per-intent checkpoint path so a mid-run failure (quota, network) does
        # not throw away work already done. Skipped on --dry-run.
        checkpoint_path = None if args.dry_run else args.output.with_suffix(args.output.suffix + ".partial")
        if args.menu_dump:
            existing_output = existing_phrasebook(args.output)
            existing_ids = {
                entry["id"]
                for entry in existing_output.get("intents", [])
                if isinstance(entry, dict) and isinstance(entry.get("id"), str)
            }
            menu_intents = [intent for intent in load_menu_intents(args.menu_dump)
                            if intent.id not in existing_ids]
            intents = select_intents(menu_intents, args.intent)
            generated = build_phrasebook(intents, provider, minimum=args.minimum,
                                         resume=args.resume, checkpoint_path=checkpoint_path)
            generated = remove_existing_phrase_collisions(existing_output, generated)
            phrasebook = merge_selected_intent(existing_output, generated)
        else:
            intents = select_intents(load_intents(args.intents_file), args.intent)
            existing = existing_phrasebook(args.output) if args.keep else None
            phrasebook = build_phrasebook(intents, provider, minimum=args.minimum, existing=existing,
                                          keep=args.keep, resume=args.resume, checkpoint_path=checkpoint_path)
            if args.intent and not args.dry_run and args.output.exists():
                phrasebook = merge_selected_intent(existing_phrasebook(args.output), phrasebook)
        validate_schema(phrasebook, require_normalised_phrases=not bool(args.menu_dump))
        check_cross_intent_collisions(phrasebook)
        rendered = json.dumps(phrasebook, indent=2, ensure_ascii=False) + "\n"
        if args.dry_run:
            if args.intent and len(phrasebook["intents"]) == 1:
                for phrase in phrasebook["intents"][0]["phrases"]:
                    print(phrase)
            else:
                print(rendered, end="")
            return 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
        print(f"wrote {args.output}")
        # Run completed cleanly; checkpoint no longer needed.
        if checkpoint_path and checkpoint_path.exists():
            try:
                checkpoint_path.unlink()
            except OSError:
                pass
        if args.java_load_check:
            java_load_check(args.output)
        return 0
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
