package imagejai.engine.automation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import org.scijava.service.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AutomationPluginIndexTest {
    @Test
    public void bundledSciJavaIndexRegistersBootService() throws Exception {
        String resource = "META-INF/json/org.scijava.plugin.Plugin";
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resource);
        assertNotNull("Missing SciJava plugin index", stream);
        boolean registered = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JsonObject entry = JsonParser.parseString(line).getAsJsonObject();
                if (AutomationBootstrapService.class.getName().equals(
                        entry.get("class").getAsString())) {
                    assertEquals(Service.class.getName(), entry.getAsJsonObject("values")
                            .get("type").getAsString());
                    registered = true;
                }
            }
        }
        assertTrue("Fiji will not load the automation boot service", registered);
        assertTrue(Service.class.isAssignableFrom(AutomationBootstrapService.class));
    }
}
