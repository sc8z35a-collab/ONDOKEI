package jp.rstlab.batteryrelay.core;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import jp.rstlab.batteryrelay.model.BatterySample;

public final class CoreSelfTest {
    public static void main(String[] args) throws Exception {
        testRetentionAndRates();
        testLiveWindowAndSamplingPolicy();
        testAuthenticatedPairingCrypto();
        testFutureAndUnavailableValuesAreRejected();
        System.out.println("CoreSelfTest: all checks passed");
    }

    private static void testLiveWindowAndSamplingPolicy() {
        long now = 2_000_000_000_000L;
        List<BatterySample> samples = new ArrayList<>();
        samples.add(new BatterySample(now - 60_000L, 49, 30d,
                Double.NaN, Double.NaN, 4000, false, 0));
        samples.add(new BatterySample(now - 5_000L, 50, 30.5d,
                Double.NaN, Double.NaN, 4000, false, 0));
        BatterySample live = new BatterySample(now, 51, 31d,
                Double.NaN, Double.NaN, 4000, false, 0);
        List<BatterySample> merged = TrendMath.upsertMinuteSample(samples, live, now);
        require(merged.size() == 2 && merged.get(1).levelPercent == 51,
                "live point must replace its minute bucket");
        require(SamplingPolicy.localInterval(true, false, 0) == 5_000L,
                "Turbo must sample every five seconds");
        require(SamplingPolicy.localInterval(true, true, 0) == 60_000L,
                "battery saver must force protection interval");
        require(SamplingPolicy.remoteInterval(false, true, true) == 60_000L,
                "unselected remotes must back off");
    }

    private static void testRetentionAndRates() {
        long now = 2_000_000_000_000L;
        List<BatterySample> samples = new ArrayList<>();
        for (int minutesAgo = 31; minutesAgo >= 0; minutesAgo--) {
            samples.add(new BatterySample(now - minutesAgo * 60_000L,
                    50 + (31 - minutesAgo),
                    30d + (31 - minutesAgo) * 0.5d,
                    Double.NaN, Double.NaN, 4000, false, 0));
        }
        List<BatterySample> retained = TrendMath.retainWindow(samples, now);
        require(retained.size() == 31, "window must retain exactly minute 0..30");
        require(retained.get(0).timestampMillis == now - TrendMath.WINDOW_MILLIS,
                "30-minute boundary must be retained");
        requireClose(TrendMath.ratePerMinute(retained, TrendMath.Metric.BATTERY_PERCENT), 1d);
        requireClose(TrendMath.ratePerMinute(retained, TrendMath.Metric.TEMPERATURE_C), 0.5d);
    }

    private static void testAuthenticatedPairingCrypto() throws Exception {
        KeyPair host = CryptoBox.generateKeyPair();
        KeyPair client = CryptoBox.generateKeyPair();
        byte[] salt = CryptoBox.randomBytes(16);
        byte[] hostKey = CryptoBox.derivePairKey(host.getPrivate(), client.getPublic(), salt,
                "ABCDEFGHJKLMNPQRSTUVWXYZ23", "share-1");
        byte[] clientKey = CryptoBox.derivePairKey(client.getPrivate(), host.getPublic(), salt,
                "ABCDEFGHJKLMNPQRSTUVWXYZ23", "share-1");
        require(java.security.MessageDigest.isEqual(hostKey, clientKey), "ECDH keys must agree");

        byte[] nonce = CryptoBox.randomBytes(CryptoBox.GCM_NONCE_BYTES);
        byte[] message = "battery=80,temp=35.5".getBytes(StandardCharsets.UTF_8);
        byte[] aad = CryptoBox.aad("test", "share-1");
        byte[] encrypted = CryptoBox.encrypt(hostKey, nonce, message, aad);
        byte[] decrypted = CryptoBox.decrypt(clientKey, nonce, encrypted, aad);
        require(java.security.MessageDigest.isEqual(message, decrypted), "AES-GCM round trip");

        byte[] wrong = CryptoBox.derivePairKey(client.getPrivate(), host.getPublic(), salt,
                "Z23456789ABCDEFGHJKLMNPQRS", "share-1");
        boolean rejected = false;
        try {
            CryptoBox.decrypt(wrong, nonce, encrypted, aad);
        } catch (java.security.GeneralSecurityException expected) {
            rejected = true;
        }
        require(rejected, "wrong 128-bit sharing secret must fail authentication");
        String secret = CryptoBox.randomPairingSecret();
        require(secret.matches("[2-9A-HJ-NP-Z]{26}"), "sharing secret format");
    }

    private static void testFutureAndUnavailableValuesAreRejected() {
        long now = 2_000_000_000_000L;
        List<BatterySample> samples = new ArrayList<>();
        samples.add(new BatterySample(now - 60_000L, 50, -5d,
                Double.NaN, Double.NaN, 4000, false, 0));
        samples.add(new BatterySample(now, -1, -4d,
                Double.NaN, Double.NaN, 4000, false, 0));
        samples.add(new BatterySample(now + 1L, 99, 80d,
                Double.NaN, Double.NaN, 4000, false, 0));
        List<BatterySample> retained = TrendMath.retainWindow(samples, now);
        require(retained.size() == 2, "future points must be rejected");
        require(Double.isNaN(TrendMath.ratePerMinute(retained,
                TrendMath.Metric.BATTERY_PERCENT)), "unavailable battery value must be skipped");
        requireClose(TrendMath.ratePerMinute(retained, TrendMath.Metric.TEMPERATURE_C), 1d);
    }

    private static void requireClose(double actual, double expected) {
        require(Math.abs(actual - expected) < 0.000001d,
                "expected " + expected + " but was " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
