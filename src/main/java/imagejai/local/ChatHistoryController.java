package imagejai.local;

/**
 * Minimal UI-facing hook for commands that affect rendered chat history.
 */
public interface ChatHistoryController {
    boolean canClear();
    void clear();
}
