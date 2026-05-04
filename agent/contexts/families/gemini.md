# Family — Gemini

(Gemini's tool-result formatting differs from Anthropic — Gemini
wraps `function_response` in a structured object that is sensitive
to non-JSON content. That is a wrapper concern, handled in
`agent/providers/gemini_native.py`, not a prompt concern. Add
observed model-side friction patterns here as Gemini sessions
accumulate history.)
