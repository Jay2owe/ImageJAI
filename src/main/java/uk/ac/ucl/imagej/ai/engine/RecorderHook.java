package uk.ac.ucl.imagej.ai.engine;

import ij.CommandListener;
import ij.Executer;
import ij.Macro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Hooks into ImageJ's command execution to track user actions.
 * Enables "teaching mode" where the AI learns from what the user does manually.
 * <p>
 * Implements {@link CommandListener} to intercept every command before it runs,
 * recording it with timestamp and options. Provides pattern detection to identify
 * repetitive workflows that could be automated.
 */
public class RecorderHook implements CommandListener {

    /** Maximum number of recorded actions to retain. */
    private static final int MAX_HISTORY = 100;

    private final LinkedList<RecordedAction> actions = new LinkedList<RecordedAction>();
    private volatile boolean recording = false;
    private PatternListener patternListener;

    // ---- Inner classes ----

    /**
     * A single recorded user action with timestamp, command name, and options.
     */
    public static class RecordedAction {
        public final long timestamp;
        public final String command;
        public final String options;

        public RecordedAction(String command, String options) {
            this.timestamp = System.currentTimeMillis();
            this.command = command != null ? command : "";
            this.options = options != null ? options : "";
        }

        /**
         * Convert this action to an ImageJ macro statement.
         * E.g. {@code run("Gaussian Blur...", "sigma=2");}
         */
        public String toMacroString() {
            if (options.isEmpty()) {
                return "run(\"" + escapeMacroString(command) + "\");";
            }
            return "run(\"" + escapeMacroString(command) + "\", \"" + escapeMacroString(options) + "\");";
        }

        @Override
        public String toString() {
            if (options.isEmpty()) {
                return command;
            }
            return command + " (" + options + ")";
        }

