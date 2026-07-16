package imagejai.terminal;

import imagejai.engine.AgentLauncher;
import org.junit.Test;

import java.io.File;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class AgentRegistryTest {

    @Test
    public void bundledGemmaModuleKeepsGemmaAgentId() {
        AgentLauncher.AgentInfo info = new AgentLauncher.AgentInfo(
                "Gemma 4 31B",
                "python -m gemma4_31b",
                "",
                null,
                "",
                true,
                "gemma4:31b-cloud");

        assertEquals("gemma4_31b", AgentRegistry.agentId(info));
    }

    @Test
    public void imagejaiAgentAliasKeepsGemmaAgentId() {
        AgentLauncher.AgentInfo info = new AgentLauncher.AgentInfo(
                "ImageJAI Agent",
                "imagejai_agent",
                "",
                null,
                "",
                true,
                "gemma4:31b-cloud");

        assertEquals("gemma4_31b", AgentRegistry.agentId(info));
    }

    @Test
    public void providerAgentCliUsesProviderAgentId() {
        AgentLauncher.AgentInfo info = new AgentLauncher.AgentInfo(
                "Claude Opus 4.7",
                "python -m agent.providers.agent_cli --provider anthropic --model claude-opus-4-7",
                "",
                null,
                "",
                false,
                "");

        assertEquals("provider_anthropic", AgentRegistry.agentId(info));
    }

    @Test
    public void deniedCommandDirectoryIsNotCachedAndSucceedsAfterAccessIsRestored()
            throws Exception {
        Path workspace = Files.createTempDirectory("registry-restored");
        Path commands = Files.createDirectories(workspace.resolve(".claude/commands"));
        Files.write(commands.resolve("analyse.md"), new byte[]{1});
        AtomicInteger opens = new AtomicInteger();
        AgentRegistry.DirectorySource source = path -> {
            if (opens.getAndIncrement() == 0) {
                throw new AccessDeniedException(path.toString());
            }
            return Files.newDirectoryStream(path);
        };
        AgentLauncher.AgentInfo info = claudeInfo();

        try {
            AgentRegistry.userCommandsResult(info, workspace.toFile(), source, 1000L);
            throw new AssertionError("Expected directory_unreadable");
        } catch (AgentRegistry.CommandScanException expected) {
            assertEquals("directory_unreadable", expected.code());
        }
        AgentRegistry.UserCommandsResult restored = AgentRegistry.userCommandsResult(
                info, workspace.toFile(), source, 1001L);
        assertEquals(1, restored.commands().size());
        assertEquals("/analyse", restored.commands().get(0).command);
        assertEquals(1, restored.entriesInspected());
    }

    @Test
    public void commandEnumerationStopsAtSafetyCap() throws Exception {
        Path workspace = Files.createTempDirectory("registry-cap");
        Path commands = Files.createDirectories(workspace.resolve(".claude/commands"));
        Path repeated = Files.write(commands.resolve("ignored.bin"), new byte[]{1});
        AgentRegistry.DirectorySource source = path -> repeatingDirectory(repeated,
                AgentRegistry.MAX_USER_COMMAND_DIRECTORY_ENTRIES + 1);

        try {
            AgentRegistry.userCommandsResult(claudeInfo(), workspace.toFile(), source, 2000L);
            throw new AssertionError("Expected directory_entry_cap");
        } catch (AgentRegistry.CommandScanException expected) {
            assertEquals("directory_entry_cap", expected.code());
            assertEquals(AgentRegistry.MAX_USER_COMMAND_DIRECTORY_ENTRIES + 1,
                    expected.entriesInspected());
        }
    }

    private static AgentLauncher.AgentInfo claudeInfo() {
        return new AgentLauncher.AgentInfo("Claude", "claude", "", null,
                "", false, "");
    }

    private static DirectoryStream<Path> repeatingDirectory(final Path path, final int count) {
        return new DirectoryStream<Path>() {
            @Override public Iterator<Path> iterator() {
                return new Iterator<Path>() {
                    int index;
                    @Override public boolean hasNext() { return index < count; }
                    @Override public Path next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        index++;
                        return path;
                    }
                };
            }
            @Override public void close() { }
        };
    }
}
