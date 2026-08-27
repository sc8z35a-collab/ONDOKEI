package jp.rstlab.batteryrelay.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Test;

/** Regression coverage for the security and concurrency boundaries used by sharing. */
public final class SecurityConcurrencyRegressionTest {
    @Test
    public void pairingSecretsRemainUniqueUnderConcurrentGeneration() throws Exception {
        int workers = 8;
        int valuesPerWorker = 250;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(pool.submit(new Callable<List<String>>() {
                    @Override public List<String> call() throws Exception {
                        start.await();
                        ArrayList<String> values = new ArrayList<>(valuesPerWorker);
                        for (int i = 0; i < valuesPerWorker; i++) {
                            values.add(CryptoBox.randomPairingSecret());
                        }
                        return values;
                    }
                }));
            }
            start.countDown();
            Set<String> unique = new HashSet<>();
            for (Future<List<String>> future : futures) {
                for (String value : future.get()) {
                    assertTrue(value.matches("[2-9A-HJ-NP-Z]{26}"));
                    assertTrue("pairing secret collision", unique.add(value));
                }
            }
            assertEquals(workers * valuesPerWorker, unique.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void authenticatedCiphertextRejectsTamperReplayContextAndBadLengths()
            throws Exception {
        byte[] key = CryptoBox.randomBytes(CryptoBox.AES_KEY_BYTES);
        byte[] nonce = CryptoBox.randomBytes(CryptoBox.GCM_NONCE_BYTES);
        byte[] aad = CryptoBox.aad("snapshot", "session-7/sequence-41");
        byte[] plain = "bounded battery history".getBytes(StandardCharsets.UTF_8);
        byte[] box = CryptoBox.encrypt(key, nonce, plain, aad);
        assertArrayEquals(plain, CryptoBox.decrypt(key, nonce, box, aad));

        box[box.length / 2] ^= 1;
        assertRejected(key, nonce, box, aad);
        box[box.length / 2] ^= 1;
        assertRejected(key, nonce, box,
                CryptoBox.aad("snapshot", "session-7/sequence-40"));

        boolean badNonceRejected = false;
        try {
            CryptoBox.encrypt(key, new byte[8], plain, aad);
        } catch (IllegalArgumentException expected) {
            badNonceRejected = true;
        }
        assertTrue(badNonceRejected);
    }

    private static void assertRejected(byte[] key, byte[] nonce, byte[] box, byte[] aad)
            throws Exception {
        boolean rejected = false;
        try {
            CryptoBox.decrypt(key, nonce, box, aad);
        } catch (GeneralSecurityException expected) {
            rejected = true;
        }
        assertTrue("modified or context-replayed ciphertext was accepted", rejected);
    }
}