        private static String escapeMacroString(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    /**
     * Listener interface for repetitive pattern detection notifications.
     */
    public interface PatternListener {
        void onPatternDetected(List<RecordedAction> pattern, int repetitions);
    }

    // ---- CommandListener implementation ----

    /**
     * Called by ImageJ before each command executes.
     * Records the command and its options, then returns the command unchanged
     * to allow normal execution.
     *
     * @param command the ImageJ command about to execute
     * @return the command string (unchanged) to let it proceed
     */
    @Override
    public String commandExecuting(String command) {
        if (!recording || command == null) {
            return command;
        }

        // Capture options from Macro.getOptions() — this is set when commands
        // are invoked with parameters (e.g., from dialogs)
        String options = Macro.getOptions();
        if (options != null) {
            options = options.trim();
        }

        RecordedAction action = new RecordedAction(command, options);

        synchronized (actions) {
            actions.addLast(action);
            while (actions.size() > MAX_HISTORY) {
                actions.removeFirst();
            }
        }

        // Check for repetitive patterns after recording
        checkForPatterns();

        return command;
    }

    // ---- Public API ----

    /**
     * Start listening for user commands. Registers this hook with ImageJ's
     * {@link Executer} system.
     */
    public void start() {
        if (!recording) {
            recording = true;
            Executer.addCommandListener(this);
        }
    }

    /**
     * Stop listening for user commands. Unregisters from ImageJ's
     * {@link Executer} system.
     */
    public void stop() {
        if (recording) {
            recording = false;
            Executer.removeCommandListener(this);
        }
    }

    /**
     * Get the most recent N recorded actions.
     *
     * @param count maximum number of actions to return
     * @return list of recent actions, most recent last
     */
    public List<RecordedAction> getRecentActions(int count) {
        synchronized (actions) {
            int size = actions.size();
            if (count >= size) {
                return new ArrayList<RecordedAction>(actions);
            }
            return new ArrayList<RecordedAction>(actions.subList(size - count, size));
        }
    }

    /**
     * Get all recorded actions since the last clear.
     *
     * @return unmodifiable list of all recorded actions
     */
    public List<RecordedAction> getAllActions() {
        synchronized (actions) {
            return Collections.unmodifiableList(new ArrayList<RecordedAction>(actions));
        }
    }

    /**
     * Clear all recorded history.
     */
    public void clear() {
        synchronized (actions) {
            actions.clear();
        }
    }

    /**
     * Check if a repetitive pattern is detected. A pattern is a sequence of
     * 2 or more commands that repeats at least {@code minRepetitions} times
     * consecutively at the end of the action history.
     *
     * @param minRepetitions minimum number of times the pattern must repeat
     * @return true if a repetitive pattern is found
     */
    public boolean hasRepetitivePattern(int minRepetitions) {
        return findPattern(minRepetitions) != null;
    }

    /**
     * Get the detected repetitive pattern (the sequence of commands that repeats).
     * Uses a minimum of 3 repetitions.
     *
     * @return the repeating pattern, or an empty list if none found
     */
    public List<RecordedAction> getRepetitivePattern() {
        List<RecordedAction> pattern = findPattern(3);
        if (pattern == null) {
            return Collections.emptyList();
        }
        return pattern;
    }

    /**
     * Export all recorded actions as an ImageJ macro string.
     *
     * @return macro code representing the recorded actions
     */
    public String exportAsMacro() {
        List<RecordedAction> snapshot;
        synchronized (actions) {
            snapshot = new ArrayList<RecordedAction>(actions);
        }

        if (snapshot.isEmpty()) {
            return "// No recorded actions";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// Recorded actions from user session\n");
        for (int i = 0; i < snapshot.size(); i++) {
            sb.append(snapshot.get(i).toMacroString());
            if (i < snapshot.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Get a human-readable summary of the most recent actions.
     *
     * @param count maximum number of actions to include
     * @return formatted summary string
     */
    public String getActionSummary(int count) {
        List<RecordedAction> recent = getRecentActions(count);
        if (recent.isEmpty()) {
            return "No recent actions recorded.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Recent actions (last ").append(recent.size()).append("):\n");
        for (int i = 0; i < recent.size(); i++) {
            sb.append(i + 1).append(". ").append(recent.get(i).toString()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Set a listener to be notified when a repetitive pattern is detected.
     *
     * @param listener the pattern listener, or null to remove
     */
    public void setPatternListener(PatternListener listener) {
        this.patternListener = listener;
    }

    /**
     * Check whether this hook is currently recording.
     *
     * @return true if recording is active
     */
    public boolean isRecording() {
        return recording;
    }

    // ---- Pattern detection ----

    /**
     * Look for a repetitive pattern at the tail of the action list.
     * Tries pattern lengths from 2 up to half the history size.
     * A pattern must repeat at least {@code minReps} times consecutively.
     *
     * @param minReps minimum repetitions required
     * @return the repeating pattern actions, or null if none found
     */
    private List<RecordedAction> findPattern(int minReps) {
        List<RecordedAction> snapshot;
        synchronized (actions) {
            snapshot = new ArrayList<RecordedAction>(actions);
        }

        int size = snapshot.size();
        if (size < 4) {
            return null;
        }

        // Try pattern lengths from 2 up to size/minReps
        int maxPatternLen = size / minReps;
        for (int patternLen = 2; patternLen <= maxPatternLen; patternLen++) {
            int reps = countTrailingRepetitions(snapshot, patternLen);
            if (reps >= minReps) {
                // Return the pattern (the last patternLen actions from the first occurrence)
                int patternStart = size - (reps * patternLen);
                return new ArrayList<RecordedAction>(
                        snapshot.subList(patternStart, patternStart + patternLen));
            }
        }

        return null;
    }

    /**
     * Count how many times a pattern of the given length repeats at the tail
     * of the action list. Comparison is by command name only (options may vary
     * slightly between repetitions).
     */
    private int countTrailingRepetitions(List<RecordedAction> list, int patternLen) {
        int size = list.size();
        if (patternLen <= 0 || size < patternLen) {
            return 0;
        }

        // Extract the candidate pattern from the tail
        String[] pattern = new String[patternLen];
        for (int i = 0; i < patternLen; i++) {
            pattern[i] = list.get(size - patternLen + i).command;
        }

        // Walk backwards counting matches
        int reps = 1;
        int pos = size - (2 * patternLen);
        while (pos >= 0) {
            boolean matches = true;
            for (int i = 0; i < patternLen; i++) {
                if (!pattern[i].equals(list.get(pos + i).command)) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                break;
            }
            reps++;
            pos -= patternLen;
        }

        return reps;
    }

    /**
     * Check for patterns and notify the listener if one is found.
     * Called after each new action is recorded.
     */
    private void checkForPatterns() {
        if (patternListener == null) {
            return;
        }

        List<RecordedAction> pattern = findPattern(3);
        if (pattern != null) {
            List<RecordedAction> snapshot;
            synchronized (actions) {
                snapshot = new ArrayList<RecordedAction>(actions);
            }
            int reps = countTrailingRepetitions(snapshot, pattern.size());
            patternListener.onPatternDetected(pattern, reps);
        }
    }
}
