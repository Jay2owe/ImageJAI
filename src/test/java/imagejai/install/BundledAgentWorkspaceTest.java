package imagejai.install;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BundledAgentWorkspaceTest {

    @Test
    public void extractsRunnableAgentWithoutPrivateRuntimeState() throws Exception {
        Path source = Files.createTempDirectory("imagejai-agent-classes");
        Path resources = source.resolve("agent-runtime");
        Files.createDirectories(resources.resolve("gemma4_31b"));
        Files.createDirectories(resources.resolve("providers"));
        Files.createDirectories(resources.resolve("console"));
        Files.write(resources.resolve("ij.py"), "# client".getBytes());
        Files.write(resources.resolve("gemma4_31b").resolve("__main__.py"),
                "# entrypoint".getBytes());
        Files.write(resources.resolve("providers").resolve("agent_cli.py"),
                "# provider".getBytes());
        Files.write(resources.resolve("console").resolve("__main__.py"),
                "# console".getBytes());
        Files.write(resources.resolve("console").resolve("requirements.txt"),
                "textual>=6.2,<7".getBytes());
        Path target = Files.createTempDirectory("imagejai-bundled-agent").resolve("agent");

        Path installed = BundledAgentWorkspace.install(target, source);

        assertTrue(Files.isRegularFile(installed.resolve("ij.py")));
        assertTrue(Files.isRegularFile(
                installed.resolve("gemma4_31b").resolve("__main__.py")));
        assertTrue(Files.isRegularFile(
                installed.resolve("providers").resolve("agent_cli.py")));
        assertTrue(Files.isRegularFile(
                installed.resolve("console").resolve("__main__.py")));
        assertTrue(Files.isRegularFile(
                installed.resolve("console").resolve("requirements.txt")));
        assertFalse(Files.exists(
                installed.resolve("providers").resolve("proxy.auth")));
        assertFalse(Files.exists(
                installed.resolve("providers").resolve("proxy.runtime.json")));
        assertFalse(Files.exists(
                installed.resolve("gemma4_31b").resolve("tests")));
    }

    @Test
    public void extractsRuntimeFromPackagedJarLayout() throws Exception {
        Path temp = Files.createTempDirectory("imagejai-agent-jar");
        Path jar = temp.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            addEntry(out, "agent-runtime/ij.py", "# client");
            addEntry(out, "agent-runtime/gemma4_31b/__main__.py", "# entrypoint");
            addEntry(out, "agent-runtime/console/__main__.py", "# console");
            addEntry(out, "agent-runtime/console/requirements.txt", "textual>=6.2,<7");
        }

        Path installed = BundledAgentWorkspace.install(temp.resolve("agent"), jar);

        assertTrue(Files.isRegularFile(installed.resolve("ij.py")));
        assertTrue(Files.isRegularFile(
                installed.resolve("gemma4_31b").resolve("__main__.py")));
        assertTrue(Files.isRegularFile(
                installed.resolve("console").resolve("requirements.txt")));
    }

    private static void addEntry(JarOutputStream out, String name, String text)
            throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
