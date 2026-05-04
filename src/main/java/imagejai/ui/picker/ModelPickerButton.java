package imagejai.ui.picker;

import imagejai.config.Settings;
import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.ProviderEntry;
import imagejai.engine.picker.ProviderRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
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

    /**
     * Refresh result returned by the user-supplied refresh task.
     * Counts let the strip render "X new, Y removed since last check" per 05 §8.3.
     */
    public static final class RefreshOutcome {
        public final ProviderRegistry newRegistry;
        public final int newCount;
        public final int removedCount;
        public final List<String> failedProviders;

        public RefreshOutcome(ProviderRegistry newRegistry,
                              int newCount,
                              int removedCount,
                              List<String> failedProviders) {
            this.newRegistry = newRegistry;
            this.newCount = newCount;
            this.removedCount = removedCount;
            this.failedProviders = failedProviders == null
                    ? java.util.Collections.<String>emptyList()
                    : new ArrayList<String>(failedProviders);
        }
    }

    /**
     * Background-thread refresh hook. Phase G's
     * {@link imagejai.engine.picker.ProviderDiscovery} fan-out is
     * the production implementation; tests inject a stub.
     */
    public interface RefreshTask {
        RefreshOutcome refresh() throws Exception;
    }

    private final Settings settings;
    private ProviderRegistry registry;
    private SelectionListener selectionListener;
    private SettingsLink settingsLink;
    private InstallerLink installerLink;
    private RefreshTask refreshTask;
    private ProviderTierGate tierGate;

    private final JPopupMenu popup = new JPopupMenu();
    private JLabel headerStatusLabel;
    private JButton headerRefreshButton;

    /**
     * Tracks the currently-mounted {@link ProviderMenu} for each provider id so
     * {@link #applyProviderRefresh} can swap a single submenu in-place rather
     * than rebuilding the whole popup. Per Phase D acceptance + 05 §3.9.
     */
    private final Map<String, ProviderMenu> providerSubmenus = new LinkedHashMap<>();

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

    /**
     * Inject the tier-safety gate that fires the first-use dialogs before
     * launch (Phase H). When null, launches proceed unguarded — used by the
     * legacy unit tests written before Phase H.
     */
    public void setTierGate(ProviderTierGate gate) {
        this.tierGate = gate;
    }

    public ProviderTierGate tierGate() {
        return tierGate;
    }

    public void setRefreshTask(RefreshTask refreshTask) {
        this.refreshTask = refreshTask;
        if (headerRefreshButton != null) {
            headerRefreshButton.setEnabled(refreshTask != null);
        }
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
        providerSubmenus.clear();
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
            ProviderMenu submenu = buildProviderMenu(provider);
            providerSubmenus.put(provider.providerId(), submenu);
            popup.add(submenu);
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

    private ProviderMenu buildProviderMenu(ProviderEntry provider) {
        return new ProviderMenu(
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
                });
    }

    /**
     * Per-provider in-place refresh per Phase D acceptance + 05 §3.9. Replaces
     * just the named provider's submenu instead of calling {@link #rebuildPopup}.
     * If the popup is currently visible the new submenu inherits its position
     * so the user doesn't see flicker.
     *
     * <p>{@code newEntry} is the {@link ProviderEntry} returned by the per-provider
     * fetcher; the registry is updated via {@link ProviderRegistry#refreshProvider}
     * so {@link #lookup} stays consistent with the popup contents.
     */
    public void applyProviderRefresh(String providerId, ProviderEntry newEntry) {
        if (providerId == null || newEntry == null) {
            return;
        }
        registry = registry.refreshProvider(providerId, newEntry);
        ProviderMenu existing = providerSubmenus.get(providerId);
        if (existing == null) {
            // Provider wasn't previously rendered (popup not built yet) — fall
            // back to a full rebuild so the new entry shows up next time.
            rebuildPopup();
            return;
        }
        int index = -1;
        for (int i = 0; i < popup.getComponentCount(); i++) {
            if (popup.getComponent(i) == existing) {
                index = i;
                break;
            }
        }
        ProviderMenu replacement = buildProviderMenu(newEntry);
        providerSubmenus.put(providerId, replacement);
        if (index >= 0) {
            popup.remove(index);
            popup.insert(replacement, index);
            popup.revalidate();
            popup.repaint();
        } else {
            // Submenu was tracked but not in the popup component list any more
            // (race with rebuild). Push it back so the dropdown still shows it.
            popup.add(replacement);
        }
        refreshCaption();
    }

    /**
     * Kick off a {@link ProviderRegistry.RefreshWorker} that fetches the named
     * provider's entry off-EDT then calls {@link #applyProviderRefresh} on the
     * EDT once {@code fetcher} returns. Any exception swallows the swap and is
     * surfaced via the header status label.
     */
    public void refreshProviderAsync(final String providerId,
                                     Callable<ProviderEntry> fetcher) {
        if (providerId == null || fetcher == null) {
            return;
        }
        ProviderRegistry.RefreshWorker worker = new ProviderRegistry.RefreshWorker(
                providerId, fetcher,
                new ProviderRegistry.RefreshWorker.Applier() {
                    @Override
                    public void apply(String pid, ProviderEntry newEntry, Throwable error) {
                        if (error != null) {
                            handleRefreshFailure(error instanceof Exception
                                    ? (Exception) error
                                    : new RuntimeException(error));
                            return;
                        }
                        if (newEntry != null) {
                            applyProviderRefresh(pid, newEntry);
                        }
                    }
                });
        worker.execute();
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

    private Component buildHeaderStrip() {
        JPanel strip = new JPanel(new BorderLayout(8, 0));
        strip.setOpaque(true);
        strip.setBackground(UIManager.getColor("MenuItem.background"));
        strip.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        headerStatusLabel = new JLabel("Models");
        headerStatusLabel.setForeground(new Color(80, 80, 90));
        strip.add(headerStatusLabel, BorderLayout.WEST);

        headerRefreshButton = new JButton("↻ refresh");
        headerRefreshButton.setFocusable(false);
        headerRefreshButton.setEnabled(refreshTask != null);
        headerRefreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                runRefresh();
            }
        });
        // Click on refresh must NOT close the popup — same MouseEvent.consume()
        // pattern as the pin star (05 §8.1).
        headerRefreshButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                e.consume();
            }
        });

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(Box.createHorizontalGlue());
        right.add(headerRefreshButton);
        strip.add(right, BorderLayout.EAST);
        return strip;
    }

    private void runRefresh() {
        final RefreshTask task = this.refreshTask;
        if (task == null) {
            return;
        }
        if (headerStatusLabel != null) {
            headerStatusLabel.setText("⟳ refreshing…");
        }
        if (headerRefreshButton != null) {
            headerRefreshButton.setEnabled(false);
        }
        SwingWorker<RefreshOutcome, Void> worker = new SwingWorker<RefreshOutcome, Void>() {
            @Override
            protected RefreshOutcome doInBackground() throws Exception {
                return task.refresh();
            }

            @Override
            protected void done() {
                try {
                    RefreshOutcome outcome = get();
                    handleRefreshSuccess(outcome);
                } catch (Exception ex) {
                    handleRefreshFailure(ex);
                } finally {
                    if (headerRefreshButton != null) {
                        headerRefreshButton.setEnabled(true);
                    }
                }
            }
        };
        worker.execute();
    }

    private void handleRefreshSuccess(RefreshOutcome outcome) {
        if (outcome == null) {
            if (headerStatusLabel != null) {
                headerStatusLabel.setText("Models");
            }
            return;
        }
        if (outcome.newRegistry != null) {
            setRegistry(outcome.newRegistry);
        }
        if (!outcome.failedProviders.isEmpty()) {
            String first = outcome.failedProviders.get(0);
            String message = outcome.failedProviders.size() == 1
                    ? "Couldn't reach " + first + " — using cached list. Retry?"
                    : "Couldn't reach " + outcome.failedProviders.size() + " providers — using cached list.";
            if (headerStatusLabel != null) {
                headerStatusLabel.setText("⚠ " + message);
            }
            return;
        }
        if (headerStatusLabel != null) {
            headerStatusLabel.setText("Models · "
                    + outcome.newCount + " new, "
                    + outcome.removedCount + " removed since last check");
        }
    }

    private void handleRefreshFailure(Exception ex) {
        if (headerStatusLabel != null) {
            headerStatusLabel.setText("⚠ Refresh failed — using cached list. Retry?");
        }
        // Quietly: don't pop a JOptionPane — strip text is the user-visible
        // surface per 05 §8.4. Logging happens through the registry's own
        // pipe; here we only need to show the cached state survived.
        SwingUtilities.invokeLater(() -> {
            if (headerRefreshButton != null) {
                headerRefreshButton.setToolTipText(ex.getMessage());
            }
        });
    }

    /** Test hook — returns the strip text so headless tests can assert it. */
    String headerStripText() {
        return headerStatusLabel == null ? "" : headerStatusLabel.getText();
    }

    /**
     * Test hook — invoke the refresh path synchronously without bouncing
     * through a {@link SwingWorker}, used by unit tests to assert the strip
     * transitions through fetching → success/failure states.
     */
    void runRefreshForTest() {
        RefreshTask task = this.refreshTask;
        if (task == null) {
            return;
        }
        if (headerStatusLabel != null) {
            headerStatusLabel.setText("⟳ refreshing…");
        }
        try {
            RefreshOutcome outcome = task.refresh();
            handleRefreshSuccess(outcome);
        } catch (Exception ex) {
            handleRefreshFailure(ex);
        }
    }


    private void handleLaunch(ModelEntry entry) {
        if (entry == null) {
            return;
        }
        if (tierGate != null) {
            java.awt.Frame owner = (java.awt.Frame) javax.swing.SwingUtilities
                    .getAncestorOfClass(java.awt.Frame.class, this);
            ProviderTierGate.Decision decision = tierGate.check(owner, entry);
            if (decision == ProviderTierGate.Decision.CANCEL_PICK_FREE
                    || decision == ProviderTierGate.Decision.CANCEL_PICK_CURATED) {
                // Re-open the popup so the user can pick again. The current
                // build does not yet carry a "filtered" popup variant — Phase H
                // surfaces the affordance, Phase I will add the filter chip.
                showPopup();
                return;
            }
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
