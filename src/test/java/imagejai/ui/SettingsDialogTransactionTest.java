package imagejai.ui;

import imagejai.config.Settings;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class SettingsDialogTransactionTest {

    @Test
    public void confirmedBackendEditAppliesToLiveSettingsAndReportsRebuild()
            throws Exception {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        Settings live = new Settings();
        live.configs.clear();
        Settings.ModelConfig config = new Settings.ModelConfig(
                "local", "ollama", "model-a");
        config.url = "http://127.0.0.1:11434";
        live.configs.add(config);
        live.activeConfigId = config.id;

        final SettingsDialog[] dialog = new SettingsDialog[1];
        SwingUtilities.invokeAndWait(() -> dialog[0] = new SettingsDialog(null, live));
        Field comboField = SettingsDialog.class.getDeclaredField("ollamaModelCombo");
        comboField.setAccessible(true);
        @SuppressWarnings("unchecked")
        JComboBox<String> combo = (JComboBox<String>) comboField.get(dialog[0]);
        Method save = SettingsDialog.class.getDeclaredMethod("save");
        save.setAccessible(true);

        SwingUtilities.invokeAndWait(() -> {
            combo.setSelectedItem("model-b");
            try {
                save.invoke(dialog[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals("model-b", live.getActiveConfig().model);
        assertTrue(dialog[0].wasConfirmed());
        assertTrue(dialog[0].backendSettingsChanged());
    }
}
