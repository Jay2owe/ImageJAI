package imagejai.local.intents.analysis;

import ij.IJ;
import ij.ImagePlus;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;

import java.util.Map;

abstract class OpenDialogIntent extends AbstractAnalysisIntent {
    protected abstract String command();
    protected abstract String label();
    public String description() { return "Open a Fiji dialog and let the user set parameters"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        IJ.run(command());
        return AssistantReply.withMacro("Opened " + label() + ".",
                "run(\"" + command() + "\");");
    }
}

class OpenThresholdDialogIntent extends OpenDialogIntent {
    public String id() { return "dialog.threshold"; }
    protected String command() { return "Threshold..."; }
    protected String label() { return "Threshold"; }
}

class OpenAnalyzeParticlesDialogIntent extends OpenDialogIntent {
    public String id() { return "dialog.analyze_particles"; }
    protected String command() { return "Analyze Particles..."; }
    protected String label() { return "Analyze Particles"; }
}

class OpenFindMaximaDialogIntent extends OpenDialogIntent {
    public String id() { return "dialog.find_maxima"; }
    protected String command() { return "Find Maxima..."; }
    protected String label() { return "Find Maxima"; }
}

class OpenGaussianBlurDialogIntent extends OpenDialogIntent {
    public String id() { return "dialog.gaussian_blur"; }
    protected String command() { return "Gaussian Blur..."; }
    protected String label() { return "Gaussian Blur"; }
}

class OpenSubtractBackgroundDialogIntent extends OpenDialogIntent {
    public String id() { return "dialog.subtract_background"; }
    protected String command() { return "Subtract Background..."; }
    protected String label() { return "Subtract Background"; }
}
