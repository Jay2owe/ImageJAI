package imagejai.local.intents.analysis;

import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;
import imagejai.local.Intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Delegates reviewed tools/intents.yaml aggregate IDs to split handlers. */
final class ReviewedAnalysisAliases {

    private ReviewedAnalysisAliases() {
    }

    static List<Intent> createAll() {
        List<Intent> aliases = new ArrayList<Intent>();
        aliases.add(alias("preprocess.variance_filter",
                "Apply a variance filter with a specified radius", new VarianceFilterIntent()));
        aliases.add(alias("segment.auto_threshold",
                "Auto-threshold the active image using a named threshold method and optional dark background",
                new AutoThresholdIntent()));
        aliases.add(alias("segment.compare_thresholds",
                "Compare several threshold methods for the active image", new CompareThresholdsIntent()));
        aliases.add(alias("segment.convert_to_mask",
                "Convert the current threshold to a binary mask with black background handling",
                new ConvertToMaskIntent()));

        Map<String, Intent> countTargets = new LinkedHashMap<String, Intent>();
        countTargets.put("particles", new CountParticlesIntent());
        countTargets.put("cells", new CountCellsIntent());
        countTargets.put("nuclei", new CountNucleiIntent());
        aliases.add(new SelectingIntent("segment.count_particles",
                "Count cells, nuclei, or particles in the active image",
                "object_type", "particles", countTargets));

        aliases.add(alias("segment.fill_holes", "Fill holes in a binary mask",
                new FillHolesIntent()));
        aliases.add(alias("segment.watershed",
                "Split touching objects in a binary mask with watershed", new WatershedIntent()));
        aliases.add(alias("segment.skeletonize", "Skeletonize a binary mask",
                new SkeletonizeIntent()));
        aliases.add(alias("segment.distance_map", "Create a distance map from a binary mask",
                new DistanceMapIntent()));
        aliases.add(alias("segment.voronoi",
                "Create a Voronoi segmentation from a binary mask", new VoronoiIntent()));
        aliases.add(alias("segment.find_maxima",
                "Find local maxima with a specified prominence", new FindMaximaIntent()));

        aliases.add(alias("measure.intensity",
                "Measure intensity in the active image or current selection",
                new MeasureIntensityIntent()));
        aliases.add(alias("measure.ctcf", "Measure corrected total cell fluorescence",
                new MeasureCtcfIntent()));
        aliases.add(alias("measure.rois", "Measure all ROIs in the ROI Manager",
                new MeasureRoisIntent()));
        aliases.add(alias("results.summarise", "Summarise the current results table",
                new SummariseIntent()));
        aliases.add(alias("results.clear", "Clear the results table",
                new ClearResultsIntent()));
        aliases.add(alias("measure.set_measurements",
                "Set measurement options such as area, mean, integrated density, and shape descriptors",
                new SetMeasurementsIntent()));
        aliases.add(alias("measure.line_profile",
                "Plot or report the intensity profile along a line selection", new LineProfileIntent()));
        aliases.add(alias("measure.histogram", "Show the active image histogram",
                new HistogramIntent()));
        aliases.add(alias("measure.nearest_neighbour",
                "Measure nearest-neighbour distances between objects or ROIs",
                new NearestNeighbourDistanceIntent()));
        return Collections.unmodifiableList(aliases);
    }

    private static Intent alias(String id, String description, Intent target) {
        return new DelegatingIntent(id, description, target);
    }

    private static class DelegatingIntent implements Intent {
        private final String id;
        private final String description;
        private final Intent target;

        DelegatingIntent(String id, String description, Intent target) {
            this.id = id;
            this.description = description;
            this.target = target;
        }

        public String id() {
            return id;
        }

        public String description() {
            return description;
        }

        public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
            return target.execute(slots, fiji);
        }
    }

    private static final class SelectingIntent extends DelegatingIntent {
        private final String slot;
        private final String fallback;
        private final Map<String, Intent> targets;

        SelectingIntent(String id, String description, String slot, String fallback,
                        Map<String, Intent> targets) {
            super(id, description, targets.get(fallback));
            this.slot = slot;
            this.fallback = fallback;
            this.targets = Collections.unmodifiableMap(new LinkedHashMap<String, Intent>(targets));
        }

        @Override
        public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
            String selected = slots == null ? null : slots.get(slot);
            Intent target = targets.get(selected == null ? fallback : selected);
            if (target == null) {
                target = targets.get(fallback);
            }
            return target.execute(slots, fiji);
        }
    }
}
