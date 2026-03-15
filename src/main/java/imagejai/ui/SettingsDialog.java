package imagejai.ui;

import imagejai.config.Constants;
import imagejai.config.Settings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings dialog for configuring LLM provider, API keys, models, and URLs.
 */
public class SettingsDialog extends JDialog {

    private final Settings settings;
    private boolean confirmed = false;

    // Provider selector
    private JComboBox<String> providerCombo;

    // Gemini fields
    private JPasswordField geminiKeyField;
    private JTextField geminiModelField;

    // Ollama fields
    private JTextField ollamaUrlField;
    private JComboBox<String> ollamaModelCombo;

    // OpenAI fields
    private JTextField openaiUrlField;
    private JPasswordField openaiKeyField;
    private JTextField openaiModelField;

    // Custom fields
    private JTextField customUrlField;
    private JPasswordField customKeyField;
    private JTextField customModelField;

    private JPanel cardsPanel;
    private CardLayout cardsLayout;

    public SettingsDialog(Frame parent, Settings settings) {
        super(parent, Constants.PLUGIN_NAME + " Settings", true);
        this.settings = settings;
        buildUI();
        loadFromSettings();
        pack();
        setLocationRelativeTo(parent);
        setResizable(false);
    }

    public boolean wasConfirmed() {
        return confirmed;
    }

