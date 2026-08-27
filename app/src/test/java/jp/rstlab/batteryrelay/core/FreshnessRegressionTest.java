package jp.rstlab.batteryrelay.core;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import jp.rstlab.batteryrelay.model.BatterySample;

public final class FreshnessRegressionTest {
    @Test
    public void latestUnavailableTemperatureNeverShowsHistoricalRateAsCurrent() {
        long now = 2_000_000_000_000L;
        BatterySample older = new BatterySample(now - 120_000L, 50, 30d,
                Double.NaN, Double.NaN, 4_000, false, 0);
        BatterySample prior = new BatterySample(now - 60_000L, 51, 31d,
                Double.NaN, Double.NaN, 4_000, false, 0);
        BatterySample latest = new BatterySample(now, 52, Double.NaN,
                Double.NaN, Double.NaN, 4_000, false, 0);

        assertTrue(Double.isNaN(TrendMath.ratePerMinute(
                Arrays.asList(older, prior, latest), TrendMath.Metric.TEMPERATURE_C)));
    }
}
