package imagejai.local.intents.control;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.measure.Calibration;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;
import imagejai.local.SlotSpec;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class CloseAllIntent extends AbstractControlIntent {
    public String id() { return "image.close_all"; }
    public String description() { return "Close all open images"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        int[] ids = WindowManager.getIDList();
        int closed = closeImages(ids, imp == null ? Integer.MIN_VALUE : Integer.MIN_VALUE);
        return AssistantReply.withMacro("Closed " + plural(closed, "image") + ".",
                "// Closed image windows only; the Log window was not touched.");
    }

    static int closeImages(int[] ids, int keepId) {
        if (ids == null) {
            return 0;
        }
        int closed = 0;
        for (int id : ids) {
            if (id == keepId) {
                continue;
            }
            ImagePlus image = WindowManager.getImage(id);
            if (image != null) {
                image.close();
                closed++;
            }
        }
        return closed;
    }
}

class CloseActiveIntent extends AbstractControlIntent {
    public String id() { return "image.close_active"; }
    public String description() { return "Close the active image"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String title = imp.getTitle();
        imp.close();
        return AssistantReply.withMacro("Closed " + title + ".", "close();");
    }
}

class CloseOthersIntent extends AbstractControlIntent {
    public String id() { return "image.close_others"; }
    public String description() { return "Close all images except the active image"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        int closed = CloseAllIntent.closeImages(WindowManager.getIDList(), imp.getID());
        return AssistantReply.withMacro("Closed " + plural(closed, "other image") + ".",
                "close(\"\\\\Others\"); // image windows only; Log window preserved");
    }
}

class DuplicateActiveIntent extends AbstractControlIntent {
    public String id() { return "image.duplicate_active"; }
    public String description() { return "Duplicate the active image"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        ImagePlus copy = imp.duplicate();
        copy.setTitle(WindowManager.getUniqueName(imp.getTitle() + " copy"));
        copy.show();
        return AssistantReply.withMacro("Duplicated " + imp.getTitle() + ".", "run(\"Duplicate...\");");
    }
}

class RevertIntent extends AbstractControlIntent {
    public String id() { return "image.revert"; }
    public String description() { return "Revert the active image"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        if (imp.getOriginalFileInfo() == null) {
            return AssistantReply.text("This image has no saved file to revert to.");
        }
        imp.revert();
        return AssistantReply.withMacro("Reverted " + imp.getTitle() + " to its saved file.", "run(\"Revert\");");
    }
}

abstract class SaveAsImageIntent extends AbstractControlIntent {
    protected abstract String format();
    protected abstract String extension();
    protected abstract boolean save(FileSaver saver, String path);

    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        Path dir = fiji.resolveAiExportsDir();
        File file = chooseFile(dir.toFile(), safeBaseName(imp.getTitle()) + "." + extension(), extension(), format());
        if (file == null) {
            return AssistantReply.text("Save cancelled.");
        }
        String path = ensureExtension(file, extension()).getAbsolutePath();
        boolean ok = save(new FileSaver(imp), path);
        if (!ok) {
            return AssistantReply.text("Could not save " + format() + " to " + path + ".");
        }
        return AssistantReply.withMacro("Saved " + format() + " to " + path + ".",
                "saveAs(\"" + format() + "\", \"" + macroQuote(path.replace(File.separatorChar, '/')) + "\");");
    }

    private File chooseFile(final File dir, final String defaultName,
                            final String ext, final String label) {
        final AtomicReference<File> selected = new AtomicReference<File>();
        Runnable chooserTask = new Runnable() {
            public void run() {
                JFileChooser chooser = new JFileChooser(dir);
                chooser.setSelectedFile(new File(dir, defaultName));
                chooser.setFileFilter(new FileNameExtensionFilter(label + " (*." + ext + ")", ext));
                int result = chooser.showSaveDialog(null);
                if (result == JFileChooser.APPROVE_OPTION) {
                    selected.set(chooser.getSelectedFile());
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            chooserTask.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(chooserTask);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("Could not open the save dialog: "
                        + e.getTargetException().getMessage(), e);
            }
        }
        return selected.get();
    }

    private File ensureExtension(File file, String ext) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith("." + ext)) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + "." + ext);
    }

    private String safeBaseName(String title) {
        String base = title == null ? "image" : title.replaceAll("[\\\\/:*?\"<>|]", "_");
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.length() == 0 ? "image" : base;
    }
}

