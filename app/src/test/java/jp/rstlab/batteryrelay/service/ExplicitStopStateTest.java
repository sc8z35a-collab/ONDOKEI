package jp.rstlab.batteryrelay.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExplicitStopStateTest {
    @Test
    public void duplicateStopUsesNewestStartIdWithoutStartingSecondFlush() {
        ExplicitStopState state = new ExplicitStopState();

        long firstGeneration = state.begin(7);
        assertNotEquals(ExplicitStopState.ALREADY_IN_PROGRESS, firstGeneration);
        assertTrue(state.isInProgress());

        assertEquals(ExplicitStopState.ALREADY_IN_PROGRESS, state.begin(8));
        assertEquals(8, state.completionStartId(firstGeneration));
    }

    @Test
    public void restartCancelsQueuedStopCompletion() {
        ExplicitStopState state = new ExplicitStopState();

        long stoppedGeneration = state.begin(11);
        state.cancel();

        assertFalse(state.isInProgress());
        assertEquals(-1, state.completionStartId(stoppedGeneration));

        long nextGeneration = state.begin(12);
        assertNotEquals(stoppedGeneration, nextGeneration);
        assertEquals(12, state.completionStartId(nextGeneration));
    }
}
