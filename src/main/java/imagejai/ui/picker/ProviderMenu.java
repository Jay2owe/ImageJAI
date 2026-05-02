package imagejai.ui.picker;

import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.ProviderEntry;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Cascading submenu for one provider. Header carries provider display name +
 * status icon; children are the {@link ModelMenuItem} rows for the provider's
 * curated entries plus an "Add credentials…" leaf for unconfigured providers.
 *
 * <p>Phase D ships the curated rows + credential leaf only. Auto-discovered
 * subsection and "↻ Refresh local Ollama list" leaf are Phase G.
 */
public class ProviderMenu extends JMenu {

    public interface ModelLaunchListener {
        void onLaunchRequested(ModelEntry entry);
    }

    public interface InstallerListener {
        void onCredentialsRequested(String providerId);
    }

    private final ProviderEntry providerEntry;

    public ProviderMenu(ProviderEntry providerEntry,
                        ModelLaunchListener launchListener,
                        ModelMenuItem.PinToggleListener pinToggleListener,
                        InstallerListener installerListener) {
        super(buildHeader(providerEntry));
        this.providerEntry = providerEntry;
        rebuildChildren(launchListener, pinToggleListener, installerListener);
    }

    public ProviderEntry providerEntry() {
        return providerEntry;
    }

    private static String buildHeader(ProviderEntry providerEntry) {
        String suffix;
        switch (providerEntry.status()) {
            case READY: suffix = "  ✓"; break;
            case UNAVAILABLE: suffix = "  ✗"; break;
            case NEEDS_SETUP:
            default: suffix = "  ⚠"; break;
        }
        return providerEntry.displayName() + suffix;
    }

    private void rebuildChildren(final ModelLaunchListener launchListener,
                                 final ModelMenuItem.PinToggleListener pinToggleListener,
                                 final InstallerListener installerListener) {
        removeAll();
        for (final ModelEntry entry : providerEntry.models()) {
            ModelMenuItem item = new ModelMenuItem(entry, pinToggleListener);
            item.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (launchListener != null) {
                        launchListener.onLaunchRequested(entry);
                    }
                }
            });
            add(item);
        }
        if (providerEntry.status() != ProviderEntry.Status.READY) {
            if (getMenuComponentCount() > 0) {
                addSeparator();
            }
            JMenuItem addCreds = new JMenuItem(
                    providerEntry.models().isEmpty()
                            ? "✎ Add credentials…"
                            : "✎ Add/edit credentials…");
            addCreds.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (installerListener != null) {
                        installerListener.onCredentialsRequested(providerEntry.providerId());
                    }
                }
            });
            add(addCreds);
        }
    }
}
