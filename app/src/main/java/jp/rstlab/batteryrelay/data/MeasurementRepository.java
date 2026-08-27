package jp.rstlab.batteryrelay.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import jp.rstlab.batteryrelay.core.TrendMath;
import jp.rstlab.batteryrelay.model.BatterySample;

import java.util.Collections;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArraySet;

/** Thread-safe source of the current local 30-minute window. */
public final class MeasurementRepository {
    public interface Listener {
        void onMeasurementsChanged(List<BatterySample> samples);
    }

    private final BatteryReader reader;
    private final HistoryStore store;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile List<BatterySample> cached = Collections.emptyList();
    private long lastPersistedMinute = Long.MIN_VALUE;
    private BatterySample liveSample;
    private BatterySample pendingPersistentSample;
    private final ArrayDeque<BatterySample> persistenceBacklog = new ArrayDeque<>(31);
    private volatile boolean samplingActive;
    private long lastSampleElapsed = Long.MIN_VALUE;
    private final AtomicLong sampleRevision = new AtomicLong();

    public MeasurementRepository(Context context) {
        reader = new BatteryReader(context);
        store = new HistoryStore(context);
    }

    /** Loads persisted state away from the Android main thread. */
    public void initializeAsync(Executor executor) {
        if (executor == null) return;
        executor.execute(this::initialize);
    }

    public synchronized void initialize() {
        long now = System.currentTimeMillis();
        try {
            cached = mergeTransientState(store.readWindow(now), now);
            if (liveSample != null) {
                lastPersistedMinute = Math.floorDiv(liveSample.timestampMillis, 60_000L);
            } else if (!cached.isEmpty()) {
                lastPersistedMinute = Math.floorDiv(
                        cached.get(cached.size() - 1).timestampMillis, 60_000L);
            }
        } catch (RuntimeException databaseFailure) {
            // Never overwrite live/in-memory backlog because storage is temporarily unavailable.
            cached = mergeTransientState(cached, now);
        }
        notifyListeners(cached);
    }

    public synchronized BatterySample sampleNow() {
        long now = System.currentTimeMillis();
        BatterySample sample = reader.read(now);
        lastSampleElapsed = SystemClock.elapsedRealtime();
        long minute = Math.floorDiv(sample.timestampMillis, 60_000L);

        if (minute != lastPersistedMinute) {
            // Checkpoint the first observation of the new minute in the same transaction that
            // finalizes the previous minute. A process kill can therefore lose at most the later
            // refinements of the current minute, not the entire minute.
            if (pendingPersistentSample != null) enqueueForPersistence(pendingPersistentSample);
            enqueueForPersistence(sample);
            flushBacklog(now);
            lastPersistedMinute = minute;
        }

        pendingPersistentSample = sample;
        liveSample = sample;
        cached = TrendMath.upsertMinuteSample(cached, sample, now);
        sampleRevision.incrementAndGet();
        notifyListeners(cached);
        return sample;
    }

    /** Coalesces authenticated remote refreshes with a recent service measurement. */
    public synchronized BatterySample sampleNowIfOlderThan(long minimumAgeMillis) {
        long elapsed = SystemClock.elapsedRealtime();
        if (lastSampleElapsed != Long.MIN_VALUE
                && elapsed >= lastSampleElapsed
                && elapsed - lastSampleElapsed < Math.max(0L, minimumAgeMillis)) {
            return latest();
        }
        return sampleNow();
    }

    public synchronized void pruneNow() {
        long now = System.currentTimeMillis();
        try {
            cached = mergeTransientState(store.readWindow(now), now);
        } catch (RuntimeException ignored) {
            cached = mergeTransientState(cached, now);
        }
        notifyListeners(cached);
    }

    public List<BatterySample> snapshot() {
        return cached;
    }

    public BatterySample latest() {
        List<BatterySample> copy = cached;
        return copy.isEmpty() ? null : copy.get(copy.size() - 1);
    }

    public boolean isSamplingActive() {
        return samplingActive;
    }

    public long sampleRevision() {
        return sampleRevision.get();
    }

    /** Flushes the latest minute on an explicit service stop without changing the live cache. */
    public synchronized void flushPending() {
        if (pendingPersistentSample != null) enqueueForPersistence(pendingPersistentSample);
        if (flushBacklog(System.currentTimeMillis())) pendingPersistentSample = null;
    }

    public void setSamplingActive(boolean active) {
        samplingActive = active;
        notifyListeners(cached);
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.add(listener);
        List<BatterySample> copy = cached;
        mainHandler.post(() -> {
            if (listeners.contains(listener)) listener.onMeasurementsChanged(copy);
        });
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(List<BatterySample> copy) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onMeasurementsChanged(copy);
        });
    }

    private List<BatterySample> mergeTransientState(List<BatterySample> base, long now) {
        ArrayList<BatterySample> merged = new ArrayList<>();
        if (base != null) merged.addAll(base);
        merged.addAll(persistenceBacklog);
        if (liveSample != null) merged.add(liveSample);
        return TrendMath.coalesceMinuteSamples(merged, now);
    }

    private void enqueueForPersistence(BatterySample sample) {
        if (sample == null) return;
        long bucket = Math.floorDiv(sample.timestampMillis, 60_000L);
        persistenceBacklog.removeIf(existing ->
                Math.floorDiv(existing.timestampMillis, 60_000L) == bucket);
        persistenceBacklog.addLast(sample);
        while (persistenceBacklog.size() > 31) persistenceBacklog.removeFirst();
    }

    private boolean flushBacklog(long now) {
        if (persistenceBacklog.isEmpty()) return true;
        ArrayList<BatterySample> batch = new ArrayList<>(persistenceBacklog);
        try {
            store.putAllAndPrune(batch, now);
            persistenceBacklog.clear();
            return true;
        } catch (RuntimeException ignored) {
            // Retain the bounded backlog in memory and retry at the next minute boundary/stop.
            return false;
        }
    }
}
