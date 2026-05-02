package imagejai.ui.picker;

import imagejai.config.Settings;
import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.ProviderEntry;
import imagejai.engine.picker.ProviderRegistry;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Header button replacing the flat {@code JComboBox<String> agentSelector}.
 * Caption shows the currently selected model; clicking opens a cascading
 * provider→model {@link JPopupMenu}.
 *
 * <p>Phase D scope per docs/multi_provider/05_ui_design.md §2.2: header strip
 * with refresh placeholder, optional pinned section, one {@link ProviderMenu}
 * per canonical provider, "Open multi-provider settings…" leaf at the bottom.
 * Auto-discovered subsection, hover-card wiring on every row, and right-click
 * menu are deferred to Phase G/H.
 */
public class ModelPickerButton extends JButton {

    public interface SelectionListener {
        void onSelectionChanged(ModelEntry entry);
        void onLaunchRequested(ModelEntry entry);
    }

    public interface SettingsLink {
        void openMultiProviderSettings();
    }

    public interface InstallerLink {
        void openInstallerForProvider(String providerId);
    }

    private final Settings settings;
    private ProviderRegistry registry;
    private SelectionListener selectionListener;
    private SettingsLink settingsLink;
    private InstallerLink installerLink;

    private final JPopupMenu popup = new JPopupMenu();

    public ModelPickerButton(ProviderRegistry registry, Settings settings) {
        super(captionFor(settings, registry));
        this.registry = registry == null ? ProviderRegistry.empty() : registry;
        this.settings = settings;
        addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showPopup();
            }
        });
        rebuildPopup();
    }

    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }

    public void setSettingsLink(SettingsLink link) {
        this.settingsLink = link;
    }

    public void setInstallerLink(InstallerLink link) {
        this.installerLink = link;
    }

    public void setRegistry(ProviderRegistry registry) {
        this.registry = registry == null ? ProviderRegistry.empty() : registry;
        rebuildPopup();
        refreshCaption();
    }

    public void showPopup() {
        popup.show(this, 0, getHeight());
    }

    public void refreshCaption() {
        setText(captionFor(settings, registry));
    }

    private static String captionFor(Settings settings, ProviderRegistry registry) {
        if (settings != null && registry != null
                && settings.selectedProvider != null
                && settings.selectedModelId != null) {
            ModelEntry entry = registry.lookup(settings.selectedProvider, settings.selectedModelId);
            if (entry != null) {
                return entry.displayName() + "  ▾";
            }
        }
        return "Pick a model  ▾";
    }

    private void rebuildPopup() {
        popup.removeAll();
        popup.add(buildHeaderStrip());
        popup.add(new JSeparator());

        List<ModelMenuItem> pinnedItems = collectPinned();
        if (!pinnedItems.isEmpty()) {
            JMenuItem pinnedHeader = new JMenuItem("★ Pinned");
            pinnedHeader.setEnabled(false);
            popup.add(pinnedHeader);
            for (ModelMenuItem item : pinnedItems) {
                popup.add(item);
            }
            popup.add(new JSeparator());
        }

        for (ProviderEntry provider : registry.providers()) {
            popup.add(new ProviderMenu(
                    provider,
                    new ProviderMenu.ModelLaunchListener() {
                        @Override
                        public void onLaunchRequested(ModelEntry entry) {
                            handleLaunch(entry);
                        }
                    },
                    new ModelMenuItem.PinToggleListener() {
                        @Override
                        public void onPinToggled(ModelEntry entry, boolean nowPinned) {
                            // Phase G persists pins to models_local.yaml; Phase D
                            // keeps them in-memory so the affordance is visible.
                        }
                    },
                    new ProviderMenu.InstallerListener() {
                        @Override
                        public void onCredentialsRequested(String providerId) {
                            if (installerLink != null) {
                                installerLink.openInstallerForProvider(providerId);
                            }
                        }
                    }));
        }

        popup.add(new JSeparator());
        JMenuItem settingsItem = new JMenuItem("⚙  Open multi-provider settings…");
        settingsItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (settingsLink != null) {
                    settingsLink.openMultiProviderSettings();
                }
            }
        });
        popup.add(settingsItem);
    }

    private List<ModelMenuItem> collectPinned() {
        List<ModelMenuItem> out = new ArrayList<ModelMenuItem>();
        for (ProviderEntry provider : registry.providers()) {
            for (final ModelEntry entry : provider.models()) {
                if (!entry.pinned()) {
                    continue;
                }
                ModelMenuItem item = new ModelMenuItem(entry, null);
                item.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        handleLaunch(entry);
                    }
                });
                out.add(item);
            }
        }
        return out;
    }

    private JMenuItem buildHeaderStrip() {
        JMenuItem header = new JMenuItem("Models");
        header.setEnabled(false);
        return header;
    }

    private void handleLaunch(ModelEntry entry) {
        if (entry == null) {
            return;
        }
        if (settings != null) {
            settings.selectedProvider = entry.providerId();
            settings.selectedModelId = entry.modelId();
            settings.save();
        }
        refreshCaption();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(entry);
            selectionListener.onLaunchRequested(entry);
        }
    }
}
