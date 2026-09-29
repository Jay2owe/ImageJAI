package imagejai.terminal;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class EmbeddedPtyEnvironmentTest {

    @Test
    public void ptyEnvironmentPreservesHostVariablesAndAddsImageJaiValues() {
        Map<String, String> inherited = new LinkedHashMap<String, String>();
        inherited.put("PATH", "C:\\Windows\\System32");
        inherited.put("SystemRoot", "C:\\Windows");
        inherited.put("ComSpec", "C:\\Windows\\System32\\cmd.exe");

        Map<String, String> additions = new LinkedHashMap<String, String>();
        additions.put("IMAGEJAI_TCP_PORT", "7746");
        additions.put("TERM", "xterm-256color");

        Map<String, String> merged =
                EmbeddedPty.mergeEnvironment(inherited, additions);

        assertEquals("C:\\Windows\\System32", merged.get("PATH"));
        assertEquals("C:\\Windows", merged.get("SystemRoot"));
        assertEquals("C:\\Windows\\System32\\cmd.exe", merged.get("ComSpec"));
        assertEquals("7746", merged.get("IMAGEJAI_TCP_PORT"));
        assertEquals("xterm-256color", merged.get("TERM"));
    }

    @Test
    public void ptyEnvironmentIsAnIndependentMapAndAdditionsWin() {
        Map<String, String> inherited = new LinkedHashMap<String, String>();
        inherited.put("TERM", "host-terminal");
        Map<String, String> additions = new LinkedHashMap<String, String>();
        additions.put("TERM", "xterm-256color");

        Map<String, String> merged =
                EmbeddedPty.mergeEnvironment(inherited, additions);
        merged.put("NEW_VALUE", "child-only");

        assertEquals("xterm-256color", merged.get("TERM"));
        assertEquals("host-terminal", inherited.get("TERM"));
        assertFalse(inherited.containsKey("NEW_VALUE"));
        assertFalse(additions.containsKey("NEW_VALUE"));
    }
}
