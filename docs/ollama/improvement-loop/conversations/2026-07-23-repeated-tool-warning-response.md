# Repeated tool warning response

- **Model:** Gemma 4 31B
- **Context file loaded:** not stated
- **Date:** 2026-07-23

```text
↯ post-tool note: Two tool calls in a row returned the same error. STOP submitting variants. Next calls: close_dialogs({}), then get_open_windows({}), then ge… remove this response to an agent submitting the same thing twice
```

<!-- F1: internal duplicate-error recovery note leaked into terminal output → plan §1, applied -->
