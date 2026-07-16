package imagejai.config;

import imagejai.ui.installer.ProviderCredentials;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SettingsTransactionTest {

    @Test
    public void detachedCancelChangesNeitherLiveStateNorPersistedBytes() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("imagejai-settings-cancel");
        try {
            System.setProperty("user.home", home.toString());
            Settings live = configuredSettings(home);
            live.save();
            Path config = Settings.getConfigDir().resolve("config.json");
            byte[] before = Files.readAllBytes(config);
            String beforeFingerprint = live.backendFingerprint();

            Settings working = live.detachedCopy();
            working.getActiveConfig().model = "cancelled-model";
            working.tcpPort = 9999;
            working.save();

            assertEquals(beforeFingerprint, live.backendFingerprint());
            assertEquals(7746, live.tcpPort);
            assertArrayEquals(before, Files.readAllBytes(config));
        } finally {
            restoreHome(oldHome);
        }
    }

    @Test
    public void applyCommitsDeepCopyAndDetectsBackendChange() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("imagejai-settings-save");
        try {
            System.setProperty("user.home", home.toString());
            Settings live = configuredSettings(home);
            String before = live.backendFingerprint();
            Settings working = live.detachedCopy();
            working.getActiveConfig().model = "new-model";
            working.tcpPort = 8123;

            live.applyFrom(working);

            assertNotEquals(before, live.backendFingerprint());
            assertEquals("new-model", live.getActiveConfig().model);
            assertEquals(8123, live.tcpPort);
            working.getActiveConfig().model = "mutated-after-apply";
            assertEquals("new-model", live.getActiveConfig().model);
            assertFalse(live.detachedCopy() == live);
        } finally {
            restoreHome(oldHome);
        }
    }

    @Test
    public void corruptSettingsLoadIsObservableWithoutExposingContents() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("imagejai-settings-corrupt");
        try {
            System.setProperty("user.home", home.toString());
            Path config = Settings.getConfigDir().resolve("config.json");
            Files.createDirectories(config.getParent());
            Files.write(config, "{not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Settings.load();

            assertTrue(Settings.lastPersistenceError().contains("load failed"));
            assertFalse(Settings.lastPersistenceError().contains("not-json"));
        } finally {
            restoreHome(oldHome);
        }
    }

    private static Settings configuredSettings(Path home) {
        Settings settings = new Settings();
        settings.configs.clear();
        Settings.ModelConfig config = new Settings.ModelConfig("test", "ollama", "model-a");
        config.url = "http://127.0.0.1:11434";
        settings.configs.add(config);
        settings.activeConfigId = config.id;
        settings.tcpPort = 7746;
        settings.setProviderCredentials(new ProviderCredentials(home.resolve("secrets")));
        return settings;
    }

    private static void restoreHome(String value) {
        if (value == null) System.clearProperty("user.home");
        else System.setProperty("user.home", value);
    }
}
