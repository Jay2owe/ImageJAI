package imagejai.local.intents.analysis;

import imagejai.local.Intent;

import java.util.ArrayList;
import java.util.List;

public final class AnalysisIntentFactory {

    private AnalysisIntentFactory() {
    }

    public static List<Intent> createAll() {
        List<Intent> intents = new ArrayList<Intent>();

        intents.add(new SubtractBackgroundIntent());
        intents.add(new GaussianBlurIntent());
        intents.add(new MedianFilterIntent());
        intents.add(new MeanFilterIntent());
        intents.add(new VarianceFilterIntent());
        intents.add(new UnsharpMaskIntent());
        intents.add(new BandpassFilterIntent());

        intents.add(new AutoThresholdIntent());
        intents.add(new CompareThresholdsIntent());
        intents.add(new ConvertToMaskIntent());
        intents.add(new CountCellsIntent());
        intents.add(new CountParticlesIntent());
        intents.add(new CountNucleiIntent());
        intents.add(new FillHolesIntent());
        intents.add(new WatershedIntent());
        intents.add(new SkeletonizeIntent());
        intents.add(new DistanceMapIntent());
        intents.add(new VoronoiIntent());
        intents.add(new FindMaximaIntent());

        intents.add(new MeasureIntensityIntent());
        intents.add(new MeasureCtcfIntent());
        intents.add(new MeasureRoisIntent());
        intents.add(new SummariseIntent());
        intents.add(new ClearResultsIntent());
        intents.add(new SetMeasurementsIntent());
        intents.add(new LineProfileIntent());
        intents.add(new HistogramIntent());
        intents.add(new NearestNeighbourDistanceIntent());

        intents.add(new OpenThresholdDialogIntent());
        intents.add(new OpenAnalyzeParticlesDialogIntent());
        intents.add(new OpenFindMaximaDialogIntent());
        intents.add(new OpenGaussianBlurDialogIntent());
        intents.add(new OpenSubtractBackgroundDialogIntent());

        return intents;
    }
}
