package imagejai.engine.security;

import java.util.regex.Pattern;

/**
 * Removes OME-XML fields that commonly carry people, instrument serials, or
 * acquisition dates while leaving pixel and calibration structures intact.
 */
public final class OmeXmlScrubber {
    private static final String[] TAGS = new String[] {
            "Experimenter",
            "Description",
            "StageLabel",
            "AcquisitionDate",
            "InstrumentRef",
            "InstrumentSerialNumber",
            "Annotation",
            "XMLAnnotation",
            "CommentAnnotation",
            "FileAnnotation",
            "ListAnnotation",
            "LongAnnotation",
            "MapAnnotation",
            "TagAnnotation",
            "TermAnnotation",
            "TimestampAnnotation"
    };

    public String scrub(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String out = value;
        for (String tag : TAGS) {
            out = stripElement(out, tag);
        }
        return out;
    }

    private static String stripElement(String xml, String tag) {
        String q = Pattern.quote(tag);
        String prefix = "(?:[A-Za-z_][A-Za-z0-9_.-]*:)?";
        String open = "<" + prefix + q + "\\b[^>]*";
        String paired = open + ">.*?</" + prefix + q + "\\s*>";
        String selfClosing = open + "/\\s*>";
        String redacted = "<" + tag + " redacted=\"true\"/>";
        return Pattern.compile(paired, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(Pattern.compile(selfClosing, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                        .matcher(xml)
                        .replaceAll(redacted))
                .replaceAll(redacted);
    }
}
