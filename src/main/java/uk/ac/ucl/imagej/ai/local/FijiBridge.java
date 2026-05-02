package uk.ac.ucl.imagej.ai.local;

import uk.ac.ucl.imagej.ai.engine.CommandEngine;

/**
 * Placeholder facade for direct Fiji/ImageJ access.
 */
public class FijiBridge {

    private final CommandEngine commandEngine;

    public FijiBridge(CommandEngine commandEngine) {
        this.commandEngine = commandEngine;
    }
}
