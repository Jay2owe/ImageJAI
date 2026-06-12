# Python Agent Helpers — Code Review
**Date:** 2026-05-03
**Scope:** agent/ij.py, agent/pixels.py, agent/probe_plugin.py, agent/recipe_search.py, agent/auditor.py

---

## Findings

### BUGS

**[BUG]** `agent/ij.py:829` — Duplicate `imagej_events()` function definition
- Lines 772–827 and 829–875 define the same function with identical logic but different comments. Python silently uses the second definition; the first is dead code. This is a maintenance trap: bugs fixed in one definition won't propagate to the other, and it wastes 100+ lines.
- Delete lines 772–827 entirely; keep the cleaner 829–875 version.

**[BUG]** `agent/probe_plugin.py:56,67,77,92,110` — Missing encoding on file operations
- All `open()` calls lack `encoding='utf-8'`. On Windows with cp1252 default encoding, JSON files with non-ASCII characters (e.g. "μm" for microns) silently corrupt during read or write. The cache file is JSON, which must be UTF-8.
- Add `encoding='utf-8'` to all `open()` calls: `with open(cpath, encoding='utf-8') as f:` and `with open(cpath, 'w', encoding='utf-8') as f:`.

**[BUG]** `agent/pixels.py:35–51` — Socket created but never closed on exception
- The `send()` function creates a socket at line 37, calls `s.settimeout(60)`, `s.connect()`, and `s.sendall()`, but only calls `s.close()` at line 50 on the success path. If `json.decode()` at line 51 raises (malformed JSON), the socket leaks. This is a file descriptor leak that accumulates over many calls.
- Wrap in try/finally: `try: [connection logic] finally: s.close()`.

**[BUG]** `agent/ij.py:197` — Partial JSON read not detected
- The `imagej_command()` function at lines 177–186 reads chunks and checks `if data.endswith(b"\n")` to detect end-of-response. But if the server sends `{"ok": true}\n` and then crashes, only the first JSON is read. If multiple messages arrive in one chunk separated by `\n`, the code stops after the first. The loop relies on socket close (`chunk == b""`) to detect all data received, but only sleeps `1/timeout` per iteration, meaning slow networks might time out before complete JSON arrives.
- Better approach: parse complete JSON frames as they arrive by splitting on `\n` before checking for complete JSON object (check brace count or use `json.JSONDecoder.raw_decode()`).

**[BUG]** `agent/auditor.py:153, 155–160` — Area plausibility returns mixed types
- `_check_area_plausibility()` sometimes returns a dict (line 190–196), sometimes a list of dicts (lines 173, 181), and sometimes None (line 155). Callers at line 593–598 check `if isinstance(area_result, list)` and call `.extend()`, but the None case is silently appended to `all_checks` at line 590, resulting in `all_checks = [..., None, ...]`. Later code assumes all checks are dicts and calls `.get("status")`, which fails on None.
- Normalize: always return a single dict or list of dicts, never None. Return `{"check": "area_plausibility", "status": "pass", ...}` when no area column exists.

**[BUG]** `agent/ij.py:604–607, 612–614` — Exception swallowed silently in line-parsing loop
- In `wait_for_job()`, the loop at 606–614 catches `Exception` from `json.loads()` and silently `continue`s. If the server sends garbage (e.g. truncated message), this will skip valid frames. If ALL frames are malformed JSON, the timeout handler never fires because the socket is still open, and the function hangs until the TCP timeout.
- Log the error or re-raise after a threshold: `except Exception as e: err_count += 1; if err_count > 5: raise; continue`.

---

### ISSUES

**[ISSUE]** `agent/recipe_search.py:56–285` — YAML parser missing important features
- The fallback `_simple_yaml_parse()` is marked "does NOT handle anchors, aliases, or complex nesting beyond 3 levels". If any recipe uses YAML anchors (`&anchor`, `*anchor`) or deep nesting, the parser silently returns incomplete or malformed dicts. The code does not validate nesting depth or warn about unsupported features.
- Add assertions in `load_recipe()` to reject files with unknown features, or enforce that recipes pass PyYAML first.

**[ISSUE]** `agent/ij.py:772–827` (old function) and 829–875 (new function) — Subtle behavioral difference in reconnect loop
- The old `imagej_events()` at line 810 yields frame objects and continues the loop unconditionally. The new one at line 860 checks `if isinstance(frame, dict) and frame.get("ok") is False: return`. This means the new version stops on error frames, the old one doesn't. This silent behavioral change breaks replay and makes version confusion a silent failure mode.
- Document or consolidate.

**[ISSUE]** `agent/ij.py:772–826` — Comment and docstring disconnect
- Lines 772–782 show a docstring promising detailed behavior, but the docstring at 779–782 contradicts line 810 (which yields unconditionally) and line 811 (which also continues on malformed frames). Readers cannot trust the docstring.
- Update docstring to match implementation or fix implementation to match promise.

**[ISSUE]** `agent/auditor.py:151–198` — Edge-bias check conflates row index with area value
- The `_check_edge_bias()` function at 354–364 builds `area_by_row` keyed by row index from `area_idxs`, then at 382–392 tries to find the corresponding Y value for each X by searching `y_idxs`. But if X, Y, and Area columns have different row orderings (e.g. due to filtering), the pairing is wrong. The loop at 388–391 is O(n²) and has no guard against mismatches.
- Pre-build a row-indexed lookup for all columns and pair by index, not by searching.

