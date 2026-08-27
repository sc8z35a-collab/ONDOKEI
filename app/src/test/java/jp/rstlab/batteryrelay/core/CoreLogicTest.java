package jp.rstlab.batteryrelay.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import jp.rstlab.batteryrelay.model.BatterySample;

public final class CoreLogicTest {
    @Test
    public void retentionIsStrictlyThirtyMinutesIncludingBoundary() {
        long now = 2_000_000_000_000L;
        List<BatterySample> samples = new ArrayList<>();
        for (int minutesAgo = 32; minutesAgo >= 0; minutesAgo--) {
            samples.add(sample(now - minutesAgo * 60_000L,
                    45 + (32 - minutesAgo), 30d + (32 - minutesAgo) * 0.25d));
        }

        List<BatterySample> kept = TrendMath.retainWindow(samples, now);

        assertEquals(31, kept.size());
        assertEquals(now - TrendMath.WINDOW_MILLIS, kept.get(0).timestampMillis);
        assertEquals(now, kept.get(kept.size() - 1).timestampMillis);
    }

    @Test
    public void ratesUseElapsedTimeRatherThanAssumingExactSampling() {
        long now = 2_000_000_000_000L;
        List<BatterySample> samples = new ArrayList<>();
        samples.add(sample(now - 90_000L, 50, 30d));
        samples.add(sample(now, 53, 31.5d));

        assertEquals(2d, TrendMath.ratePerMinute(samples,
                TrendMath.Metric.BATTERY_PERCENT), 0.000001d);
        assertEquals(1d, TrendMath.ratePerMinute(samples,
                TrendMath.Metric.TEMPERATURE_C), 0.000001d);
    }

    @Test
    public void unavailableTemperatureIsSkippedForRate() {
        long now = 2_000_000_000_000L;
        List<BatterySample> samples = new ArrayList<>();
        samples.add(sample(now - 120_000L, 50, 30d));
        samples.add(sample(now - 60_000L, 51, Double.NaN));
        samples.add(sample(now, 52, 32d));

        assertEquals(1d, TrendMath.ratePerMinute(samples,
                TrendMath.Metric.TEMPERATURE_C), 0.000001d);
    }

