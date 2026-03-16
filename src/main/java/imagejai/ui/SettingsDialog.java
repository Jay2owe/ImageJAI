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
 * Settings dialog for configuring multiple LLM profiles.
 */
public class SettingsDialog extends JDialog {

    private final Settings settings;
    private boolean confirmed = false;
    private Settings.ModelConfig editingConfig;

    // Profile selector
    private JComboBox<Settings.ModelConfig> profileCombo;
    private JTextField profileNameField;

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

    // TCP server fields
    private JCheckBox tcpEnabledCheckbox;
    private JTextField tcpPortField;
    private JLabel tcpPortLabel;
    private JLabel tcpHelpLabel;

    private JPanel cardsPanel;
    private CardLayout cardsLayout;

    public SettingsDialog(Frame parent, Settings settings) {
        super(parent, Constants.PLUGIN_NAME + " Settings", true);
        this.settings = settings;
        this.editingConfig = settings.getActiveConfig();
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

        // Profile Selection Area
        JPanel profilePanel = new JPanel(new GridBagLayout());
        GridBagConstraints pc = new GridBagConstraints();
        pc.insets = new Insets(2, 4, 6, 4);
        pc.anchor = GridBagConstraints.WEST;

        pc.gridx = 0; pc.gridy = 0;
        profilePanel.add(new JLabel("Profile:"), pc);

        pc.gridx = 1; pc.fill = GridBagConstraints.HORIZONTAL; pc.weightx = 1.0;
        profileCombo = new JComboBox<Settings.ModelConfig>();
        for (Settings.ModelConfig c : settings.configs) {
            profileCombo.addItem(c);
        }
        profileCombo.setSelectedItem(editingConfig);
        profileCombo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onProfileChanged();
            }
        });
        profilePanel.add(profileCombo, pc);

        pc.gridx = 2; pc.fill = GridBagConstraints.NONE; pc.weightx = 0;
        JButton newProfileBtn = new JButton("New");
        newProfileBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                createNewProfile();
            }
        });
        profilePanel.add(newProfileBtn, pc);

        pc.gridx = 3;
        JButton deleteProfileBtn = new JButton("Delete");
        deleteProfileBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteProfile();
            }
        });
        profilePanel.add(deleteProfileBtn, pc);

        pc.gridx = 0; pc.gridy = 1;
        profilePanel.add(new JLabel("Name:"), pc);
        pc.gridx = 1; pc.gridwidth = 3; pc.fill = GridBagConstraints.HORIZONTAL;
        profileNameField = new JTextField(20);
        profilePanel.add(profileNameField, pc);

        pc.gridx = 0; pc.gridy = 2; pc.gridwidth = 1; pc.fill = GridBagConstraints.NONE;
        profilePanel.add(new JLabel("Provider:"), pc);
        pc.gridx = 1; pc.gridwidth = 3; pc.fill = GridBagConstraints.HORIZONTAL;
        providerCombo = new JComboBox<String>(new String[]{
                "Google Gemini", "Ollama (local)", "OpenAI / Compatible", "Custom"
        });
        providerCombo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onProviderChanged();
            }
        });
        profilePanel.add(providerCombo, pc);

        content.add(profilePanel, BorderLayout.NORTH);

        // Provider-specific cards
        cardsLayout = new CardLayout();
        cardsPanel = new JPanel(cardsLayout);
        cardsPanel.add(buildGeminiPanel(), "gemini");
        cardsPanel.add(buildOllamaPanel(), "ollama");
        cardsPanel.add(buildOpenAIPanel(), "openai");
        cardsPanel.add(buildCustomPanel(), "custom");
        
        // Wrap cards and advanced panel in a vertical box
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(cardsPanel);
        centerPanel.add(buildAdvancedPanel());
        content.add(centerPanel, BorderLayout.CENTER);

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
        JButton saveBtn = new JButton("Save All");
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

        return p;
    }

    private JPanel buildAdvancedPanel() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Advanced (Global)"),
                new EmptyBorder(4, 8, 4, 8)));

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        tcpEnabledCheckbox = new JCheckBox("Enable TCP command server");
        tcpEnabledCheckbox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                boolean enabled = tcpEnabledCheckbox.isSelected();
                tcpPortField.setEnabled(enabled);
                tcpPortLabel.setEnabled(enabled);
                tcpHelpLabel.setEnabled(enabled);
            }
        });
        p.add(tcpEnabledCheckbox, c);

        c.gridx = 0; c.gridy = 1; c.gridwidth = 1;
        tcpPortLabel = new JLabel("  Port:");
        p.add(tcpPortLabel, c);

        c.gridx = 1;
        tcpPortField = new JTextField(String.valueOf(Constants.DEFAULT_TCP_PORT), 6);
        p.add(tcpPortField, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
        tcpHelpLabel = new JLabel("<html><i>For Claude CLI / external tools</i></html>");
        tcpHelpLabel.setFont(tcpHelpLabel.getFont().deriveFont(Font.ITALIC, 11f));
        p.add(tcpHelpLabel, c);

        wrapper.add(p, BorderLayout.WEST);
        return wrapper;
    }

    private GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private void createNewProfile() {
        saveToActiveConfig();
        Settings.ModelConfig nc = new Settings.ModelConfig("New Profile", "gemini", Constants.DEFAULT_GEMINI_MODEL);
        settings.configs.add(nc);
        profileCombo.addItem(nc);
        profileCombo.setSelectedItem(nc);
    }

    private void deleteProfile() {
        if (settings.configs.size() <= 1) {
            showError("Cannot delete the last profile.");
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete profile \"" + editingConfig.name + "\"?\nThis cannot be undone.",
                "Delete Profile", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            settings.configs.remove(editingConfig);
            profileCombo.removeItem(editingConfig);
            profileCombo.setSelectedIndex(0);
        }
    }

    private void onProfileChanged() {
        saveToActiveConfig();
        editingConfig = (Settings.ModelConfig) profileCombo.getSelectedItem();
        if (editingConfig != null) {
            loadFromActiveConfig();
        }
    }

    private void onProviderChanged() {
        int idx = providerCombo.getSelectedIndex();
        String[] cards = {"gemini", "ollama", "openai", "custom"};
        if (idx >= 0 && idx < cards.length) {
            cardsLayout.show(cardsPanel, cards[idx]);
        }
    }

    private void loadFromActiveConfig() {
        profileNameField.setText(editingConfig.name);
        String p = editingConfig.provider;
        if ("gemini".equals(p)) providerCombo.setSelectedIndex(0);
        else if ("ollama".equals(p)) providerCombo.setSelectedIndex(1);
        else if ("openai".equals(p)) providerCombo.setSelectedIndex(2);
        else providerCombo.setSelectedIndex(3);

        geminiKeyField.setText("gemini".equals(p) ? editingConfig.apiKey : "");
        geminiModelField.setText("gemini".equals(p) ? editingConfig.model : Constants.DEFAULT_GEMINI_MODEL);
        ollamaUrlField.setText("ollama".equals(p) ? editingConfig.url : Constants.DEFAULT_OLLAMA_URL);
        if ("ollama".equals(p)) ollamaModelCombo.setSelectedItem(editingConfig.model);
        openaiUrlField.setText("openai".equals(p) ? editingConfig.url : Constants.DEFAULT_OPENAI_URL);
        openaiKeyField.setText("openai".equals(p) ? editingConfig.apiKey : "");
        openaiModelField.setText("openai".equals(p) ? editingConfig.model : Constants.DEFAULT_OPENAI_MODEL);
        customUrlField.setText("custom".equals(p) ? editingConfig.url : "");
        customKeyField.setText("custom".equals(p) ? editingConfig.apiKey : "");
        customModelField.setText("custom".equals(p) ? editingConfig.model : "");

        onProviderChanged();
    }

    private void saveToActiveConfig() {
        if (editingConfig == null) return;
        editingConfig.name = profileNameField.getText().trim();
        int idx = providerCombo.getSelectedIndex();
        String[] providers = {"gemini", "ollama", "openai", "custom"};
        editingConfig.provider = providers[idx];

        if (idx == 0) { // Gemini
            editingConfig.apiKey = new String(geminiKeyField.getPassword()).trim();
            editingConfig.model = geminiModelField.getText().trim();
        } else if (idx == 1) { // Ollama
            editingConfig.url = ollamaUrlField.getText().trim();
            Object m = ollamaModelCombo.getSelectedItem();
            editingConfig.model = m != null ? m.toString() : Constants.DEFAULT_OLLAMA_MODEL;
        } else if (idx == 2) { // OpenAI
            editingConfig.url = openaiUrlField.getText().trim();
            editingConfig.apiKey = new String(openaiKeyField.getPassword()).trim();
            editingConfig.model = openaiModelField.getText().trim();
        } else if (idx == 3) { // Custom
            editingConfig.url = customUrlField.getText().trim();
            editingConfig.apiKey = new String(customKeyField.getPassword()).trim();
            editingConfig.model = customModelField.getText().trim();
        }
        profileCombo.repaint();
    }

    private void loadFromSettings() {
        tcpEnabledCheckbox.setSelected(settings.tcpServerEnabled);
        tcpPortField.setText(String.valueOf(settings.tcpPort));
        boolean tcpOn = settings.tcpServerEnabled;
        tcpPortField.setEnabled(tcpOn);
        tcpPortLabel.setEnabled(tcpOn);
        tcpHelpLabel.setEnabled(tcpOn);
        loadFromActiveConfig();
    }

    private boolean validateInputs() {
        // Simple check for active profile
        if (editingConfig.name.isEmpty()) {
            showError("Profile name cannot be empty.");
            return false;
        }
        
        // We allow empty keys now if TCP is enabled
        boolean tcp = tcpEnabledCheckbox.isSelected();
        if (!tcp && !"ollama".equals(editingConfig.provider) && editingConfig.apiKey.isEmpty()) {
            showError("API key is required for " + editingConfig.provider + " chat features.");
            return false;
        }
        
        return true;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Validation Error", JOptionPane.WARNING_MESSAGE);
    }

    private void save() {
        saveToActiveConfig();
        if (!validateInputs()) return;

        settings.tcpServerEnabled = tcpEnabledCheckbox.isSelected();
        try {
            settings.tcpPort = Integer.parseInt(tcpPortField.getText().trim());
        } catch (Exception e) {
            settings.tcpPort = Constants.DEFAULT_TCP_PORT;
        }

        if (editingConfig != null) {
            settings.activeConfigId = editingConfig.id;
        }

        confirmed = true;
        dispose();
    }

    private void refreshOllamaModels() {
        final String baseUrl = ollamaUrlField.getText().trim();
        if (baseUrl.isEmpty()) {
            showError("Please enter the Ollama URL first.");
            return;
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
                    int code = conn.getResponseCode();
                    if (code != 200) throw new RuntimeException("HTTP " + code);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    
                    final List<String> models = parseModelNames(sb.toString());
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            ollamaModelCombo.removeAllItems();
                            for (String m : models) ollamaModelCombo.addItem(m);
                        }
                    });
                } catch (final Exception ex) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            showError("Could not connect to Ollama: " + ex.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private List<String> parseModelNames(String json) {
        List<String> names = new ArrayList<String>();
        int pos = 0;
        while ((pos = json.indexOf("\"name\"", pos)) != -1) {
            int start = json.indexOf('\"', pos + 6);
            int end = json.indexOf('\"', start + 1);
            if (start != -1 && end != -1) {
                names.add(json.substring(start + 1, end));
                pos = end;
            } else pos += 6;
        }
        return names;
    }

    private void testConnection() {
        JOptionPane.showMessageDialog(this, "Connection test available in Phase 1B.", "Test Connection", JOptionPane.INFORMATION_MESSAGE);
    }

    private void openUrl(String url) {
        try { Desktop.getDesktop().browse(new URI(url)); }
        catch (Exception e) { JOptionPane.showMessageDialog(this, "URL: " + url); }
    }
}