class SaveAsTiffIntent extends SaveAsImageIntent {
    public String id() { return "image.save_tiff"; }
    public String description() { return "Save the active image as TIFF"; }
    protected String format() { return "Tiff"; }
    protected String extension() { return "tif"; }
    protected boolean save(FileSaver saver, String path) { return saver.saveAsTiff(path); }
}

class SaveAsPngIntent extends SaveAsImageIntent {
    public String id() { return "image.save_png"; }
    public String description() { return "Save the active image as PNG"; }
    protected String format() { return "PNG"; }
    protected String extension() { return "png"; }
    protected boolean save(FileSaver saver, String path) { return saver.saveAsPng(path); }
}

class SaveAsJpegIntent extends SaveAsImageIntent {
    public String id() { return "image.save_jpeg"; }
    public String description() { return "Save the active image as JPEG"; }
    protected String format() { return "Jpeg"; }
    protected String extension() { return "jpg"; }
    protected boolean save(FileSaver saver, String path) { return saver.saveAsJpeg(path); }
}

class NextSliceIntent extends AbstractControlIntent {
    public String id() { return "image.next_slice"; }
    public String description() { return "Move to the next slice"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        int next = Math.min(imp.getNSlices(), imp.getZ() + 1);
        imp.setZ(next);
        return AssistantReply.withMacro("Active slice: " + next + " of " + imp.getNSlices() + ".", "Stack.setSlice(" + next + ");");
    }
}

class PreviousSliceIntent extends AbstractControlIntent {
    public String id() { return "image.previous_slice"; }
    public String description() { return "Move to the previous slice"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        int prev = Math.max(1, imp.getZ() - 1);
        imp.setZ(prev);
        return AssistantReply.withMacro("Active slice: " + prev + " of " + imp.getNSlices() + ".", "Stack.setSlice(" + prev + ");");
    }
}

class NextOpenImageIntent extends AbstractControlIntent {
    public String id() { return "image.next_open_image"; }
    public String description() { return "Switch to the next open image"; }

    protected boolean requiresImage() {
        return false;
    }

    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        int[] ids = WindowManager.getIDList();
        if (ids == null || ids.length == 0) {
            return AssistantReply.text("No images are open.");
        }
        ImagePlus current = WindowManager.getCurrentImage();
        int currentId = current == null ? Integer.MIN_VALUE : current.getID();
        int nextIndex = 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == currentId) {
                nextIndex = (i + 1) % ids.length;
                break;
            }
        }
        ImagePlus target = WindowManager.getImage(ids[nextIndex]);
        if (target == null) {
            return AssistantReply.text("No images are open.");
        }
        ImageWindow window = target.getWindow();
        if (window != null) {
            WindowManager.setCurrentWindow(window);
        } else {
            WindowManager.setTempCurrentImage(target);
        }
        return AssistantReply.withMacro("Active image: " + target.getTitle() + ".",
                "selectImage(\"" + macroQuote(target.getTitle()) + "\");");
    }
}

class SwitchChannelIntent extends AbstractControlIntent {
    public String id() { return "image.switch_channel"; }
    public String description() { return "Switch to a channel number"; }
    public List<SlotSpec> requiredSlots() { return List.of(new SlotSpec("channel", "channel number", null)); }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        int channel = intSlot(slots, "channel", -1);
        if (channel < 1 || channel > imp.getNChannels()) {
            return AssistantReply.text("Tell me a channel from 1 to " + imp.getNChannels() + ".");
        }
        imp.setC(channel);
        return AssistantReply.withMacro("Switched to channel " + channel + ".", "Stack.setChannel(" + channel + ");");
    }
}

