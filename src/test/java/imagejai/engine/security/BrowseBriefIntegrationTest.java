package imagejai.engine.security;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;

/**
 * Stage 09 integration placeholder.
 *
 * <p>Enable with the rest of the integration suite via
 * {@code mvn test -Pintegration} after the pseudonymised file browser,
 * SelectionBroker, brief TCP commands, clipboard nudge, and embedded-PTY nudge
 * land in stage 09.
 */
@Disabled("TODO stage 09: enable after Browse Files and SelectionBroker land")
@Tag("integration")
@Ignore("TODO stage 09: skeleton only")
public class BrowseBriefIntegrationTest {
    @Test
    public void selectedSeriesBriefCanBeRetrievedAndOpenedByToken() {
        // TODO stage 09: select three series in Browse Files, send a brief,
        // assert browse_pending_brief/get_pending_brief/open_image_by_token,
        // and cover external clipboard plus embedded-PTY nudges.
    }
}
