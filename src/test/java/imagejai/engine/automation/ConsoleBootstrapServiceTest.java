package imagejai.engine.automation;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConsoleBootstrapServiceTest {
    @After
    public void clearFlag() {
        System.getProperties().remove(ConsoleBootstrapService.WATCHER_PROPERTY);
    }

    @Test
    public void macroEntryPointStartsOneWatcherOnly() {
        System.getProperties().remove(ConsoleBootstrapService.WATCHER_PROPERTY);
        // The service and an IJ1 macro call may both arrive; only one may watch.
        assertEquals("watching", ConsoleBootstrapService.startWatcher());
        assertEquals("already watching", ConsoleBootstrapService.startWatcher());
    }

    @Test
    public void flagIsSharedAcrossClassLoadersThroughSystemProperties() {
        // A copy loaded by another class loader sees the same JVM-wide flag.
        System.setProperty(ConsoleBootstrapService.WATCHER_PROPERTY, "running");
        assertEquals("already watching", ConsoleBootstrapService.startWatcher());
    }
}
