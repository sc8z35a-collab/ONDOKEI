package jp.rstlab.batteryrelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jp.rstlab.batteryrelay.data.HistoryStore;
import jp.rstlab.batteryrelay.model.BatterySample;

/** Reproduces concurrent writer/pruner pressure against the real SQLite implementation. */
@RunWith(AndroidJUnit4.class)
public final class HistoryStoreConcurrencyTest {
    private static final String DATABASE = "battery_relay.db";
    private Context context;
    private HistoryStore store;

    @Before
    public void createIsolatedDatabase() {
        context = InstrumentationRegistry.getInstrumentation().getContext();
        context.deleteDatabase(DATABASE);
        store = new HistoryStore(context);
    }

    @After
    public void removeIsolatedDatabase() {
        if (store != null) store.close();
        if (context != null) context.deleteDatabase(DATABASE);
    }

    @Test
    public void concurrentWritesCoalesceMinutesAndNeverEscapeRetentionWindow()
            throws Exception {
        long now = 2_000_000_000_000L;
        int workers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                final int writer = worker;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int minute = 45; minute >= 0; minute--) {
                        long timestamp = now - minute * 60_000L
                                + (minute == 0 ? -writer : writer);
                        store.putAndPrune(sample(timestamp, 40 + writer), now);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }

        List<BatterySample> kept = store.readWindow(now);
        assertEquals(31, kept.size());
        assertTrue(store.countAllForDiagnostics() <= 31);
        long previous = Long.MIN_VALUE;
        for (BatterySample sample : kept) {
            assertTrue(sample.timestampMillis >= now - 30L * 60_000L);
            assertTrue(sample.timestampMillis <= now);
            assertTrue(sample.timestampMillis >= previous);
            previous = sample.timestampMillis;
        }
    }

    private static BatterySample sample(long timestamp, int battery) {
        return new BatterySample(timestamp, battery, 31.5d, 2_500d,
                -300d, 4_100, false, 0);
    }
}
