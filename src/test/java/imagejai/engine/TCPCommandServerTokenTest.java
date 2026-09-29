package imagejai.engine;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Regression coverage for the fail-closed server-token lifecycle. */
public class TCPCommandServerTokenTest {

    @Test
    public void generatedTokenIsDurableBeforeItIsReturned() throws Exception {
        Path directory = Files.createTempDirectory("imagejai-token-");
        Path tokenFile = directory.resolve("server-token");
        try {
            String token = TCPCommandServer.loadOrGenerateToken(tokenFile);

            assertTrue(token.length() >= 32);
            assertEquals(token, new String(Files.readAllBytes(tokenFile),
                    StandardCharsets.UTF_8).trim());
            try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
                assertFalse("atomic write must not leave a pending token",
                        entries.anyMatch(path -> path.getFileName().toString()
                                .startsWith(".server-token-")));
            }
        } finally {
            Files.deleteIfExists(tokenFile);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void persistenceFailureNeverReturnsAnInMemoryOnlyToken() throws Exception {
        Path directory = Files.createTempDirectory("imagejai-token-failure-");
        Path parentIsFile = directory.resolve("not-a-directory");
        Files.write(parentIsFile, new byte[] {1});
        try {
            try {
                TCPCommandServer.loadOrGenerateToken(
                        parentIsFile.resolve("server-token"));
                fail("persistence failure must abort token initialization");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains(
                        "Failed to persist server token"));
            }
        } finally {
            Files.deleteIfExists(parentIsFile);
            Files.deleteIfExists(directory);
        }
    }
}
