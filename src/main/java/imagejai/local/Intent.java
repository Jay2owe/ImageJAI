package imagejai.local;

import java.util.List;
import java.util.Map;

/**
 * A deterministic Local Assistant action.
 */
public interface Intent {

    String id();

    String description();

    AssistantReply execute(Map<String, String> slots, FijiBridge fiji);

    default List<SlotSpec> requiredSlots() {
        return List.of();
    }

    default List<SlotSpec> suggestedSlots() {
        return List.of();
    }
}
