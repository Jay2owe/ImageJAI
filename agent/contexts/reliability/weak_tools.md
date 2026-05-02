# Reliability — Weak tool calling

Tool calls are your weak spot. Follow these rules strictly.

- **Tools take a single semantic argument.** Read the docstring
  example values literally. If you are about to pass two
  parameters to a tool, you are calling the wrong tool — pick a
  more specific one.
- **Verify the tool name before calling.** Hallucinated tool
  names (`run_command`, `execute`, `imagej`) do not exist —
  consult the tool table in this context, not your training data.
- **Match the JSON schema exactly.** Field names are
  case-sensitive. Required fields are required. Do not invent
  fields the schema does not list.
- **On any tool error, do not re-issue the same call with the
  same arguments.** Re-read the error, fix the call, then retry
  once. Two identical errors → stop and ask the user.
- **Macros are strings, not Python.** When emitting `run_macro`,
  the `code` argument is ImageJ macro language (C-like syntax),
  not Python. `for (i=0; i<n; i++)`, not `for i in range(n)`.
- **One tool per turn unless explicitly parallelisable.** Chain
  through results; do not bundle.