    private void buildUI() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Provider selection
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Provider:"));
        providerCombo = new JComboBox<String>(new String[]{
                "Google Gemini (free)", "Ollama (local)", "OpenAI / Compatible", "Custom"
        });
        providerCombo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onProviderChanged();
            }
        });
        topPanel.add(providerCombo);
        content.add(topPanel, BorderLayout.NORTH);

        // Provider-specific cards
        cardsLayout = new CardLayout();
        cardsPanel = new JPanel(cardsLayout);
        cardsPanel.add(buildGeminiPanel(), "gemini");
        cardsPanel.add(buildOllamaPanel(), "ollama");
        cardsPanel.add(buildOpenAIPanel(), "openai");
        cardsPanel.add(buildCustomPanel(), "custom");
        content.add(cardsPanel, BorderLayout.CENTER);

        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton testBtn = new JButton("Test Connection");
        testBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                testConnection();
            }
        });
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                save();
            }
        });
        btnPanel.add(testBtn);
        btnPanel.add(cancelBtn);
        btnPanel.add(saveBtn);
        content.add(btnPanel, BorderLayout.SOUTH);

        setContentPane(content);
    }

    private JPanel buildGeminiPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();

        c.gridx = 0; c.gridy = 0;
        p.add(new JLabel("API Key:"), c);
        c.gridx = 1;
        geminiKeyField = new JPasswordField(30);
        p.add(geminiKeyField, c);

        c.gridx = 0; c.gridy = 1;
        p.add(new JLabel("Model:"), c);
        c.gridx = 1;
        geminiModelField = new JTextField(Constants.DEFAULT_GEMINI_MODEL, 20);
        p.add(geminiModelField, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
        JButton getKeyBtn = new JButton("Get a free API key (ai.google.dev)");
        getKeyBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openUrl("https://aistudio.google.com/apikey");
            }
        });
        p.add(getKeyBtn, c);

        return p;
    }

    private JPanel buildOllamaPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();

        c.gridx = 0; c.gridy = 0;
        p.add(new JLabel("Ollama URL:"), c);
        c.gridx = 1;
        ollamaUrlField = new JTextField(Constants.DEFAULT_OLLAMA_URL, 25);
        p.add(ollamaUrlField, c);

        c.gridx = 0; c.gridy = 1;
        p.add(new JLabel("Model:"), c);
        c.gridx = 1;
        // Model dropdown with refresh
        JPanel modelPanel = new JPanel(new BorderLayout(4, 0));
        ollamaModelCombo = new JComboBox<String>();
        ollamaModelCombo.setEditable(true);
        ollamaModelCombo.addItem(Constants.DEFAULT_OLLAMA_MODEL);
        modelPanel.add(ollamaModelCombo, BorderLayout.CENTER);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setMargin(new Insets(2, 6, 2, 6));
        refreshBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshOllamaModels();
            }
        });
        modelPanel.add(refreshBtn, BorderLayout.EAST);
        p.add(modelPanel, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
        p.add(new JLabel("<html><i>No API key needed. Install Ollama from ollama.ai</i></html>"), c);

        return p;
    }

    private JPanel buildOpenAIPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();

        c.gridx = 0; c.gridy = 0;
        p.add(new JLabel("API URL:"), c);
        c.gridx = 1;
        openaiUrlField = new JTextField(Constants.DEFAULT_OPENAI_URL, 25);
        p.add(openaiUrlField, c);

        c.gridx = 0; c.gridy = 1;
        p.add(new JLabel("API Key:"), c);
        c.gridx = 1;
        openaiKeyField = new JPasswordField(30);
        p.add(openaiKeyField, c);

        c.gridx = 0; c.gridy = 2;
        p.add(new JLabel("Model:"), c);
        c.gridx = 1;
        openaiModelField = new JTextField(Constants.DEFAULT_OPENAI_MODEL, 20);
        p.add(openaiModelField, c);

        return p;
    }

    private JPanel buildCustomPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();

        c.gridx = 0; c.gridy = 0;
        p.add(new JLabel("Endpoint URL:"), c);
        c.gridx = 1;
        customUrlField = new JTextField(25);
        p.add(customUrlField, c);

        c.gridx = 0; c.gridy = 1;
        p.add(new JLabel("API Key:"), c);
        c.gridx = 1;
        customKeyField = new JPasswordField(30);
        p.add(customKeyField, c);

        c.gridx = 0; c.gridy = 2;
        p.add(new JLabel("Model:"), c);
        c.gridx = 1;
        customModelField = new JTextField(20);
        p.add(customModelField, c);

        c.gridx = 0; c.gridy = 3; c.gridwidth = 2;
        p.add(new JLabel("<html><i>Any OpenAI-compatible endpoint</i></html>"), c);

        return p;
    }

    private GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private void onProviderChanged() {
        int idx = providerCombo.getSelectedIndex();
        String[] cards = {"gemini", "ollama", "openai", "custom"};
        if (idx >= 0 && idx < cards.length) {
            cardsLayout.show(cardsPanel, cards[idx]);
        }
    }

    private void loadFromSettings() {
        String provider = settings.provider;
        if ("gemini".equals(provider)) {
            providerCombo.setSelectedIndex(0);
        } else if ("ollama".equals(provider)) {
            providerCombo.setSelectedIndex(1);
        } else if ("openai".equals(provider)) {
            providerCombo.setSelectedIndex(2);
        } else {
            providerCombo.setSelectedIndex(3);
        }

        // Load all fields regardless of current provider
        geminiKeyField.setText(settings.apiKey);
        geminiModelField.setText(settings.model);

        ollamaUrlField.setText(settings.ollamaUrl);
        // Set the ollama model in the combo
        String ollamaModel = settings.model;
        if ("ollama".equals(provider) && ollamaModel != null && !ollamaModel.isEmpty()) {
            ollamaModelCombo.setSelectedItem(ollamaModel);
        }

        openaiUrlField.setText(settings.openaiUrl);
        openaiKeyField.setText(settings.apiKey);
        openaiModelField.setText("openai".equals(provider) ? settings.model : Constants.DEFAULT_OPENAI_MODEL);

        customUrlField.setText(settings.customUrl);
        customKeyField.setText(settings.customApiKey);
        customModelField.setText(settings.customModel);

        onProviderChanged();
    }

    private boolean validateInputs() {
        int idx = providerCombo.getSelectedIndex();

        if (idx == 0) { // Gemini
            String key = new String(geminiKeyField.getPassword()).trim();
            if (key.isEmpty()) {
                showError("Please enter a Gemini API key.\n"
                        + "Get a free key at https://aistudio.google.com/apikey");
                return false;
            }
        } else if (idx == 1) { // Ollama
            String url = ollamaUrlField.getText().trim();
            if (url.isEmpty()) {
                showError("Please enter the Ollama URL.");
                return false;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                showError("Ollama URL must start with http:// or https://");
                return false;
            }
            Object selectedModel = ollamaModelCombo.getSelectedItem();
            if (selectedModel == null || selectedModel.toString().trim().isEmpty()) {
                showError("Please select or enter an Ollama model name.");
                return false;
            }
        } else if (idx == 2) { // OpenAI
            String key = new String(openaiKeyField.getPassword()).trim();
            if (key.isEmpty()) {
                showError("Please enter an OpenAI API key.");
                return false;
            }
            String url = openaiUrlField.getText().trim();
            if (url.isEmpty()) {
                showError("Please enter the OpenAI API URL.");
                return false;
            }
        } else if (idx == 3) { // Custom
            String url = customUrlField.getText().trim();
            if (url.isEmpty()) {
                showError("Please enter the custom endpoint URL.");
                return false;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                showError("Endpoint URL must start with http:// or https://");
                return false;
            }
        }

        return true;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Validation Error", JOptionPane.WARNING_MESSAGE);
    }

    private void save() {
        if (!validateInputs()) {
            return;
        }

        int idx = providerCombo.getSelectedIndex();
        String[] providers = {"gemini", "ollama", "openai", "custom"};
        settings.provider = providers[idx];

        if (idx == 0) { // Gemini
            settings.apiKey = new String(geminiKeyField.getPassword()).trim();
            String model = geminiModelField.getText().trim();
            settings.model = model.isEmpty() ? Constants.DEFAULT_GEMINI_MODEL : model;
        } else if (idx == 1) { // Ollama
            settings.ollamaUrl = ollamaUrlField.getText().trim();
            if (settings.ollamaUrl.isEmpty()) {
                settings.ollamaUrl = Constants.DEFAULT_OLLAMA_URL;
            }
            Object selectedModel = ollamaModelCombo.getSelectedItem();
            settings.model = (selectedModel != null) ? selectedModel.toString().trim() : Constants.DEFAULT_OLLAMA_MODEL;
            if (settings.model.isEmpty()) {
                settings.model = Constants.DEFAULT_OLLAMA_MODEL;
            }
        } else if (idx == 2) { // OpenAI
            settings.openaiUrl = openaiUrlField.getText().trim();
            if (settings.openaiUrl.isEmpty()) {
                settings.openaiUrl = Constants.DEFAULT_OPENAI_URL;
            }
            settings.apiKey = new String(openaiKeyField.getPassword()).trim();
            String model = openaiModelField.getText().trim();
            settings.model = model.isEmpty() ? Constants.DEFAULT_OPENAI_MODEL : model;
        } else if (idx == 3) { // Custom
            settings.customUrl = customUrlField.getText().trim();
            settings.customApiKey = new String(customKeyField.getPassword()).trim();
            String model = customModelField.getText().trim();
            settings.customModel = model;
            settings.model = model;
        }

        confirmed = true;
        dispose();
    }

    /**
     * Fetch available models from Ollama's /api/tags endpoint and populate the dropdown.
     */
    private void refreshOllamaModels() {
        final String baseUrl = ollamaUrlField.getText().trim();
        if (baseUrl.isEmpty()) {
            showError("Please enter the Ollama URL first.");
            return;
        }

        // Run the HTTP request on a background thread to avoid blocking the EDT
        final JButton source = findRefreshButton();
        if (source != null) {
            source.setEnabled(false);
            source.setText("...");
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String urlStr = baseUrl.endsWith("/") ? baseUrl + "api/tags" : baseUrl + "/api/tags";
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new RuntimeException("HTTP " + code);
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    conn.disconnect();

                    // Parse model names from JSON response
                    // Response format: {"models":[{"name":"llama3",...},{"name":"mistral",...}]}
                    final List<String> models = parseModelNames(sb.toString());

                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            Object current = ollamaModelCombo.getSelectedItem();
                            ollamaModelCombo.removeAllItems();
                            for (String m : models) {
                                ollamaModelCombo.addItem(m);
                            }
                            if (current != null && !current.toString().isEmpty()) {
                                ollamaModelCombo.setSelectedItem(current);
                            }
                            if (source != null) {
                                source.setEnabled(true);
                                source.setText("Refresh");
                            }
                        }
                    });
                } catch (final Exception ex) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            if (source != null) {
                                source.setEnabled(true);
                                source.setText("Refresh");
                            }
                            showError("Could not connect to Ollama at:\n" + baseUrl
                                    + "\n\nError: " + ex.getMessage()
                                    + "\n\nMake sure Ollama is running.");
                        }
                    });
                }
            }
        }, "ollama-model-refresh").start();
    }

    /**
     * Simple JSON parser for Ollama /api/tags response.
     * Extracts "name" values from the "models" array without requiring Gson
     * (keeping it self-contained, though Gson is available).
     */
    private List<String> parseModelNames(String json) {
        List<String> names = new ArrayList<String>();
        // Find "models" array, then extract each "name" value
        int modelsIdx = json.indexOf("\"models\"");
        if (modelsIdx < 0) return names;

        int arrayStart = json.indexOf('[', modelsIdx);
        if (arrayStart < 0) return names;

        int arrayEnd = json.indexOf(']', arrayStart);
        if (arrayEnd < 0) arrayEnd = json.length();

        String arrayContent = json.substring(arrayStart, arrayEnd);

        // Find each "name":"value" pair
        int pos = 0;
        while (pos < arrayContent.length()) {
            int nameKeyIdx = arrayContent.indexOf("\"name\"", pos);
            if (nameKeyIdx < 0) break;

            int colonIdx = arrayContent.indexOf(':', nameKeyIdx + 6);
            if (colonIdx < 0) break;

            int quoteStart = arrayContent.indexOf('"', colonIdx + 1);
            if (quoteStart < 0) break;

            int quoteEnd = arrayContent.indexOf('"', quoteStart + 1);
            if (quoteEnd < 0) break;

            String name = arrayContent.substring(quoteStart + 1, quoteEnd);
            if (!name.isEmpty()) {
                names.add(name);
            }
            pos = quoteEnd + 1;
        }

        return names;
    }

    private JButton findRefreshButton() {
        // Walk the Ollama panel to find the Refresh button
        Component[] cards = cardsPanel.getComponents();
        // Ollama is the second card (index 1)
        if (cards.length > 1 && cards[1] instanceof JPanel) {
            return findButtonInPanel((JPanel) cards[1], "Refresh");
        }
        return null;
    }

    private JButton findButtonInPanel(Container container, String text) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton && text.equals(((JButton) comp).getText())) {
                return (JButton) comp;
            }
            if (comp instanceof Container) {
                JButton found = findButtonInPanel((Container) comp, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void testConnection() {
        // TODO: Wire to LLMBackend.testConnection() in Phase 1B
        JOptionPane.showMessageDialog(this,
                "Connection test will be available after LLM backends are implemented.",
                "Test Connection", JOptionPane.INFORMATION_MESSAGE);
    }

    private void openUrl(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Open this URL in your browser:\n" + url,
                    "Get API Key", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
