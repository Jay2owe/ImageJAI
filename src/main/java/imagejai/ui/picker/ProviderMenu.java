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
 * <p>Phase D ships the curated rows + credential leaf only. Phase E wires the
 * status-icon column in each row to the deep-link / cached-error dialog.
 * Auto-discovered subsection and "↻ Refresh local Ollama list" leaf are Phase G.
 */
public class ProviderMenu extends JMenu {

    public interface ModelLaunchListener {
        void onLaunchRequested(ModelEntry entry);
    }

    public interface InstallerListener {
        void onCredentialsRequested(String providerId);
    }

    /** Status-icon column click — caller decides whether to deep-link. */
    public interface StatusListener {
        boolean onStatusIconClicked(ModelEntry entry,
                                    ModelMenuItem.ProviderStatusIcon status);
    }

    private final ProviderEntry providerEntry;

    public ProviderMenu(ProviderEntry providerEntry,
                        ModelLaunchListener launchListener,
                        ModelMenuItem.PinToggleListener pinToggleListener,
                        InstallerListener installerListener) {
        this(providerEntry, launchListener, pinToggleListener, installerListener, null);
    }

    public ProviderMenu(ProviderEntry providerEntry,
                        ModelLaunchListener launchListener,
                        ModelMenuItem.PinToggleListener pinToggleListener,
                        InstallerListener installerListener,
                        StatusListener statusListener) {
        super(buildHeader(providerEntry));
        this.providerEntry = providerEntry;
        rebuildChildren(launchListener, pinToggleListener, installerListener, statusListener);
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

    private static ModelMenuItem.ProviderStatusIcon iconFor(ProviderEntry.Status status) {
        switch (status) {
            case READY: return ModelMenuItem.ProviderStatusIcon.READY;
            case UNAVAILABLE: return ModelMenuItem.ProviderStatusIcon.UNAVAILABLE;
            case NEEDS_SETUP:
            default: return ModelMenuItem.ProviderStatusIcon.NEEDS_SETUP;
        }
    }

    private void rebuildChildren(final ModelLaunchListener launchListener,
                                 final ModelMenuItem.PinToggleListener pinToggleListener,
                                 final InstallerListener installerListener,
                                 final StatusListener statusListener) {
        removeAll();
        ModelMenuItem.ProviderStatusIcon icon = iconFor(providerEntry.status());
        for (final ModelEntry entry : providerEntry.models()) {
            ModelMenuItem.StatusIconClickListener bridge = statusListener == null
                    ? null
                    : (model, status) -> statusListener.onStatusIconClicked(model, status);
            ModelMenuItem item = new ModelMenuItem(entry, icon, pinToggleListener, bridge);
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
