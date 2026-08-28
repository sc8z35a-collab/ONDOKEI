package jp.rstlab.batteryrelay.service;

/**
 * Small, Android-free state machine for explicit MonitorService shutdown.
 *
 * <p>A duplicate STOP updates the newest startId without starting another flush. A later non-stop
 * command cancels the generation so an already queued completion cannot tear down the restarted
 * foreground service. Once a completion is claimed, a subsequent STOP starts a fresh generation
 * instead of being lost in the short window before onDestroy.</p>
 */
final class ExplicitStopState {
    static final long ALREADY_IN_PROGRESS = -1L;

    private long generation;
    private int latestStartId;
    private boolean inProgress;
    private boolean completed;

    synchronized long begin(int startId) {
        if (startId <= 0) throw new IllegalArgumentException("startId must be positive");
        latestStartId = startId;
        if (inProgress) return ALREADY_IN_PROGRESS;
        inProgress = true;
        completed = false;
        generation++;
        return generation;
    }

    synchronized void cancel() {
        if (!inProgress && !completed) return;
        inProgress = false;
        completed = false;
        generation++;
    }

    synchronized int claimCompletionStartId(long expectedGeneration) {
        if (!inProgress || generation != expectedGeneration) return -1;
        inProgress = false;
        completed = true;
        return latestStartId;
    }

    synchronized boolean ownsShutdown() {
        return inProgress || completed;
    }
}
