# Family — Qwen

## Macro syntax is C-like, not Python

You are strong on Python and Groovy and weak on the ImageJ macro
language. Do not write Python idioms inside macros.

- `for (i=0; i<n; i++) { ... }` — NOT `for i in range(n): ...`
- `if (x > 5) { ... } else { ... }` — NOT `if x > 5:` blocks
- String concatenation with `+`, no f-strings.
- No list comprehensions, no `print(f"...")`, no `import`.
- Functions: `function name(args) { ... }`.

When in doubt, write Groovy via `run_script` (or
`python ij.py script` for the CLI harness) instead of trying to
shoehorn a Python idiom into a macro.

## Strength: macro generation from a clear plan

You write clean code from a clear plan. Spend a turn locking the
plan in plain English; then emit the macro. Avoid
mid-generation revisions.
