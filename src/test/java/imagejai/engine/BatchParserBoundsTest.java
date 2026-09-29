package imagejai.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BatchParserBoundsTest {

    @Test
    public void exactSegmentLimitParsesAndPreservesEscapedSeparator() {
        StringBuilder chain = new StringBuilder("macro value\\|||literal");
        for (int i = 1; i < BatchParser.DEFAULT_MAX_SEGMENTS; i++) {
            chain.append("|||ping");
        }

        List<JsonObject> parsed = BatchParser.parse(chain.toString());

        assertEquals(BatchParser.DEFAULT_MAX_SEGMENTS, parsed.size());
        assertEquals("value|||literal", parsed.get(0).get("code").getAsString());
    }

    @Test
    public void segmentSixtyFiveAbortsBeforeScanningSeparatorHeavyTail() {
        StringBuilder chain = new StringBuilder(1024 * 1024);
        for (int i = 0; i < 66; i++) {
            if (i > 0) chain.append("|||");
            chain.append("ping");
        }
        while (chain.length() < 1024 * 1024) chain.append("|||");

        try {
            BatchParser.parse(chain.toString(), 64);
            fail("Expected parser to reject segment 65");
        } catch (BatchParser.SegmentLimitException expected) {
            assertTrue(expected.getMessage().contains("max 64"));
            assertTrue("parser must abort near segment 65, before the large tail",
                    expected.offset() < 1024);
        }
    }
}
