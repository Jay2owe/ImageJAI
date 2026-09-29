package imagejai.engine.automation;

import net.imagej.ImageJService;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Lets the standalone console request the authenticated TCP bridge while Fiji
 * is already open. The watcher never opens the assistant panel or enables the
 * test-only automation commands.
 */
@Plugin(type = Service.class)
public class ConsoleBootstrapService extends AbstractService implements ImageJService {
    private static final long MAX_REQUEST_AGE_MS = 120_000L;
    private static final long POLL_MS = 750L;

    @Override
    public void initialize() {
        if (AutomationPolicy.current().isEnabled()) return;
        Thread watcher = new Thread(new Runnable() {
            @Override public void run() { watchRequests(); }
        }, "ImageJAI-console-bridge-request");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void watchRequests() {
        String override = System.getenv("IMAGEJAI_HOME");
        Path configRoot = override == null || override.trim().isEmpty()
                ? Paths.get(System.getProperty("user.home", ""), ".imagej-ai")
                : Paths.get(override);
        Path request = configRoot.resolve("console").resolve("tcp-start-request.properties");
        String lastAttempt = null;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Do not load ij.* until Fiji's IJ1 patcher has run.
                if (AutomationBootstrapService.imageJMainWindowExists()
                        && Files.isRegularFile(request)) {
                    Properties values = new Properties();
                    try (java.io.Reader reader = Files.newBufferedReader(request)) {
                        values.load(reader);
                    }
                    String stamp = values.getProperty("request_id",
                            values.getProperty("created_ms", ""));
                    if (!stamp.equals(lastAttempt)) {
                        lastAttempt = stamp;
                        if (requestTargetsThisFiji(values)) {
                            int port = Integer.parseInt(values.getProperty("port", "0"));
                            startThroughImageJClassLoader(port);
                        }
                    }
                }
            } catch (Throwable error) {
                System.err.println("[ImageJAI-TCP] console bridge request failed: "
                        + error);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean requestTargetsThisFiji(Properties values) throws Exception {
        if (!"1".equals(values.getProperty("version"))) return false;
        long created = Long.parseLong(values.getProperty("created_ms", "0"));
        long age = System.currentTimeMillis() - created;
        if (age < -5_000L || age > MAX_REQUEST_AGE_MS) return false;
        Path target = Paths.get(URI.create(values.getProperty("target_uri", "")))
                .toRealPath();
        return currentFijiRoot().equals(target);
    }

    /** The installation containing this plugin, independent of imagej.dir overrides. */
    public static Path currentFijiRoot() throws Exception {
        Path location = Paths.get(ConsoleBootstrapService.class
                .getProtectionDomain().getCodeSource().getLocation().toURI())
                .toRealPath();
        if (Files.isRegularFile(location)
                && location.getParent() != null
                && "plugins".equalsIgnoreCase(location.getParent()
                        .getFileName().toString())) {
            return location.getParent().getParent().toRealPath();
        }
        String imagejDirectory = ij.IJ.getDirectory("imagej");
        if (imagejDirectory == null) throw new IllegalStateException("Fiji installation not found");
        return Paths.get(imagejDirectory).toRealPath();
    }

    private static void startThroughImageJClassLoader(int port) throws Exception {
        ClassLoader loader = ij.IJ.getClassLoader();
        Class<?> plugin = loader == null
                ? imagejai.ImageJAIPlugin.class
                : loader.loadClass("imagejai.ImageJAIPlugin");
        Object result = plugin.getMethod("startConsoleBridge", int.class)
                .invoke(null, port);
        if (!Boolean.TRUE.equals(result)) {
            System.err.println("[ImageJAI-TCP] console bridge did not start on :" + port);
        }
    }
}
