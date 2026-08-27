package jp.rstlab.batteryrelay.share;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class SessionLifecycleRegressionTest {
    @Test
    public void leasedKeyIsNotMutatedWhenSessionIsDestroyed() {
        byte[] original = new byte[32];
        Arrays.fill(original, (byte) 0x5a);
        ShareHost.ViewerSession session = new ShareHost.ViewerSession(original, 1_000L);

        byte[] leased = session.leaseKey();
        assertArrayEquals(original, leased);

        session.destroy();

        // stop(), expiry cleanup or logout may destroy the internal key while a worker is already
        // decrypting/encrypting. The in-flight lease must remain stable and independent.
        assertArrayEquals(original, leased);
        assertNull(session.leaseKey());
        assertFalse(session.isSequenceFresh(1L));
        assertFalse(session.acceptSequence(1L, 1_001L, true));
        assertFalse(session.canFresh(1_001L, 5_000L));
        assertFalse(session.commitFresh(1_001L, 5_000L));

        Arrays.fill(leased, (byte) 0);
    }

    @Test
    public void activeSessionStillEnforcesSequenceAndFreshness() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x33);
        ShareHost.ViewerSession session = new ShareHost.ViewerSession(key, 10_000L);

        assertTrue(session.isSequenceFresh(1L));
        assertTrue(session.acceptSequence(1L, 10_001L, true));
        assertFalse(session.isSequenceFresh(1L));
        assertTrue(session.isSequenceFresh(2L));

        assertTrue(session.canFresh(10_001L, 5_000L));
        assertTrue(session.commitFresh(10_001L, 5_000L));
        assertFalse(session.canFresh(12_000L, 5_000L));
        assertTrue(session.canFresh(15_001L, 5_000L));

        session.destroy();
    }
}
