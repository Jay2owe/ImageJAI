package imagejai.local;

import java.util.Map;

/**
 * A deterministic Local Assistant action.
 */
public interface Intent {

    String id();

    String description();

    AssistantReply execute(Map<String, String> slots, FijiBridge fiji);
}
