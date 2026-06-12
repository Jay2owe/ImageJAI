package imagejai.ui.installer.wizard;

import imagejai.ui.installer.ProviderCredentials;

import java.awt.Component;

/**
 * One of the five install-shape modal wizards. Each subclass knows how to
 * collect a particular kind of credential (pure API key, browser auth, local
 * runtime, local model download, paid-with-card) for a single provider and
 * persist it through {@link ProviderCredentials}.
 *
 * <p>Shapes per docs/multi_provider/01_provider_survey.md and the Phase E plan
 * in docs/multi_provider/07_implementation_plan.md §E.
 */
public interface InstallerWizard {

    /** Canonical hyphenated provider key this wizard configures. */
    String providerKey();

    /**
     * Show the modal dialog. Returns true when credentials were saved (or, for
     * the Ollama runtime wizard, when the user confirms a working setup).
     */
    boolean showAndSave(Component parent);
}
