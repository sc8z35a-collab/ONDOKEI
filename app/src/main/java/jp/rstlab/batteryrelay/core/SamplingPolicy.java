package jp.rstlab.batteryrelay.core;

/** Pure interval policy so power/thermal behavior can be tested without Android. */
public final class SamplingPolicy {
    public static final long TURBO_INTERVAL_MILLIS = 5_000L;
    public static final long NORMAL_INTERVAL_MILLIS = 15_000L;
    public static final long BACKGROUND_INTERVAL_MILLIS = 60_000L;
    public static final long NOTIFICATION_INTERVAL_MILLIS = 60_000L;
    public static final long FRESHNESS_TICK_MILLIS = 5_000L;
    public static final int THERMAL_BACKOFF_STATUS = 3; // PowerManager.THERMAL_STATUS_SEVERE

    private SamplingPolicy() {}

    public static long localInterval(boolean turbo, boolean powerSave, int thermalStatus) {
        if (powerSave || thermalStatus >= THERMAL_BACKOFF_STATUS) {
            return BACKGROUND_INTERVAL_MILLIS;
        }
        return turbo ? TURBO_INTERVAL_MILLIS : NORMAL_INTERVAL_MILLIS;
    }

    public static long remoteInterval(boolean active, boolean turbo, boolean uiVisible) {
        if (!uiVisible || !active) return BACKGROUND_INTERVAL_MILLIS;
        return turbo ? TURBO_INTERVAL_MILLIS : NORMAL_INTERVAL_MILLIS;
    }

    public static long clampRemoteInterval(long intervalMillis) {
        return Math.max(TURBO_INTERVAL_MILLIS,
                Math.min(BACKGROUND_INTERVAL_MILLIS, intervalMillis));
    }
}
