package imagejai.ui;

import imagejai.config.Settings;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Compatibility wrapper around the extracted chat body.
 *
 * <p>New plugin-frame code uses {@link AiRootPanel}. This class remains so
 * older call sites that refer to {@code ChatPanel.ChatListener} or the old
 * panel type keep compiling while the UI is decomposed.
 */
public class ChatPanel extends JPanel implements ChatPanelController, ChatSurface {

    /** Callback interface for user-entered chat messages. */
    public interface ChatListener {
        void onUserMessage(String text);
    }

    private final ChatView chatView;

    public ChatPanel(Settings settings) {
        this(new ChatView(settings));
    }

    public ChatPanel(ChatView chatView) {
        super(new BorderLayout());
        this.chatView = chatView;
        if (chatView.getParent() == null) {
            add(chatView, BorderLayout.CENTER);
        }
    }

    public ChatView chatView() {
        return chatView;
    }

    public void addChatListener(ChatListener listener) {
        chatView.addChatListener(listener);
    }

    public void removeChatListener(ChatListener listener) {
        chatView.removeChatListener(listener);
    }

    public void clearConversation() {
        chatView.clearConversation();
    }

    public Settings getSettings() {
        return chatView.getSettings();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        chatView.setEnabled(enabled);
    }

    @Override
    public void setThinking(boolean thinking) {
        chatView.setThinking(thinking);
    }

    @Override
    public void appendMessage(String role, String text) {
        chatView.appendMessage(role, text);
    }

    @Override
    public void appendHtml(String html) {
        chatView.appendHtml(html);
    }

    @Override
    public void setStatus(String status) {
        chatView.setStatus(status);
    }

    @Override
    public void inlineImage(Path path) {
        chatView.inlineImage(path);
    }

    @Override
    public void toast(String message, String level) {
        chatView.toast(message, level);
    }

    @Override
    public void showMarkdown(String content) {
        chatView.showMarkdown(content);
    }

    @Override
    public void highlightRoi(String imageTitle, int[] roiBounds) {
        chatView.highlightRoi(imageTitle, roiBounds);
    }

    @Override
    public void focusImage(String imageTitle) {
        chatView.focusImage(imageTitle);
    }

    @Override
    public void confirm(String prompt, List<String> options, Consumer<String> onChoice) {
        chatView.confirm(prompt, options, onChoice);
    }
}
