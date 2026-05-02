package imagejai.local;

import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * A deterministic Local Assistant action.
 */
public interface Intent {

    String id();

    String description();

    AssistantReply execute(Map<String, String> slots, FijiBridge fiji);

    default List<SlotSpec> requiredSlots() {
        return Collections.emptyList();
    }

    default List<SlotSpec> suggestedSlots() {
        return Collections.emptyList();
    }
}
