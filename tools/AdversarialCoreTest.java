package jp.rstlab.batteryrelay.core;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import jp.rstlab.batteryrelay.model.BatterySample;

/** Deterministic property/fuzz checks for hostile boundaries that ordinary examples miss. */
public final class AdversarialCoreTest {
    private static final long SEED = 0x42525452454c4159L;

    public static void main(String[] args) throws Exception {
        fuzzRetentionAndRates();
        attackAuthenticatedEncryption();
        stressPairingSecrets();
        System.out.println("AdversarialCoreTest: 100000 windows, 2000 AEAD attacks, "
                + "50000 secrets passed");
    }

    private static void fuzzRetentionAndRates() {
        Random random = new Random(SEED);
        for (int round = 0; round < 100_000; round++) {
            long now = random.nextLong();
            ArrayList<BatterySample> input = new ArrayList<>();
            int count = random.nextInt(80);
            for (int i = 0; i < count; i++) {
                long timestamp = random.nextBoolean() ? random.nextLong()
                        : saturatedAdd(now, random.nextInt(7_200_001) - 3_600_000L);
                input.add(new BatterySample(timestamp, random.nextInt(240) - 80,
                        random.nextBoolean() ? random.nextDouble() * 200d - 80d : Double.NaN,
                        random.nextDouble() * 100_000d - 10_000d,
                        random.nextDouble() * 100_000d - 50_000d,
                        random.nextInt(), random.nextBoolean(), random.nextInt()));
            }

            List<BatterySample> kept = TrendMath.retainWindow(input, now);
            long cutoff = now < Long.MIN_VALUE + TrendMath.WINDOW_MILLIS
                    ? Long.MIN_VALUE : now - TrendMath.WINDOW_MILLIS;
            long previous = Long.MIN_VALUE;
            for (BatterySample sample : kept) {
                require(sample.timestampMillis >= cutoff && sample.timestampMillis <= now,
                        "retention escaped window");
                require(sample.timestampMillis >= previous, "retention is not sorted");
                previous = sample.timestampMillis;
            }

            List<BatterySample> coalesced = TrendMath.coalesceMinuteSamples(input, now);
            require(coalesced.size() <= 31, "point cap exceeded");
            Set<Long> buckets = new HashSet<>();
            for (BatterySample sample : coalesced) {
                require(buckets.add(Math.floorDiv(sample.timestampMillis, 60_000L)),
                        "duplicate minute bucket");
            }
            double batteryRate = TrendMath.ratePerMinute(kept,
                    TrendMath.Metric.BATTERY_PERCENT);
            double temperatureRate = TrendMath.ratePerMinute(kept,
                    TrendMath.Metric.TEMPERATURE_C);
            require(Double.isNaN(batteryRate) || Double.isFinite(batteryRate),
                    "non-finite battery rate");
            require(Double.isNaN(temperatureRate) || Double.isFinite(temperatureRate),
                    "non-finite temperature rate");
        }
    }

    private static void attackAuthenticatedEncryption() throws Exception {
        KeyPair host = CryptoBox.generateKeyPair();
        KeyPair client = CryptoBox.generateKeyPair();
        byte[] salt = CryptoBox.randomBytes(16);
        byte[] hostKey = CryptoBox.derivePairKey(host.getPrivate(), client.getPublic(), salt,
                "23456789ABCDEFGHJKLMNPQRST", "attack-test");
        byte[] clientKey = CryptoBox.derivePairKey(client.getPrivate(), host.getPublic(), salt,
                "23456789ABCDEFGHJKLMNPQRST", "attack-test");
        Random random = new Random(SEED ^ 0x5a5a5a5aL);
        for (int round = 0; round < 1_000; round++) {
            byte[] message = new byte[random.nextInt(4097)];
            random.nextBytes(message);
            byte[] nonce = CryptoBox.randomBytes(CryptoBox.GCM_NONCE_BYTES);
            byte[] aad = CryptoBox.aad("snapshot-response", "session/" + round);
            byte[] box = CryptoBox.encrypt(hostKey, nonce, message, aad);
            require(java.security.MessageDigest.isEqual(message,
                    CryptoBox.decrypt(clientKey, nonce, box, aad)), "AEAD round trip failed");

            box[random.nextInt(box.length)] ^= 1;
            requireRejected(clientKey, nonce, box, aad, "tampered ciphertext accepted");
            box[random.nextInt(box.length)] ^= 1;
            requireRejected(clientKey, nonce, box,
                    CryptoBox.aad("snapshot-response", "wrong/" + round),
                    "wrong AAD accepted");
        }
        java.util.Arrays.fill(hostKey, (byte) 0);
        java.util.Arrays.fill(clientKey, (byte) 0);
    }

    private static void stressPairingSecrets() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            String secret = CryptoBox.randomPairingSecret();
            require(secret.matches("[2-9A-HJ-NP-Z]{26}"), "invalid secret alphabet/length");
            require(values.add(secret), "pairing secret collision");
        }
    }

    private static void requireRejected(byte[] key, byte[] nonce, byte[] box, byte[] aad,
                                        String message) throws Exception {
        boolean rejected = false;
        try {
            CryptoBox.decrypt(key, nonce, box, aad);
        } catch (GeneralSecurityException expected) {
            rejected = true;
        }
        require(rejected, message);
    }

    private static long saturatedAdd(long value, long delta) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException overflow) {
            return delta < 0L ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
