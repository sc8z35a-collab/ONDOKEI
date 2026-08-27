package jp.rstlab.batteryrelay.service;

/**
 * Small, Android-free state machine for explicit MonitorService shutdown.
 *
 * <p>A duplicate STOP updates the newest startId without starting another flush. A later non-stop
 * command cancels the generation so an already queued completion cannot tear down the restarted
 * foreground service.</p>
 */
final class ExplicitStopState {
    static final long ALREADY_IN_PROGRESS = -1L;

    private long generation;
    private int latestStartId;
    private boolean inProgress;

    synchronized long begin(int startId) {
        if (startId <= 0) throw new IllegalArgumentException("startId must be positive");
        latestStartId = startId;
        if (inProgress) return ALREADY_IN_PROGRESS;
        inProgress = true;
        generation++;
        return generation;
    }

    synchronized void cancel() {
        if (!inProgress) return;
        inProgress = false;
        generation++;
    }

    synchronized int completionStartId(long expectedGeneration) {
        return inProgress && generation == expectedGeneration ? latestStartId : -1;
    }

    synchronized boolean isInProgress() {
        return inProgress;
    }
}
