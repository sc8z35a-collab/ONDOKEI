package jp.rstlab.batteryrelay.core;

import jp.rstlab.batteryrelay.model.BatterySample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;

/** Pure trend/retention logic, kept independent from Android for deterministic tests. */
public final class TrendMath {
    public static final long WINDOW_MILLIS = 30L * 60L * 1000L;
    public static final long MIN_RATE_INTERVAL_MILLIS = 30_000L;

    public enum Metric { BATTERY_PERCENT, TEMPERATURE_C }

    private TrendMath() {}

    public static List<BatterySample> retainWindow(List<BatterySample> samples, long nowMillis) {
        if (samples == null || samples.isEmpty()) return Collections.emptyList();
        long cutoff = nowMillis < Long.MIN_VALUE + WINDOW_MILLIS
                ? Long.MIN_VALUE : nowMillis - WINDOW_MILLIS;
        ArrayList<BatterySample> kept = new ArrayList<>();
        for (BatterySample sample : samples) {
            if (sample != null && sample.timestampMillis >= cutoff && sample.timestampMillis <= nowMillis) {
                kept.add(sample);
            }
        }
        kept.sort((a, b) -> Long.compare(a.timestampMillis, b.timestampMillis));
        return Collections.unmodifiableList(kept);
    }

    /** Replaces the live point in its minute bucket without touching persistent storage. */
    public static List<BatterySample> upsertMinuteSample(
            List<BatterySample> samples, BatterySample live, long nowMillis) {
        if (live == null) return retainWindow(samples, nowMillis);
        ArrayList<BatterySample> merged = new ArrayList<>();
        long liveBucket = Math.floorDiv(live.timestampMillis, 60_000L);
        if (samples != null) {
            for (BatterySample sample : samples) {
                if (sample != null
                        && Math.floorDiv(sample.timestampMillis, 60_000L) != liveBucket) {
                    merged.add(sample);
                }
            }
        }
        merged.add(live);
        List<BatterySample> retained = retainWindow(merged, nowMillis);
        if (retained.size() <= 31) return retained;
        return Collections.unmodifiableList(new ArrayList<>(
                retained.subList(retained.size() - 31, retained.size())));
    }

    /** Keeps the latest value in each minute bucket and enforces the protocol's 31-point cap. */
    public static List<BatterySample> coalesceMinuteSamples(
            List<BatterySample> samples, long nowMillis) {
        List<BatterySample> retained = retainWindow(samples, nowMillis);
        LinkedHashMap<Long, BatterySample> byMinute = new LinkedHashMap<>();
        for (BatterySample sample : retained) {
            byMinute.put(Math.floorDiv(sample.timestampMillis, 60_000L), sample);
        }
        ArrayList<BatterySample> result = new ArrayList<>(byMinute.values());
        if (result.size() > 31) {
            result = new ArrayList<>(result.subList(result.size() - 31, result.size()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns change per minute from the newest sample and the newest usable older sample.
     * If the newest sample cannot provide this metric, NaN is returned rather than surfacing a
     * stale historical rate next to a current "取得不可" value.
     */
    public static double ratePerMinute(List<BatterySample> samples, Metric metric) {
        if (samples == null || samples.size() < 2) return Double.NaN;
        BatterySample newest = null;
        for (BatterySample candidate : samples) {
            if (candidate != null && (newest == null
                    || candidate.timestampMillis > newest.timestampMillis)) {
                newest = candidate;
            }
        }
        if (newest == null) return Double.NaN;
        double newestValue = value(newest, metric);
        if (!Double.isFinite(newestValue)) return Double.NaN;

        BatterySample bestOlder = null;
        double bestOlderValue = Double.NaN;
        for (BatterySample candidate : samples) {
            if (candidate == null || candidate == newest) continue;
            long elapsed;
            try {
                elapsed = Math.subtractExact(newest.timestampMillis, candidate.timestampMillis);
            } catch (ArithmeticException overflow) {
                continue;
            }
            double olderValue = value(candidate, metric);
            if (elapsed >= MIN_RATE_INTERVAL_MILLIS && Double.isFinite(olderValue)) {
                if (bestOlder == null || candidate.timestampMillis > bestOlder.timestampMillis) {
                    bestOlder = candidate;
                    bestOlderValue = olderValue;
                }
            }
        }
        if (bestOlder == null) return Double.NaN;
        long elapsed = newest.timestampMillis - bestOlder.timestampMillis;
        return (newestValue - bestOlderValue) / (elapsed / 60_000d);
    }

    public static String signedRate(double value, String unit) {
        if (!Double.isFinite(value)) return "計測待ち";
        double normalized = Math.abs(value) < 0.05d ? 0d : value;
        return String.format(java.util.Locale.JAPAN, "%+.1f%s/分", normalized, unit);
    }

    private static double value(BatterySample sample, Metric metric) {
        if (sample == null) return Double.NaN;
        if (metric == Metric.BATTERY_PERCENT) {
            return sample.levelPercent >= 0 ? sample.levelPercent : Double.NaN;
        }
        return sample.temperatureC;
    }
}
