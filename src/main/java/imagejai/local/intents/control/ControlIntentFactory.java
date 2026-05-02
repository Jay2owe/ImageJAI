package imagejai.local.intents.control;

import imagejai.local.Intent;

import java.util.ArrayList;
import java.util.List;

public final class ControlIntentFactory {

    private ControlIntentFactory() {
    }

    public static List<Intent> createAll() {
        List<Intent> intents = new ArrayList<Intent>();

        intents.add(new ImageDimensionsIntent());
        intents.add(new BitDepthIntent());
        intents.add(new ChannelCountIntent());
        intents.add(new SliceCountIntent());
        intents.add(new FrameCountIntent());
        intents.add(new ActiveChannelIntent());
        intents.add(new ActiveSliceIntent());
        intents.add(new ActiveFrameIntent());
        intents.add(new ImageTitleIntent());
        intents.add(new FilePathIntent());
        intents.add(new IntensityStatsIntent());
        intents.add(new ListOpenImagesIntent());
        intents.add(new SaturationCheckIntent());

        intents.add(new CloseAllIntent());
        intents.add(new CloseActiveIntent());
        intents.add(new CloseOthersIntent());
        intents.add(new DuplicateActiveIntent());
        intents.add(new RevertIntent());
        intents.add(new SaveAsTiffIntent());
        intents.add(new SaveAsPngIntent());
        intents.add(new SaveAsJpegIntent());
        intents.add(new NextSliceIntent());
        intents.add(new PreviousSliceIntent());
        intents.add(new SwitchChannelIntent());
        intents.add(new JumpSliceIntent());
        intents.add(new JumpFrameIntent());
        intents.add(new MergeChannelsIntent());
        intents.add(new SplitChannelsIntent());
        intents.add(new ZProjectMaxIntent());
        intents.add(new ZProjectMeanIntent());
        intents.add(new ZProjectSumIntent());
        intents.add(new ZProjectSdIntent());
        intents.add(new MakeSubstackIntent());
        intents.add(new CropToSelectionIntent());
        intents.add(new ScaleByFactorIntent());
        intents.add(new InvertImageIntent());
        intents.add(new InvertLutIntent());
        intents.add(new ConvertTo8BitIntent());
        intents.add(new ConvertTo16BitIntent());
        intents.add(new ConvertTo32BitIntent());
        intents.add(new ConvertToRgbIntent());
        intents.add(new ConvertToCompositeIntent());
        intents.add(new SetScaleIntent());

        intents.add(new ListRoisIntent());
        intents.add(new CountRoisIntent());
        intents.add(new ClearRoiManagerIntent());
        intents.add(new SaveRoisIntent());
        intents.add(new ShowResultsTableIntent());
        intents.add(new SaveResultsCsvIntent());

        intents.add(new AutoContrastIntent());
        intents.add(new ResetDisplayIntent());
        intents.add(new FitWindowIntent());
        intents.add(new SetZoomIntent());

        intents.add(new PluginCountIntent());
        intents.add(new OpenMacroRecorderIntent());
        intents.add(new OpenRoiManagerIntent());
        intents.add(new OpenChannelsToolIntent());
        intents.add(new ShowLogIntent());
        intents.add(new ShowConsoleIntent());
        intents.add(new OpenDialogsIntent());
        intents.add(new MemoryUsedIntent());
        intents.add(new GarbageCollectIntent());

        intents.add(new CapabilitiesIntent());
        intents.add(new CommandsIntent());
        intents.add(new CurrentAgentIntent());
        intents.add(new VersionIntent());
        return intents;
    }
}
