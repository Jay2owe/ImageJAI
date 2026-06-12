package imagejai.ui;

/**
 * The minimum surface {@link imagejai.ConversationLoop} and
 * the TCP-server wiring need from the chat pane. Introduced so those
 * consumers do not depend on the concrete {@code ChatPanel} class —
 * later stages extract {@code ChatView} from {@code ChatPanel} and
 * place the whole thing under a new {@code AiRootPanel} with a
 * terminal card.
 */
public interface ChatSurface {
    void setEnabled(boolean enabled);
    void setThinking(boolean thinking);
    void appendMessage(String role, String text);
    void appendHtml(String html);
    void setStatus(String status);
}
