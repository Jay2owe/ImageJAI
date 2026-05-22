package imagejai.config;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrivacyPostureTest {

    @Test
    public void stricterThanFollowsStandardPseudonymisedOnPremisesOrder() {
        assertTrue(PrivacyPosture.PSEUDONYMISED
                .isStricterThan(PrivacyPosture.STANDARD));
        assertTrue(PrivacyPosture.ON_PREMISES
                .isStricterThan(PrivacyPosture.PSEUDONYMISED));
        assertTrue(PrivacyPosture.ON_PREMISES
                .isStricterThan(PrivacyPosture.STANDARD));

        assertFalse(PrivacyPosture.STANDARD
                .isStricterThan(PrivacyPosture.PSEUDONYMISED));
        assertFalse(PrivacyPosture.PSEUDONYMISED
                .isStricterThan(PrivacyPosture.ON_PREMISES));
        assertFalse(PrivacyPosture.STANDARD
                .isStricterThan(PrivacyPosture.STANDARD));
    }
}
