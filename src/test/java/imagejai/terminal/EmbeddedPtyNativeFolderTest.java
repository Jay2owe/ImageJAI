package imagejai.terminal;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class EmbeddedPtyNativeFolderTest {

    @Test
    public void configurePtyNativeFolderDoesNotOverrideNativeExtraction() throws Exception {
        String oldTmp = System.getProperty("pty4j.tmpdir");
        String oldPreferred = System.getProperty("pty4j.preferred.native.folder");
        System.clearProperty("pty4j.tmpdir");
        System.setProperty("pty4j.preferred.native.folder",
                new java.io.File(System.getProperty("java.io.tmpdir"),
                        "imagejai-pty4j-native").getAbsolutePath());
        try {
            Method method = EmbeddedPty.class.getDeclaredMethod("configurePtyNativeFolder");
            method.setAccessible(true);
            method.invoke(null);

            assertNotNull(System.getProperty("pty4j.tmpdir"));
            assertNull(System.getProperty("pty4j.preferred.native.folder"));
        } finally {
            restore("pty4j.tmpdir", oldTmp);
            restore("pty4j.preferred.native.folder", oldPreferred);
        }
    }

    @Test
    public void configureClearsStalePreferredFolderEvenWhenTmpdirAlreadySet() throws Exception {
        String oldTmp = System.getProperty("pty4j.tmpdir");
        String oldPreferred = System.getProperty("pty4j.preferred.native.folder");
        String imagejaiTmp = new java.io.File(System.getProperty("java.io.tmpdir"),
                "imagejai-pty4j-native").getAbsolutePath();
        System.setProperty("pty4j.tmpdir", imagejaiTmp);
        System.setProperty("pty4j.preferred.native.folder", imagejaiTmp);
        try {
            Method method = EmbeddedPty.class.getDeclaredMethod("configurePtyNativeFolder");
            method.setAccessible(true);
            method.invoke(null);

            assertNotNull(System.getProperty("pty4j.tmpdir"));
            assertNull(System.getProperty("pty4j.preferred.native.folder"));
        } finally {
            restore("pty4j.tmpdir", oldTmp);
            restore("pty4j.preferred.native.folder", oldPreferred);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
