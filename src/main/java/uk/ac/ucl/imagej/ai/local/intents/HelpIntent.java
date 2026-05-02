package uk.ac.ucl.imagej.ai.local.intents;

import uk.ac.ucl.imagej.ai.local.AssistantReply;
import uk.ac.ucl.imagej.ai.local.FijiBridge;
import uk.ac.ucl.imagej.ai.local.Intent;

import java.util.Map;

/**
 * Stage-02 proof that chat input reaches the Local Assistant.
 */
public class HelpIntent implements Intent {

    public static final String ID = "builtin.help";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Show Local Assistant help";
    }

    @Override
    public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
        return AssistantReply.text("I am the Local Assistant. I match plain English "
                + "to ImageJ actions. Try: 'pixel size', 'close all', 'list ROIs'.");
    }
}
