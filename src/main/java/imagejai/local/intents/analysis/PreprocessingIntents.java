package imagejai.local.intents.analysis;

import ij.ImagePlus;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;

import java.util.Map;

class SubtractBackgroundIntent extends AbstractAnalysisIntent {
    public String id() { return "preprocess.subtract_background"; }
    public String description() { return "Subtract background using a rolling-ball radius"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double radius = doubleSlot(slots, "radius", 50.0);
        String macro = "run(\"Subtract Background...\", \"rolling=" + fmt(radius) + "\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Subtracted background with rolling-ball radius " + fmt(radius) + " px.", macro);
    }
}

class GaussianBlurIntent extends AbstractAnalysisIntent {
    public String id() { return "preprocess.gaussian_blur"; }
    public String description() { return "Apply Gaussian blur"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double sigma = doubleSlot(slots, "sigma", 1.0);
        String macro = "run(\"Gaussian Blur...\", \"sigma=" + fmt(sigma) + "\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Applied Gaussian blur with sigma " + fmt(sigma) + ".", macro);
    }
}

class MedianFilterIntent extends AbstractAnalysisIntent {
    public String id() { return "preprocess.median_filter"; }
    public String description() { return "Apply a median filter"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double radius = doubleSlot(slots, "radius", 2.0);
        String macro = "run(\"Median...\", \"radius=" + fmt(radius) + "\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Applied Median filter with radius " + fmt(radius) + ".", macro);
    }
}

class MeanFilterIntent extends AbstractAnalysisIntent {
    public String id() { return "preprocess.mean_filter"; }
    public String description() { return "Apply a mean filter"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double radius = doubleSlot(slots, "radius", 2.0);
        String macro = "run(\"Mean...\", \"radius=" + fmt(radius) + "\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Applied Mean filter with radius " + fmt(radius) + ".", macro);
    }
}

class VarianceFilterIntent extends AbstractAnalysisIntent {
    public String id() { return "preprocess.variance"; }
    public String description() { return "Apply a variance filter"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double radius = doubleSlot(slots, "radius", 2.0);
        String macro = "run(\"Variance...\", \"radius=" + fmt(radius) + "\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Applied Variance filter with radius " + fmt(radius) + ".", macro);
    }
}

class UnsharpMaskIntent extends AbstractAnalysisIntent {
    public String id() { return "preprocess.unsharp_mask"; }
    public String description() { return "Apply an unsharp mask"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double radius = doubleSlot(slots, "radius", 3.0);
        double mask = doubleSlot(slots, "mask", 0.60);
        String macro = "run(\"Unsharp Mask...\", \"radius=" + fmt(radius) + " mask=" + fmt(mask) + "\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Applied Unsharp Mask with radius " + fmt(radius) + " and mask " + fmt(mask) + ".", macro);
    }
}

class BandpassFilterIntent extends AbstractAnalysisIntent {
    public String id() { return "preprocess.bandpass_filter"; }
    public String description() { return "Apply a bandpass filter"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double large = doubleSlot(slots, "large", 40.0);
        double small = doubleSlot(slots, "small", 3.0);
        String macro = "run(\"Bandpass Filter...\", \"filter_large=" + fmt(large)
                + " filter_small=" + fmt(small) + " suppress=None tolerance=5\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Applied Bandpass Filter (large " + fmt(large)
                + ", small " + fmt(small) + ").", macro);
    }
}
