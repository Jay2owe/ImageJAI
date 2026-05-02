package imagejai.engine;

import imagejai.config.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Detects the Claude Code GSD skill once per plugin lifetime.
 */
public final class GsdDetector {

    interface Probe {
        boolean exists(Path path);
        boolean claudeHelpExitsZero();
    }

    private static volatile Boolean cached;
    private static volatile Probe probe = new Probe() {
        @Override
        public boolean exists(Path path) {
            return Files.isDirectory(path);
        }

        @Override
        public boolean claudeHelpExitsZero() {
            try {
                Process process = new ProcessBuilder("claude", "/gsd:help")
                        .redirectErrorStream(true)
                        .start();
                boolean done = process.waitFor(5, TimeUnit.SECONDS);
                if (!done) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        }
    };

    private GsdDetector() {
    }

    public static boolean isInstalled() {
        return isInstalled(Settings.load());
    }

    public static boolean isInstalled(Settings settings) {
        Boolean current = cached;
        if (current != null) {
            return current.booleanValue();
        }
        synchronized (GsdDetector.class) {
            if (cached != null) {
                return cached.booleanValue();
            }
            boolean installed = probe.exists(resolveSkillsPath(settings))
                    || probe.claudeHelpExitsZero();
            cached = Boolean.valueOf(installed);
            return installed;
        }
    }

    static Path resolveSkillsPath(Settings settings) {
        String configured = settings == null ? "" : settings.gsdSkillsPath;
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        return Paths.get(System.getProperty("user.home"), ".claude", "skills", "gsd");
    }

    static void resetCacheForTests() {
        cached = null;
    }

    static void setProbeForTests(Probe testProbe) {
        probe = testProbe;
        cached = null;
    }

    static void restoreProbeForTests() {
        probe = new Probe() {
            @Override
            public boolean exists(Path path) {
                return Files.isDirectory(path);
            }

            @Override
            public boolean claudeHelpExitsZero() {
                try {
                    Process process = new ProcessBuilder("claude", "/gsd:help")
                            .redirectErrorStream(true)
                            .start();
                    boolean done = process.waitFor(5, TimeUnit.SECONDS);
                    if (!done) {
                        process.destroyForcibly();
                        return false;
                    }
                    return process.exitValue() == 0;
                } catch (Exception e) {
                    return false;
                }
            }
        };
        cached = null;
    }
}
