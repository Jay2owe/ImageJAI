package imagejai.local;

import imagejai.engine.CommandEngine;

/**
 * Placeholder facade for direct Fiji/ImageJ access.
 */
public class FijiBridge {

    private final CommandEngine commandEngine;

    public FijiBridge(CommandEngine commandEngine) {
        this.commandEngine = commandEngine;
    }
}
