package jp.rstlab.batteryrelay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.security.KeyPair;
import java.util.Arrays;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import jp.rstlab.batteryrelay.model.BatterySample;
import jp.rstlab.batteryrelay.model.RemoteSnapshot;

/** Pure-JVM regressions added by the second ultra audit. */
public final class UltraAuditV2RegressionTest {
    @Test
    public void exactTemperatureBoundariesRemainRepresentable() {
        BatterySample cold = sample(1L, -40d);
        BatterySample hot = sample(2L, 90d);
        assertTrue(cold.hasTemperature());
        assertTrue(hot.hasTemperature());
        assertEquals(-40d, cold.temperatureC, 0d);
        assertEquals(90d, hot.temperatureC, 0d);
        assertFalse(sample(3L, -40.1d).hasTemperature());
        assertFalse(sample(4L, 90.1d).hasTemperature());
    }

    @Test
    public void remoteMappingCanUseRttMidpointWithoutChangingReceiptTime() throws Exception {
        long hostGeneratedAt = 2_000_000_000_000L;
        long receivedAt = 3_000_000_000_000L;
        long midpoint = receivedAt - 2_500L;
        JSONObject payload = new JSONObject()
                .put("device", "remote")
                .put("generatedAt", hostGeneratedAt)
                .put("freshRequested", true)
                .put("freshApplied", true)
                .put("requestSequence", 9L)
                .put("samples", new JSONArray().put(
                        sample(hostGeneratedAt - 10_000L, 31d).toJson()));

        RemoteSnapshot snapshot = RemoteSnapshot.fromJson(payload, receivedAt, midpoint);

        assertEquals(receivedAt, snapshot.receivedAt);
        assertEquals(midpoint - 10_000L, snapshot.generatedAt);
        assertEquals(9L, snapshot.requestSequence);
        assertTrue(snapshot.freshRequested);
    }

    @Test
    public void exactP256KeyStillRoundTripsAfterStrictParameterCheck() throws Exception {
        KeyPair pair = CryptoBox.generateKeyPair();
        assertEquals(pair.getPublic(), CryptoBox.decodePublicKey(pair.getPublic().getEncoded()));
    }

    @Test
    public void cryptoRejectsUnboundedAndInvalidInputs() throws Exception {
        boolean randomRejected = false;
        try {
            CryptoBox.randomBytes(1024 * 1024 + 1);
        } catch (IllegalArgumentException expected) {
            randomRejected = true;
        }
        assertTrue(randomRejected);

        boolean hkdfRejected = false;
        try {
            CryptoBox.hkdfSha256(new byte[]{1}, new byte[]{2}, null, 255 * 32 + 1);
        } catch (IllegalArgumentException expected) {
            hkdfRejected = true;
        }
        assertTrue(hkdfRejected);

        byte[] key = CryptoBox.randomBytes(CryptoBox.AES_KEY_BYTES);
        byte[] nonce = CryptoBox.randomBytes(CryptoBox.GCM_NONCE_BYTES);
        try {
            boolean nullPlainRejected = false;
            try {
                CryptoBox.encrypt(key, nonce, null, new byte[0]);
            } catch (IllegalArgumentException expected) {
                nullPlainRejected = true;
            }
            assertTrue(nullPlainRejected);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    @Test
    public void protectionIntervalPolicyIsExplicitlySixtySeconds() {
        assertEquals(SamplingPolicy.BACKGROUND_INTERVAL_MILLIS,
                SamplingPolicy.localInterval(true, true, 0));
        assertEquals(SamplingPolicy.BACKGROUND_INTERVAL_MILLIS,
                SamplingPolicy.localInterval(true, false, SamplingPolicy.THERMAL_BACKOFF_STATUS));
    }

    private static BatterySample sample(long time, double temp) {
        return new BatterySample(time, 50, temp, 2500d, -300d, 4100, false, 0);
    }
}
