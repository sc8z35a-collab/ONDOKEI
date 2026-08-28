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
        assertTrue(state.ownsShutdown());

        assertEquals(ExplicitStopState.ALREADY_IN_PROGRESS, state.begin(8));
        assertEquals(8, state.claimCompletionStartId(firstGeneration));
        assertTrue(state.ownsShutdown());
        assertEquals(-1, state.claimCompletionStartId(firstGeneration));
    }

    @Test
    public void restartCancelsQueuedOrCompletedStopCompletion() {
        ExplicitStopState state = new ExplicitStopState();

        long stoppedGeneration = state.begin(11);
        state.cancel();

        assertFalse(state.ownsShutdown());
        assertEquals(-1, state.claimCompletionStartId(stoppedGeneration));

        long completedGeneration = state.begin(12);
        assertEquals(12, state.claimCompletionStartId(completedGeneration));
        assertTrue(state.ownsShutdown());
        state.cancel();
        assertFalse(state.ownsShutdown());
    }

    @Test
    public void stopAfterCompletionStartsFreshGeneration() {
        ExplicitStopState state = new ExplicitStopState();

        long firstGeneration = state.begin(21);
        assertEquals(21, state.claimCompletionStartId(firstGeneration));

        long secondGeneration = state.begin(22);
        assertNotEquals(ExplicitStopState.ALREADY_IN_PROGRESS, secondGeneration);
        assertNotEquals(firstGeneration, secondGeneration);
        assertEquals(22, state.claimCompletionStartId(secondGeneration));
    }
}
