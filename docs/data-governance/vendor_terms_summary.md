# Vendor Terms Summary

Last verified: 2026-05-22

This table mirrors `src/main/java/imagejai/engine/security/VendorTermsRegistry.java`.
The summary strings below must match the registry exactly.

| Vendor | Summary | Terms URL |
|---|---|---|
| Anthropic Claude API / Claude Code | No training on API inputs/outputs; abuse logs retained 7 days. | https://www.anthropic.com/legal/commercial-terms |
| OpenAI API / Codex CLI | No training on business API data; 30-day abuse logs. | https://openai.com/enterprise-privacy/ |
| Google Gemini API (paid) | No training; 55-day abuse logs. | https://cloud.google.com/gemini/docs/discover/data-governance |
| Google Gemini API / AI Studio (free tier) | PROMPTS AND OUTPUTS ARE USED FOR TRAINING. NOT RECOMMENDED FOR RESEARCH DATA. | https://ai.google.dev/gemini-api/terms |
| Ollama (local) | No outbound network traffic. Recommended for Restricted data. |  |
| Ollama Cloud (*-cloud model tags) | Routes inference to Ollama's US-hosted servers. Not a local execution. | https://ollama.com/privacy |

Use On-premises posture for work that must stay off cloud-hosted model
endpoints. Free-tier Gemini / AI Studio is not recommended for
research data under this governance model.

*This table and `src/main/java/imagejai/engine/security/VendorTermsRegistry.java` are edited together.*
