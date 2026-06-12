package imagejai.engine.terminal;

import ij.IJ;
import imagejai.engine.AgentLaunchSpec;

import java.lang.reflect.Constructor;

/**
 * Runtime selector for Java 8-safe external terminals and Java 11+ embedded
 * terminals.
 */
public final class TerminalProviderFactory {
    private static final String EMBEDDED_PROVIDER_CLASS =
            "imagejai.engine.terminal.embedded.EmbeddedTerminalProvider";
    private static final String JAVA8_NOTICE =
            "Embedded terminal needs Java 11+ - launching agent in an external window.";

    private TerminalProviderFactory() {
    }

    public static TerminalProvider create(AgentLaunchSpec embeddedSpec,
                                          AgentLaunchSpec externalSpec) {
        if (!isJava11OrNewer()) {
            return new ExternalTerminalProvider(externalSpec, JAVA8_NOTICE);
        }

        try {
            Class<?> cls = Class.forName(EMBEDDED_PROVIDER_CLASS);
            Constructor<?> constructor = cls.getConstructor(AgentLaunchSpec.class);
            Object instance = constructor.newInstance(embeddedSpec);
            return (TerminalProvider) instance;
        } catch (ClassNotFoundException e) {
            return fallback(externalSpec, "Embedded terminal backend is not available.");
        } catch (LinkageError e) {
            return fallback(externalSpec,
                    "Embedded terminal backend failed to load: " + e.getClass().getSimpleName());
        } catch (ReflectiveOperationException e) {
            return fallback(externalSpec,
                    "Embedded terminal backend failed to initialize: " + e.getMessage());
        } catch (ClassCastException e) {
            return fallback(externalSpec,
                    "Embedded terminal backend has an incompatible provider contract.");
        }
    }

    static boolean isJava11OrNewer() {
        return parseJavaSpecification(System.getProperty("java.specification.version")) >= 11;
    }

    static int parseJavaSpecification(String version) {
        if (version == null || version.trim().isEmpty()) {
            return 8;
        }
        String value = version.trim();
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        int dot = value.indexOf('.');
        if (dot >= 0) {
            value = value.substring(0, dot);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    private static TerminalProvider fallback(AgentLaunchSpec externalSpec, String reason) {
        IJ.log("[ImageJAI-Term] " + reason + " Falling back to external terminal.");
        return new ExternalTerminalProvider(externalSpec,
                reason + " Launching agent in an external window.");
    }
}
