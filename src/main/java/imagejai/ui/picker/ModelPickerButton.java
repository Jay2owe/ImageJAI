package imagejai.ui.picker;

import imagejai.config.Settings;
import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.ProviderEntry;
import imagejai.engine.picker.ProviderRegistry;
import imagejai.ui.installer.CachedErrorDialog;

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
    private final ProviderRegistry.RefreshGeneration refreshGeneration =
            new ProviderRegistry.RefreshGeneration();
    private SwingWorker<RefreshOutcome, Void> activeRefreshWorker;
    private final Map<String, ProviderRegistry.RefreshGeneration> providerRefreshGenerations =
            new LinkedHashMap<String, ProviderRegistry.RefreshGeneration>();
    private final Map<String, ProviderRegistry.RefreshWorker> providerRefreshWorkers =
            new LinkedHashMap<String, ProviderRegistry.RefreshWorker>();
    private ProviderTierGate tierGate;
    private PinListener pinListener;
    private PopupFilter popupFilter = PopupFilter.ALL;
    /** Live type-to-filter query applied on top of the tier filter. */
    private String searchText = "";

    private final JPopupMenu popup = new JPopupMenu();
    private JLabel headerStatusLabel;
    private JButton headerRefreshButton;
    private JButton headerFreeToggle;
    private javax.swing.JTextField searchField;

    /**
     * Tracks the currently-mounted {@link ProviderMenu} for each provider id so
     * {@link #applyProviderRefresh} can swap a single submenu in-place rather
     * than rebuilding the whole popup. Per Phase D acceptance + 05 §3.9.
     */
    private final Map<String, ProviderMenu> providerSubmenus = new LinkedHashMap<>();

    private enum PopupFilter {
        ALL,
        FREE_ONLY,
        CURATED_ONLY
    }

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

    /** Listener notified when the user toggles a model's pin star. */
    public interface PinListener {
        void onPinChanged(ModelEntry entry, boolean nowPinned);
    }

    /**
     * Inject the hook that persists pins to {@code models_local.yaml}. When
     * null, the star is in-memory only (the pre-fix Phase D behaviour).
     */
    public void setPinListener(PinListener pinListener) {
        this.pinListener = pinListener;
    }

    /**
     * Shared pin-toggle handler used by BOTH the provider submenus and the
     * pinned section, so toggling the star anywhere persists via the panel
     * (verifier #6 / #3-3). With no PinListener wired, the star is in-memory
     * only — the pre-fix behaviour.
     */
    private ModelMenuItem.PinToggleListener pinToggleListener() {
        return new ModelMenuItem.PinToggleListener() {
            @Override
            public void onPinToggled(ModelEntry entry, boolean nowPinned) {
                if (pinListener != null) {
                    pinListener.onPinChanged(entry, nowPinned);
                }
            }
        };
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
        popupFilter = PopupFilter.ALL;
        searchText = "";
        rebuildPopup();
        popup.show(this, 0, getHeight());
    }

    private void showFilteredPopup(PopupFilter filter) {
        popupFilter = filter == null ? PopupFilter.ALL : filter;
        rebuildPopup();
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
        List<ProviderEntry> providers = visibleProviders();
        popup.add(buildHeaderStrip());
        popup.add(new JSeparator());

        // Free + keyless local-daemon models first, so a biologist sees the
        // no-API-key options (Ollama, LM Studio, Jan, llama.cpp, vLLM) above the
        // paid cloud providers. Respects the active search/tier filter.
        List<ModelMenuItem> freeLocalItems = collectFreeLocal(providers);
        if (!freeLocalItems.isEmpty()) {
            JMenuItem freeLocalHeader = new JMenuItem("🆓 Free & local (no key)");
            freeLocalHeader.setEnabled(false);
            popup.add(freeLocalHeader);
            for (ModelMenuItem item : freeLocalItems) {
                popup.add(item);
            }
            popup.add(new JSeparator());
        }

        List<ModelMenuItem> pinnedItems = collectPinned(providers);
        if (!pinnedItems.isEmpty()) {
            JMenuItem pinnedHeader = new JMenuItem("★ Pinned");
            pinnedHeader.setEnabled(false);
            popup.add(pinnedHeader);
            for (ModelMenuItem item : pinnedItems) {
                popup.add(item);
            }
            popup.add(new JSeparator());
        }

        for (ProviderEntry provider : providers) {
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

    private List<ProviderEntry> visibleProviders() {
        boolean filtering = popupFilter != PopupFilter.ALL
                || (searchText != null && !searchText.trim().isEmpty());
        List<ProviderEntry> out = new ArrayList<ProviderEntry>();
        for (ProviderEntry provider : registry.providers()) {
            if (!filtering) {
                out.add(provider);
                continue;
            }
            List<ModelEntry> models = new ArrayList<ModelEntry>();
            for (ModelEntry entry : provider.models()) {
                if (matchesFilter(entry) && matchesQuery(entry, searchText)) {
                    models.add(entry);
                }
            }
            if (!models.isEmpty()) {
                out.add(new ProviderEntry(provider.providerId(), provider.displayName(),
                        provider.status(), provider.lastError(), models));
            }
        }
        return out;
    }

    /**
     * Case-insensitive substring match over a model's display name, id, and
     * provider key. Empty query matches everything. Package-private + static so
     * it can be unit-tested without standing up the Swing popup.
     */
    static boolean matchesQuery(ModelEntry entry, String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        if (entry == null) {
            return false;
        }
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        return containsIgnoreCase(entry.displayName(), q)
                || containsIgnoreCase(entry.modelId(), q)
                || containsIgnoreCase(entry.providerId(), q);
    }

    private static boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        return haystack != null
                && haystack.toLowerCase(java.util.Locale.ROOT).contains(lowerNeedle);
    }

    /** Rebuild the popup for the current filter/search and re-show it in place. */
    private void applyFilterAndReshow() {
        rebuildPopup();
        popup.show(this, 0, getHeight());
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (searchField != null) {
                    searchField.requestFocusInWindow();
                    searchField.setCaretPosition(searchField.getText().length());
                }
            }
        });
    }

    private boolean matchesFilter(ModelEntry entry) {
        if (entry == null || popupFilter == PopupFilter.ALL) {
            return true;
        }
        if (popupFilter == PopupFilter.FREE_ONLY) {
            return entry.tier() == ModelEntry.Tier.FREE
                    || entry.tier() == ModelEntry.Tier.FREE_WITH_LIMITS;
        }
        if (popupFilter == PopupFilter.CURATED_ONLY) {
            return entry.curated() && entry.tier() != ModelEntry.Tier.UNCURATED;
        }
        return true;
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
                pinToggleListener(),
                new ProviderMenu.InstallerListener() {
                    @Override
                    public void onCredentialsRequested(String providerId) {
                        if (installerLink != null) {
                            installerLink.openInstallerForProvider(providerId);
                        }
                    }
                },
                new ProviderMenu.StatusListener() {
                    @Override
                    public boolean onStatusIconClicked(ModelEntry entry,
                                                       ModelMenuItem.ProviderStatusIcon status) {
                        return handleStatusIconClick(entry == null ? null : entry.providerId(),
                                status);
                    }

                    @Override
                    public boolean onProviderStatusIconClicked(ProviderEntry entry,
                                                               ModelMenuItem.ProviderStatusIcon status) {
                        return handleStatusIconClick(entry == null ? null : entry.providerId(),
                                status);
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
        ProviderRegistry.RefreshGeneration generation =
                providerRefreshGenerations.get(providerId);
        if (generation == null) {
            generation = new ProviderRegistry.RefreshGeneration();
            providerRefreshGenerations.put(providerId, generation);
        }
        ProviderRegistry.RefreshWorker previous = providerRefreshWorkers.get(providerId);
        if (previous != null) previous.cancel(true);
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
                }, generation);
        providerRefreshWorkers.put(providerId, worker);
        worker.execute();
    }

    private List<ModelMenuItem> collectPinned(List<ProviderEntry> providers) {
        List<ModelMenuItem> out = new ArrayList<ModelMenuItem>();
        for (ProviderEntry provider : providers) {
            for (final ModelEntry entry : provider.models()) {
                if (!entry.pinned()) {
                    continue;
                }
                ModelMenuItem item = new ModelMenuItem(entry, pinToggleListener());
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

    /**
     * Free / free-with-limits models from keyless local-daemon providers
     * (Ollama, LM Studio, Jan, llama.cpp, vLLM). Surfaced as a quick-access
     * group at the top of the popup. Package-private accessor below for tests.
     */
    private List<ModelMenuItem> collectFreeLocal(List<ProviderEntry> providers) {
        List<ModelMenuItem> out = new ArrayList<ModelMenuItem>();
        for (ProviderEntry provider : providers) {
            if (!imagejai.ui.installer.ProviderCredentials
                    .isLocalDaemonProvider(provider.providerId())) {
                continue;
            }
            for (final ModelEntry entry : provider.models()) {
                if (entry.tier() != ModelEntry.Tier.FREE
                        && entry.tier() != ModelEntry.Tier.FREE_WITH_LIMITS) {
                    continue;
                }
                ModelMenuItem item = new ModelMenuItem(entry, pinToggleListener());
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

        headerStatusLabel = new JLabel(headerTitle());
        headerStatusLabel.setForeground(new Color(80, 80, 90));
        strip.add(headerStatusLabel, BorderLayout.WEST);

        strip.add(buildSearchField(), BorderLayout.CENTER);

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
        right.add(buildFreeToggle());
        right.add(Box.createHorizontalStrut(6));
        right.add(headerRefreshButton);
        strip.add(right, BorderLayout.EAST);
        return strip;
    }

    /** Persistent type-to-filter field. Reused across rebuilds so its text and
     *  listeners survive; Enter applies the query (robust inside a JPopupMenu). */
    private Component buildSearchField() {
        if (searchField == null) {
            searchField = new javax.swing.JTextField(12);
            searchField.setToolTipText("Type to filter models, then press Enter");
            searchField.putClientProperty("JTextField.placeholderText", "filter…");
            searchField.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    searchText = searchField.getText();
                    applyFilterAndReshow();
                }
            });
            // Keep clicks inside the field from dismissing the popup.
            searchField.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    e.consume();
                }
            });
        }
        searchField.setText(searchText == null ? "" : searchText);
        return searchField;
    }

    /** Toggle between all models and free/keyless-only models. */
    private Component buildFreeToggle() {
        boolean freeOnly = popupFilter == PopupFilter.FREE_ONLY;
        headerFreeToggle = new JButton(freeOnly ? "✓ Free only" : "Free only");
        headerFreeToggle.setFocusable(false);
        headerFreeToggle.setToolTipText(freeOnly
                ? "Showing free models only — click to show all"
                : "Show only free / free-with-limits models");
        headerFreeToggle.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                popupFilter = (popupFilter == PopupFilter.FREE_ONLY)
                        ? PopupFilter.ALL : PopupFilter.FREE_ONLY;
                applyFilterAndReshow();
            }
        });
        headerFreeToggle.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                e.consume();
            }
        });
        return headerFreeToggle;
    }

    private String headerTitle() {
        if (popupFilter == PopupFilter.FREE_ONLY) {
            return "Free models";
        }
        if (popupFilter == PopupFilter.CURATED_ONLY) {
            return "Curated models";
        }
        return "Models";
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
        final long generation = refreshGeneration.next();
        SwingWorker<RefreshOutcome, Void> previous = activeRefreshWorker;
        if (previous != null) previous.cancel(true);
        SwingWorker<RefreshOutcome, Void> worker = new SwingWorker<RefreshOutcome, Void>() {
            @Override
            protected RefreshOutcome doInBackground() throws Exception {
                return task.refresh();
            }

            @Override
            protected void done() {
                if (isCancelled() || !refreshGeneration.isCurrent(generation)) return;
                try {
                    RefreshOutcome outcome = get();
                    if (refreshGeneration.isCurrent(generation)) {
                        handleRefreshSuccess(outcome);
                    }
                } catch (Exception ex) {
                    if (refreshGeneration.isCurrent(generation) && !isCancelled()) {
                        handleRefreshFailure(ex);
                    }
                } finally {
                    if (refreshGeneration.isCurrent(generation)
                            && headerRefreshButton != null) {
                        headerRefreshButton.setEnabled(true);
                    }
                }
            }
        };
        activeRefreshWorker = worker;
        worker.execute();
    }

    /** Start the same generation-gated refresh used by the header button. */
    public void refreshAsync() {
        runRefresh();
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

    void setPopupFilterForTest(String filter) {
        if ("free".equals(filter)) {
            popupFilter = PopupFilter.FREE_ONLY;
        } else if ("curated".equals(filter)) {
            popupFilter = PopupFilter.CURATED_ONLY;
        } else {
            popupFilter = PopupFilter.ALL;
        }
        rebuildPopup();
    }

    List<ProviderEntry> visibleProvidersForTest() {
        return visibleProviders();
    }

    void setSearchTextForTest(String query) {
        searchText = query == null ? "" : query;
        rebuildPopup();
    }

    /** Model keys ("provider modelId") in the top "Free & local" group. */
    List<String> freeLocalKeysForTest() {
        List<String> keys = new ArrayList<String>();
        for (ModelMenuItem item : collectFreeLocal(visibleProviders())) {
            ModelEntry e = item.entry();
            keys.add(e.providerId() + " " + e.modelId());
        }
        return keys;
    }


    private void handleLaunch(ModelEntry entry) {
        if (entry == null) {
            return;
        }
        if (tierGate != null) {
            java.awt.Frame owner = (java.awt.Frame) javax.swing.SwingUtilities
                    .getAncestorOfClass(java.awt.Frame.class, this);
            ProviderTierGate.Decision decision = tierGate.check(owner, entry);
            if (decision == ProviderTierGate.Decision.CANCEL_PICK_FREE) {
                showFilteredPopup(PopupFilter.FREE_ONLY);
                return;
            }
            if (decision == ProviderTierGate.Decision.CANCEL_PICK_CURATED) {
                showFilteredPopup(PopupFilter.CURATED_ONLY);
                return;
            }
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

    private boolean handleStatusIconClick(String providerId,
                                          ModelMenuItem.ProviderStatusIcon status) {
        if (providerId == null || status == null) {
            return false;
        }
        if (status == ModelMenuItem.ProviderStatusIcon.NEEDS_SETUP) {
            if (installerLink != null) {
                installerLink.openInstallerForProvider(providerId);
            }
            return true;
        }
        if (status == ModelMenuItem.ProviderStatusIcon.UNAVAILABLE) {
            showCachedError(providerId);
            return true;
        }
        return false;
    }

    private void showCachedError(final String providerId) {
        final ProviderEntry provider = registry == null ? null : registry.provider(providerId);
        final String display = provider == null ? providerId : provider.displayName();
        String error = settings == null ? null : settings.lastErrorFor(providerId);
        if ((error == null || error.trim().isEmpty()) && provider != null) {
            error = provider.lastError();
        }
        final String cachedError = error;
        CachedErrorDialog dialog = new CachedErrorDialog(display, cachedError, null,
                new CachedErrorDialog.ReconfigureAction() {
                    @Override
                    public void reconfigure() {
                        if (installerLink != null) {
                            installerLink.openInstallerForProvider(providerId);
                        }
                    }
                });
        dialog.show(this);
    }
}
