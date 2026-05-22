package imagejai.engine.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Shared vendor-term strings for the Data Handling Statement and stage 07
 * documentation. TODO(stage 07): update vendor_terms_summary.md whenever this
 * registry changes.
 */
public final class VendorTermsRegistry {
    public static final List<VendorTerm> ALL = Collections.unmodifiableList(Arrays.asList(
            new VendorTerm("Anthropic Claude API / Claude Code",
                    "No training on API inputs/outputs; abuse logs retained 7 days.",
                    "https://www.anthropic.com/legal/commercial-terms"),
            new VendorTerm("OpenAI API / Codex CLI",
                    "No training on business API data; 30-day abuse logs.",
                    "https://openai.com/enterprise-privacy/"),
            new VendorTerm("Google Gemini API (paid)",
                    "No training; 55-day abuse logs.",
                    "https://cloud.google.com/gemini/docs/discover/data-governance"),
            new VendorTerm("Google Gemini API / AI Studio (free tier)",
                    "PROMPTS AND OUTPUTS ARE USED FOR TRAINING. NOT RECOMMENDED FOR RESEARCH DATA.",
                    "https://ai.google.dev/gemini-api/terms"),
            new VendorTerm("Ollama (local)",
                    "No outbound network traffic. Recommended for Restricted data.",
                    ""),
            new VendorTerm("Ollama Cloud (*-cloud model tags)",
                    "Routes inference to Ollama's US-hosted servers. Not a local execution.",
                    "https://ollama.com/privacy")
    ));

    private VendorTermsRegistry() {
    }

    public static final class VendorTerm {
        private final String vendor;
        private final String statement;
        private final String url;

        public VendorTerm(String vendor, String statement, String url) {
            this.vendor = vendor == null ? "" : vendor;
            this.statement = statement == null ? "" : statement;
            this.url = url == null ? "" : url;
        }

        public String vendor() {
            return vendor;
        }

        public String statement() {
            return statement;
        }

        public String url() {
            return url;
        }

        public String displayUrl() {
            String out = url;
            if (out.startsWith("https://")) {
                out = out.substring("https://".length());
            } else if (out.startsWith("http://")) {
                out = out.substring("http://".length());
            }
            if (out.startsWith("www.")) {
                out = out.substring("www.".length());
            }
            while (out.endsWith("/")) {
                out = out.substring(0, out.length() - 1);
            }
            return out;
        }
    }
}