**[ISSUE]** `agent/pixels.py:42–51` — `json.loads()` on potentially incomplete buffer
- The recv loop reads until timeout (line 48–49 catches `socket.timeout`), but does not check whether the full JSON frame has arrived. If the server sends `{"ok":` and then hangs for 0.1s, the code times out and tries `json.loads()` on a partial string, raising `JSONDecodeError`.
- Check for complete JSON (e.g., brace matching) before calling `json.loads()`.

**[ISSUE]** `agent/ij.py:763–764` — 60-second hardcoded polling fallback has no escape hatch
- The `gui_confirm()` function's fallback path sleeps 1 second per iteration for exactly 60 iterations (line 764) regardless of user action. If the server's subscribe fails, the user gets a 60-second hang. No indication it's waiting, no early-exit on user cancel.
- Accept KeyboardInterrupt in the loop or use a shorter timeout for the fallback.

---

### INEFFICIENCIES

**[INEFFICIENCY]** `agent/recipe_search.py:467–510` — O(n²) token matching in search
- The search algorithm at 488–504 iterates over `query_tokens` (outer) and then `text_tokens` (inner), both derived from the same recipe. For N recipes and M tokens each, this is O(N²M²) when searching multiple recipes. With 100 recipes and 10 tokens each, this is 10M comparisons per search.
- Use a set for token lookups: `text_token_set = set(_tokenize(...))` and check membership in O(1).

**[INEFFICIENCY]** `agent/auditor.py:310–348` — Repeated column lookups in outlier detection
- `_check_outliers()` calls `_col_values(rows, col)` for every numeric column, which scans the entire rows list each time (lines 310–323). With 100 rows and 50 columns, this is 5000 row scans.
- Build a single column cache upfront: `{col_name: [values, indices]}`.

**[INEFFICIENCY]** `agent/ij.py:580–606` — Re-checking job status after subscribe
- In `wait_for_job()`, line 587 calls `job_status()` again after subscribing, even though the subscription was just set up. This is a TOCTOU (time-of-check-time-of-use) guard, but it adds an extra TCP round-trip for most calls.
- This is actually good defensive programming; mark as intentional with a comment.

**[INEFFICIENCY]** `agent/recipe_search.py:315–323` — Repeated recipe loading on every list/show/validate call
- `load_all_recipes()` scans the directory and loads every YAML file on every call. If recipes are loaded 10 times per session, each with 50 files, that's 500 YAML parses. No caching.
- Add module-level cache: `_recipe_cache = {}` and `_recipe_cache_mtime = {}` to detect file changes.

---

### NITS

**[NIT]** `agent/ij.py:1048–1050` — File read without encoding
- `with open(sys.argv[i + 1], "r") as f:` lacks encoding. Python defaults to locale encoding (cp1252 on Windows). Groovy/Jython scripts with UTF-8 comments or strings corrupt silently.
- Add `encoding='utf-8'`.

**[NIT]** `agent/pixels.py:303` — Error message concatenates exception object
- Line 303: `print("ERROR: Cannot connect to ImageJAI on localhost:" + str(PORT))` should use f-string or .format() for clarity and consistency with other error messages.
- Use: `print(f"ERROR: Cannot connect to ImageJAI on localhost:{PORT}")`.

**[NIT]** `agent/ij.py:640` — Bare `except Exception` in poll loop silently swallows errors
- At line 639, `cur = job_status(job_id)` is wrapped in `except Exception:`, which catches network errors, timeout, JSON errors, and program bugs identically. The fallback `cur = {"ok": False, "error": "poll failed"}` hides whether it was a network issue or a bug.
- Log the exception: `except Exception as e: logging.warning(f"job_status() failed: {e}"); cur = ...`.

**[NIT]** `agent/auditor.py:62` — `isnan()` check on integers
- Line 62 calls `math.isnan(v)` on values that may be int or float. Python's `isnan()` raises TypeError on int, but the code assumes it won't. If a column contains only integers (common for Area, for example), this crashes.
- Guard: `if isinstance(v, float) and math.isnan(v)` or catch TypeError.

**[NIT]** `agent/recipe_search.py:738–750` — Command-line arg parsing is fragile
- The main() function at 738–750 uses positional indexing (`args[show_recipe]`, `args.show`) and string comparisons without bounds checking. If a user runs `recipe_search.py --show`, this crashes with IndexError.
- Use argparse or at least validate bounds before access.

**[NIT]** `agent/ij.py:1555` — Multiple statements on one line
- Line 1555: `x = int(sys.argv[3]); y = int(sys.argv[4])` violates PEP 8 (one statement per line). Similar pattern at 1556.
- Separate to multiple lines for readability.

**[NIT]** `agent/auditor.py:590` — Missing docstring return type info
- `_parse_csv()` docstring at line 28 doesn't describe return type. Callers at 574 must read the function body to know it returns `(rows, columns)`.
- Add to docstring: `:return: (rows, columns) where rows is list of dicts and columns is list of str`.

---

## Summary

| Severity | Count |
|----------|-------|
| BUG      | 5     |
| ISSUE    | 7     |
| INEFFICIENCY | 5 |
| NIT      | 9     |
| **Total** | **26** |

### Top 3 Critical Issues

1. **[BUG]** `agent/ij.py:829` — Duplicate `imagej_events()` function causes maintenance confusion and dead code.
2. **[BUG]** `agent/probe_plugin.py` — Missing UTF-8 encoding on file I/O causes silent data corruption on Windows with non-ASCII characters.
3. **[BUG]** `agent/pixels.py:35–51` — Socket leak on exception in `send()` causes file descriptor exhaustion.