class JumpSliceIntent extends AbstractControlIntent {
    public String id() { return "image.jump_slice"; }
    public String description() { return "Jump to a slice number"; }
    public List<SlotSpec> requiredSlots() { return List.of(new SlotSpec("slice", "slice number", null)); }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        int slice = intSlot(slots, "slice", -1);
        if (slice < 1 || slice > imp.getNSlices()) {
            return AssistantReply.text("Tell me a slice from 1 to " + imp.getNSlices() + ".");
        }
        imp.setZ(slice);
        return AssistantReply.withMacro("Jumped to slice " + slice + ".", "Stack.setSlice(" + slice + ");");
    }
}

class JumpFrameIntent extends AbstractControlIntent {
    public String id() { return "image.jump_frame"; }
    public String description() { return "Jump to a frame number"; }
    public List<SlotSpec> requiredSlots() { return List.of(new SlotSpec("frame", "frame number", null)); }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        int frame = intSlot(slots, "frame", -1);
        if (frame < 1 || frame > imp.getNFrames()) {
            return AssistantReply.text("Tell me a frame from 1 to " + imp.getNFrames() + ".");
        }
        imp.setT(frame);
        return AssistantReply.withMacro("Jumped to frame " + frame + ".", "Stack.setFrame(" + frame + ");");
    }
}

class MergeChannelsIntent extends AbstractControlIntent {
    public String id() { return "image.merge_channels"; }
    public String description() { return "Open Merge Channels"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        IJ.run("Merge Channels...");
        return AssistantReply.withMacro("Opened Merge Channels so you can choose the channel images.",
                "run(\"Merge Channels...\");");
    }
}

class SplitChannelsIntent extends AbstractControlIntent {
    public String id() { return "image.split_channels"; }
    public String description() { return "Split channels"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        IJ.run(imp, "Split Channels", "");
        return AssistantReply.withMacro("Split channels for " + imp.getTitle() + ".", "run(\"Split Channels\");");
    }
}

abstract class ZProjectIntent extends AbstractControlIntent {
    protected abstract String method();
    public String description() { return "Create a z projection"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        if (imp.getNSlices() < 2) {
            return AssistantReply.text("The active image has only one slice.");
        }
        String macro = "run(\"Z Project...\", \"projection=[" + method() + "]\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Created " + method() + " z projection.", macro);
    }
}

class ZProjectMaxIntent extends ZProjectIntent {
    public String id() { return "image.z_project_max"; }
    protected String method() { return "Max Intensity"; }
}

class ZProjectMeanIntent extends ZProjectIntent {
    public String id() { return "image.z_project_mean"; }
    protected String method() { return "Average Intensity"; }
}

class ZProjectSumIntent extends ZProjectIntent {
    public String id() { return "image.z_project_sum"; }
    protected String method() { return "Sum Slices"; }
}

class ZProjectSdIntent extends ZProjectIntent {
    public String id() { return "image.z_project_sd"; }
    protected String method() { return "Standard Deviation"; }
}

class MakeSubstackIntent extends AbstractControlIntent {
    public String id() { return "image.make_substack"; }
    public String description() { return "Make a channel, slice, or frame substack"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String channels = stringSlot(slots, "channels");
        String slices = stringSlot(slots, "slices");
        String frames = stringSlot(slots, "frames");
        if (channels.length() == 0 && slices.length() == 0 && frames.length() == 0) {
            return AssistantReply.text("Tell me which channels, slices, or frames to include, for example: make substack slices 5-20.");
        }
        if (channels.length() == 0) channels = "1-" + imp.getNChannels();
        if (slices.length() == 0) slices = "1-" + imp.getNSlices();
        if (frames.length() == 0) frames = "1-" + imp.getNFrames();
        String options = "channels=" + channels + " slices=" + slices + " frames=" + frames;
        String macro = "run(\"Make Substack...\", \"" + macroQuote(options) + "\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Made substack (" + options + ").", macro);
    }
}

class CropToSelectionIntent extends AbstractControlIntent {
    public String id() { return "image.crop_to_selection"; }
    public String description() { return "Crop to the current selection"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        Roi roi = imp.getRoi();
        if (roi == null) {
            return AssistantReply.text("No selection is active.");
        }
        IJ.run(imp, "Crop", "");
        return AssistantReply.withMacro("Cropped to the current selection.", "run(\"Crop\");");
    }
}

