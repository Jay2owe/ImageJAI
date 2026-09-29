package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression coverage for the C/Z/T contract of {@code get_pixels}. */
public class TCPCommandServerGetPixelsHyperstackTest {

    @Test
    public void requestedSliceIsZAtCurrentChannelAndFrame() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        ImagePlus imp = uniqueHyperstack();
        imp.setPosition(2, 2, 2);
        server.currentImageForTest = () -> imp;
        try {
            JsonObject result = result(server, "{\"command\":\"get_pixels\",\"slice\":1}");

            assertArrayEquals(new float[] {212.0f}, decode(result), 0.0f);
            assertCoordinates(result, 2, 1, 1, 2);
            assertEquals(2, imp.getC());
            assertEquals(2, imp.getZ());
            assertEquals(2, imp.getT());
        } finally {
            server.stop();
        }
    }

    @Test
    public void omittedSliceReadsCurrentZAtCurrentChannelAndFrame() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        ImagePlus imp = uniqueHyperstack();
        imp.setPosition(2, 2, 2);
        server.currentImageForTest = () -> imp;
        try {
            JsonObject result = result(server, "{\"command\":\"get_pixels\"}");

            assertArrayEquals(new float[] {222.0f}, decode(result), 0.0f);
            assertCoordinates(result, 2, 2, 2, 2);
        } finally {
            server.stop();
        }
    }

    @Test
    public void allSlicesReadsOnlyZAtCurrentChannelAndFrame() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        ImagePlus imp = uniqueHyperstack();
        imp.setPosition(2, 2, 2);
        server.currentImageForTest = () -> imp;
        try {
            JsonObject result = result(server,
                    "{\"command\":\"get_pixels\",\"allSlices\":true}");

            assertArrayEquals(new float[] {212.0f, 222.0f, 232.0f},
                    decode(result), 0.0f);
            assertCoordinates(result, 2, 1, 3, 2);
            assertEquals(3, result.get("sliceCount").getAsInt());
            assertEquals(3, result.get("nPixels").getAsInt());
        } finally {
            server.stop();
        }
    }

    @Test
    public void outOfRangeZIsRejectedInsteadOfClamped() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        ImagePlus imp = uniqueHyperstack();
        imp.setPosition(2, 2, 2);
        server.currentImageForTest = () -> imp;
        try {
            JsonObject high = server.dispatch(parse(
                    "{\"command\":\"get_pixels\",\"slice\":4}"),
                    new TCPCommandServer.AgentCaps());
            JsonObject zero = server.dispatch(parse(
                    "{\"command\":\"get_pixels\",\"slice\":0}"),
                    new TCPCommandServer.AgentCaps());

            assertFalse(high.toString(), high.get("ok").getAsBoolean());
            assertFalse(zero.toString(), zero.get("ok").getAsBoolean());
            assertTrue(high.toString(), high.toString().contains("1-based Z"));
            assertTrue(zero.toString(), zero.toString().contains("1-based Z"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void ordinaryStackKeepsLinearZBehavior() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new FloatProcessor(1, 1, new float[] {1.0f}, null));
        stack.addSlice(new FloatProcessor(1, 1, new float[] {2.0f}, null));
        stack.addSlice(new FloatProcessor(1, 1, new float[] {3.0f}, null));
        ImagePlus imp = new ImagePlus("plain-stack", stack);
        imp.setSlice(2);
        server.currentImageForTest = () -> imp;
        try {
            JsonObject result = result(server,
                    "{\"command\":\"get_pixels\",\"allSlices\":true}");

            assertArrayEquals(new float[] {1.0f, 2.0f, 3.0f},
                    decode(result), 0.0f);
            assertEquals(1, result.get("channel").getAsInt());
            assertEquals(1, result.get("sliceStart").getAsInt());
            assertEquals(3, result.get("sliceEnd").getAsInt());
            assertEquals(1, result.get("frame").getAsInt());
            assertEquals("Z", result.get("sliceAxis").getAsString());
            assertEquals(1, result.get("channels").getAsInt());
            assertEquals(3, result.get("slices").getAsInt());
            assertEquals(1, result.get("frames").getAsInt());
        } finally {
            server.stop();
        }
    }

    private static JsonObject result(TCPCommandServer server, String request) {
        JsonObject response = server.dispatch(parse(request),
                new TCPCommandServer.AgentCaps());
        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        return response.getAsJsonObject("result");
    }

    private static void assertCoordinates(JsonObject result, int channel,
                                          int zStart, int zEnd, int frame) {
        assertEquals(channel, result.get("channel").getAsInt());
        assertEquals(zStart, result.get("sliceStart").getAsInt());
        assertEquals(zEnd, result.get("sliceEnd").getAsInt());
        assertEquals(frame, result.get("frame").getAsInt());
        assertEquals("Z", result.get("sliceAxis").getAsString());
        assertEquals(2, result.get("channels").getAsInt());
        assertEquals(3, result.get("slices").getAsInt());
        assertEquals(2, result.get("frames").getAsInt());
    }

    private static float[] decode(JsonObject result) {
        byte[] bytes = Base64.getDecoder().decode(result.get("data").getAsString());
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[bytes.length / 4];
        for (int i = 0; i < values.length; i++) values[i] = buffer.getFloat();
        return values;
    }

    private static ImagePlus uniqueHyperstack() {
        int channels = 2;
        int slices = 3;
        int frames = 2;
        ImageStack stack = new ImageStack(1, 1);
        for (int t = 1; t <= frames; t++) {
            for (int z = 1; z <= slices; z++) {
                for (int c = 1; c <= channels; c++) {
                    float value = 100.0f * c + 10.0f * z + t;
                    stack.addSlice(new FloatProcessor(1, 1,
                            new float[] {value}, null));
                }
            }
        }
        ImagePlus imp = new ImagePlus("unique-hyperstack", stack);
        imp.setDimensions(channels, slices, frames);
        imp.setOpenAsHyperStack(true);
        return imp;
    }

    private static JsonObject parse(String value) {
        return new JsonParser().parse(value).getAsJsonObject();
    }
}
