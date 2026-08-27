package jp.rstlab.batteryrelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import jp.rstlab.batteryrelay.data.HistoryStore;
import jp.rstlab.batteryrelay.model.BatterySample;
import jp.rstlab.batteryrelay.model.RemoteSnapshot;

@RunWith(AndroidJUnit4.class)
public final class FreshnessAndClockRegressionTest {
    @Test
    public void remoteFreshnessUsesNewestMeasurementNotResponseReceiptTime() throws Exception {
        long hostGeneratedAt = 2_000_000_000_000L;
        long receivedAt = 3_000_000_000_000L;
        BatterySample stale = sample(hostGeneratedAt - 10L * 60_000L, 55, 31.5d);
        JSONObject payload = new JSONObject()
                .put("device", "remote")
                .put("generatedAt", hostGeneratedAt)
                .put("freshRequested", false)
                .put("freshApplied", false)
                .put("requestSequence", 7L)
                .put("samples", new JSONArray().put(stale.toJson()));

        RemoteSnapshot snapshot = RemoteSnapshot.fromJson(payload, receivedAt);

        assertEquals(receivedAt, snapshot.receivedAt);
        assertEquals(receivedAt - 10L * 60_000L, snapshot.generatedAt);
        assertEquals(1, snapshot.samples.size());
        assertEquals(snapshot.generatedAt, snapshot.samples.get(0).timestampMillis);
    }

    @Test
    public void clockRollbackDoesNotPhysicallyDeleteFutureLookingValidRows() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String database = "battery_relay.db";
        context.deleteDatabase(database);
        HistoryStore store = new HistoryStore(context);
        try {
            long originalNow = 2_000_000_000_000L;
            store.putAndPrune(sample(originalNow, 60, 32d), originalNow);
            assertEquals(1, store.countAllForDiagnostics());

            long rolledBackNow = originalNow - 10L * 60_000L;
            List<BatterySample> hiddenDuringRollback = store.readWindow(rolledBackNow);
            assertTrue(hiddenDuringRollback.isEmpty());
            assertEquals(1, store.countAllForDiagnostics());

            List<BatterySample> visibleAgain = store.readWindow(originalNow);
            assertEquals(1, visibleAgain.size());
            assertEquals(originalNow, visibleAgain.get(0).timestampMillis);
        } finally {
            store.close();
            context.deleteDatabase(database);
        }
    }

    private static BatterySample sample(long timestamp, int battery, double temperature) {
        return new BatterySample(timestamp, battery, temperature, 2_500d,
                -300d, 4_100, false, 0);
    }
}