class ScaleByFactorIntent extends AbstractControlIntent {
    public String id() { return "image.scale_by_factor"; }
    public String description() { return "Scale the active image by a factor"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double factor = doubleSlot(slots, "factor", -1.0);
        if (factor <= 0.0) {
            return AssistantReply.text("Tell me a positive scale factor, for example: scale image by 0.5.");
        }
        String options = String.format(Locale.ROOT, "x=%s y=%s interpolation=Bilinear create", fmt(factor), fmt(factor));
        String macro = "run(\"Scale...\", \"" + options + "\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Scaled image by " + fmt(factor) + "x.", macro);
    }
}

class InvertImageIntent extends AbstractControlIntent {
    public String id() { return "image.invert_pixels"; }
    public String description() { return "Invert pixel values"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        IJ.run(imp, "Invert", "");
        return AssistantReply.withMacro("Inverted pixel values.", "run(\"Invert\");");
    }
}

class InvertLutIntent extends AbstractControlIntent {
    public String id() { return "image.invert_lut"; }
    public String description() { return "Invert the lookup table"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        IJ.run(imp, "Invert LUT", "");
        return AssistantReply.withMacro("Inverted the LUT only; pixel values were not changed.", "run(\"Invert LUT\");");
    }
}

abstract class ConvertTypeIntent extends AbstractControlIntent {
    protected abstract String command();
    protected abstract String label();
    public String description() { return "Convert image type"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        IJ.run(imp, command(), "");
        return AssistantReply.withMacro("Converted to " + label() + ".", "run(\"" + command() + "\");");
    }
}

class ConvertTo8BitIntent extends ConvertTypeIntent {
    public String id() { return "image.convert_8bit"; }
    protected String command() { return "8-bit"; }
    protected String label() { return "8-bit"; }
}

class ConvertTo16BitIntent extends ConvertTypeIntent {
    public String id() { return "image.convert_16bit"; }
    protected String command() { return "16-bit"; }
    protected String label() { return "16-bit"; }
}

class ConvertTo32BitIntent extends ConvertTypeIntent {
    public String id() { return "image.convert_32bit"; }
    protected String command() { return "32-bit"; }
    protected String label() { return "32-bit"; }
}

class ConvertToRgbIntent extends ConvertTypeIntent {
    public String id() { return "image.convert_rgb"; }
    protected String command() { return "RGB Color"; }
    protected String label() { return "RGB"; }
}

class ConvertToCompositeIntent extends AbstractControlIntent {
    public String id() { return "image.convert_composite"; }
    public String description() { return "Convert to composite display"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        if (imp instanceof CompositeImage) {
            ((CompositeImage) imp).setMode(IJ.COMPOSITE);
            imp.updateAndDraw();
        } else {
            IJ.run(imp, "Make Composite", "");
        }
        return AssistantReply.withMacro("Converted to composite display.", "run(\"Make Composite\");");
    }
}

class SetScaleIntent extends AbstractControlIntent {
    public String id() { return "image.set_scale"; }
    public String description() { return "Set spatial calibration"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double pixels = doubleSlot(slots, "pixels", -1.0);
        double distance = doubleSlot(slots, "distance", -1.0);
        String unit = stringSlot(slots, "unit");
        if (pixels <= 0.0 || distance <= 0.0 || unit.length() == 0) {
            return AssistantReply.text("Tell me the calibration as N px = M unit, for example: set scale 100 px = 10 um.");
        }
        Calibration cal = imp.getCalibration();
        double pixelSize = distance / pixels;
        cal.pixelWidth = pixelSize;
        cal.pixelHeight = pixelSize;
        cal.setUnit(unit);
        imp.setCalibration(cal);
        String macro = "run(\"Set Scale...\", \"distance=" + fmt(pixels)
                + " known=" + fmt(distance) + " pixel=1 unit=" + macroQuote(unit) + "\");";
        return AssistantReply.withMacro("Set scale: " + fmt(pixels) + " px = "
                + fmt(distance) + " " + unit + ".", macro);
    }
}
