"""ImageJAI multi-provider spike — proof-of-concept routing layer.

Three paths fan out from one ProviderClient interface:
  - LiteLLM Proxy (openai SDK pointed at localhost:4000)
  - Anthropic native (anthropic SDK)
  - Google Gemini native (google-genai SDK)

Self-contained: does not import or modify ollama_chat.py.
"""