    @Test
    public void pairKeyAgreesAndWrongCodeCannotDecrypt() throws Exception {
        KeyPair host = CryptoBox.generateKeyPair();
        KeyPair client = CryptoBox.generateKeyPair();
        byte[] salt = CryptoBox.randomBytes(16);
        byte[] hostKey = CryptoBox.derivePairKey(host.getPrivate(), client.getPublic(),
                salt, "123456", "share-id");
        byte[] clientKey = CryptoBox.derivePairKey(client.getPrivate(), host.getPublic(),
                salt, "123456", "share-id");
        assertArrayEquals(hostKey, clientKey);

        byte[] nonce = CryptoBox.randomBytes(CryptoBox.GCM_NONCE_BYTES);
        byte[] aad = CryptoBox.aad("unit", "share-id");
        byte[] plain = "battery history".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = CryptoBox.encrypt(hostKey, nonce, plain, aad);
        assertArrayEquals(plain, CryptoBox.decrypt(clientKey, nonce, cipher, aad));

        byte[] wrongKey = CryptoBox.derivePairKey(client.getPrivate(), host.getPublic(),
                salt, "999999", "share-id");
        boolean rejected = false;
        try {
            CryptoBox.decrypt(wrongKey, nonce, cipher, aad);
        } catch (GeneralSecurityException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    public void liveSampleReplacesOnlyItsMinuteBucket() {
        long now = 2_000_000_000_000L;
        List<BatterySample> samples = new ArrayList<>();
        samples.add(sample(now - 60_000L, 49, 30d));
        samples.add(sample(now - 8_000L, 50, 30.5d));

        List<BatterySample> merged = TrendMath.upsertMinuteSample(
                samples, sample(now, 51, 31d), now);

        assertEquals(2, merged.size());
        assertEquals(49, merged.get(0).levelPercent);
        assertEquals(51, merged.get(1).levelPercent);
    }

    @Test
    public void samplingPolicyProtectsBatteryAndThermals() {
        assertEquals(5_000L, SamplingPolicy.localInterval(true, false, 0));
        assertEquals(15_000L, SamplingPolicy.localInterval(false, false, 0));
        assertEquals(60_000L, SamplingPolicy.localInterval(true, true, 0));
        assertEquals(60_000L, SamplingPolicy.localInterval(true, false, 3));
        assertEquals(60_000L, SamplingPolicy.remoteInterval(false, true, true));
        assertEquals(5_000L, SamplingPolicy.remoteInterval(true, true, true));
    }

    @Test
    public void remotePollingBacksOffWhenHiddenOrNotSelected() {
        assertEquals(60_000L, SamplingPolicy.remoteInterval(true, true, false));
        assertEquals(60_000L, SamplingPolicy.remoteInterval(false, true, true));
        assertEquals(15_000L, SamplingPolicy.remoteInterval(true, false, true));
        assertEquals(5_000L, SamplingPolicy.clampRemoteInterval(250L));
        assertEquals(60_000L, SamplingPolicy.clampRemoteInterval(600_000L));
    }

    @Test
    public void liveUpsertEnforcesWindowAndPointCap() {
        long now = 2_000_000_000_000L;
        List<BatterySample> samples = new ArrayList<>();
        for (int minutesAgo = 40; minutesAgo >= 1; minutesAgo--) {
            samples.add(sample(now - minutesAgo * 60_000L, 50, 30d));
        }

        List<BatterySample> merged = TrendMath.upsertMinuteSample(
                samples, sample(now, 51, 31d), now);

        assertEquals(31, merged.size());
        assertEquals(now - TrendMath.WINDOW_MILLIS, merged.get(0).timestampMillis);
        assertEquals(now, merged.get(30).timestampMillis);
    }

    @Test
    public void futureSamplesAndUnavailableBatteryAreNotTreatedAsRealData() {
        long now = 2_000_000_000_000L;
        List<BatterySample> samples = new ArrayList<>();
        samples.add(sample(now - 60_000L, 50, -5d));
        samples.add(sample(now, -1, -4d));
        samples.add(sample(now + 1L, 99, 80d));
        List<BatterySample> kept = TrendMath.retainWindow(samples, now);
        assertEquals(2, kept.size());
        assertTrue(Double.isNaN(TrendMath.ratePerMinute(kept,
                TrendMath.Metric.BATTERY_PERCENT)));
        assertEquals(1d, TrendMath.ratePerMinute(kept,
                TrendMath.Metric.TEMPERATURE_C), 0.000001d);
    }

    @Test
    public void ratesAreCorrectEvenWhenInputIsUnsorted() {
        long now = 2_000_000_000_000L;
        List<BatterySample> samples = Arrays.asList(
                sample(now, 54, 32d),
                sample(now - 120_000L, 50, 30d),
                sample(now - 60_000L, 53, 31d));

        assertEquals(1d, TrendMath.ratePerMinute(samples,
                TrendMath.Metric.BATTERY_PERCENT), 0.000001d);
        assertEquals(1d, TrendMath.ratePerMinute(samples,
                TrendMath.Metric.TEMPERATURE_C), 0.000001d);
    }

    @Test
    public void hostileNumericValuesAreSanitizedAtTheModelBoundary() {
        BatterySample hostile = new BatterySample(1L, 500, Double.MAX_VALUE,
                1e300d, -1e300d, Integer.MAX_VALUE, false, Integer.MAX_VALUE);
        assertEquals(100, hostile.levelPercent);
        assertTrue(Double.isNaN(hostile.temperatureC));
        assertTrue(Double.isNaN(hostile.remainingMah));
        assertTrue(Double.isNaN(hostile.currentMa));
        assertEquals(0, hostile.voltageMv);
        assertEquals(-1, hostile.thermalStatus);
    }

    @Test
    public void retentionHandlesLongUnderflowWithoutAcceptingFuturePoints() {
        List<BatterySample> samples = Arrays.asList(
                sample(Long.MIN_VALUE, 1, 1d),
                sample(Long.MIN_VALUE + 1L, 2, 2d));
        List<BatterySample> kept = TrendMath.retainWindow(samples, Long.MIN_VALUE);
        assertEquals(1, kept.size());
        assertEquals(Long.MIN_VALUE, kept.get(0).timestampMillis);
    }

    @Test
    public void nonP256PublicKeysAreRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        boolean rejected = false;
        try {
            CryptoBox.decodePublicKey(generator.generateKeyPair().getPublic().getEncoded());
        } catch (GeneralSecurityException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    public void remoteMinuteDuplicatesAreCoalescedAndCapped() {
        long now = 2_000_000_000_000L;
        ArrayList<BatterySample> samples = new ArrayList<>();
        for (int minute = 35; minute >= 0; minute--) {
            long base = now - minute * 60_000L;
            samples.add(sample(base - 1_000L, 40, 30d));
            samples.add(sample(base, 41, 31d));
        }
        List<BatterySample> result = TrendMath.coalesceMinuteSamples(samples, now);
        assertEquals(31, result.size());
        for (int i = 1; i < result.size(); i++) {
            assertTrue(Math.floorDiv(result.get(i - 1).timestampMillis, 60_000L)
                    < Math.floorDiv(result.get(i).timestampMillis, 60_000L));
        }
    }

    private static BatterySample sample(long time, int battery, double temp) {
        return new BatterySample(time, battery, temp, Double.NaN, Double.NaN,
                4000, false, 0);
    }
}
