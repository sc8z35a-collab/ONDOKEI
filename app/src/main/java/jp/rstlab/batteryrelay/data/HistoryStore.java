package jp.rstlab.batteryrelay.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import jp.rstlab.batteryrelay.core.TrendMath;
import jp.rstlab.batteryrelay.model.BatterySample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded one-row-per-minute ring. Visibility is a 30-minute wall-clock window. */
public final class HistoryStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "battery_relay.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "minute_samples";
    private static final int MAX_ROWS = 31;

    public HistoryStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "minute_bucket INTEGER PRIMARY KEY,"
                + "captured_at INTEGER NOT NULL,"
                + "battery_percent INTEGER NOT NULL,"
                + "temperature_c REAL,"
                + "remaining_mah REAL,"
                + "current_ma REAL,"
                + "voltage_mv INTEGER NOT NULL,"
                + "charging INTEGER NOT NULL,"
                + "thermal_status INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_samples_captured_at ON " + TABLE + "(captured_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == newVersion) return;
        // Never silently destroy measurement history. A future schema bump must add an explicit
        // migration here; failing fast during development is safer than shipping DROP TABLE.
        throw new IllegalStateException(
                "Missing battery history migration from " + oldVersion + " to " + newVersion);
    }

    public synchronized void putAndPrune(BatterySample sample, long nowMillis) {
        putAllAndPrune(Collections.singletonList(sample), nowMillis);
    }

    /** Persists a bounded in-memory backlog in one transaction after transient DB failures. */
    public synchronized void putAllAndPrune(List<BatterySample> samples, long nowMillis) {
        if (samples == null || samples.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (BatterySample sample : samples) {
                if (sample == null) continue;
                ContentValues values = new ContentValues();
                values.put("minute_bucket", Math.floorDiv(sample.timestampMillis, 60_000L));
                values.put("captured_at", sample.timestampMillis);
                values.put("battery_percent", sample.levelPercent);
                putFinite(values, "temperature_c", sample.temperatureC);
                putFinite(values, "remaining_mah", sample.remainingMah);
                putFinite(values, "current_ma", sample.currentMa);
                values.put("voltage_mv", sample.voltageMv);
                values.put("charging", sample.charging ? 1 : 0);
                values.put("thermal_status", sample.thermalStatus);
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            compactInTransaction(db, nowMillis);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Returns only the current wall-clock 30-minute window without mutating storage.
     *
     * Physical retention is enforced by a 31-row ring on writes instead of deleting by
     * {@code now-30m}. This prevents a temporary forward clock jump from irreversibly deleting
     * otherwise valid history. Future-looking rows after a rollback stay hidden until time catches
     * up, while the database remains bounded.
     */
    public synchronized List<BatterySample> readWindow(long nowMillis) {
        SQLiteDatabase db = getReadableDatabase();
        ArrayList<BatterySample> newestFirst = new ArrayList<>(MAX_ROWS);
        long cutoff = safeCutoff(nowMillis);
        try (Cursor cursor = db.query(TABLE, null, "captured_at>=? AND captured_at<=?",
                new String[]{Long.toString(cutoff), Long.toString(nowMillis)},
                null, null, "captured_at DESC", Integer.toString(MAX_ROWS))) {
            int captured = cursor.getColumnIndexOrThrow("captured_at");
            int battery = cursor.getColumnIndexOrThrow("battery_percent");
            int temp = cursor.getColumnIndexOrThrow("temperature_c");
            int remaining = cursor.getColumnIndexOrThrow("remaining_mah");
            int current = cursor.getColumnIndexOrThrow("current_ma");
            int voltage = cursor.getColumnIndexOrThrow("voltage_mv");
            int charging = cursor.getColumnIndexOrThrow("charging");
            int thermal = cursor.getColumnIndexOrThrow("thermal_status");
            while (cursor.moveToNext()) {
                newestFirst.add(new BatterySample(
                        cursor.getLong(captured),
                        cursor.getInt(battery),
                        nullableDouble(cursor, temp),
                        nullableDouble(cursor, remaining),
                        nullableDouble(cursor, current),
                        cursor.getInt(voltage),
                        cursor.getInt(charging) != 0,
                        cursor.getInt(thermal)
                ));
            }
        }
        Collections.reverse(newestFirst);
        return Collections.unmodifiableList(newestFirst);
    }

    public synchronized int countAllForDiagnostics() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static void compactInTransaction(SQLiteDatabase db, long nowMillis) {
        String now = Long.toString(nowMillis);
        // Prefer rows that are visible at the caller's current wall clock, newest first. Rows that
        // temporarily look like the future are retained next, oldest first, so a rollback does not
        // immediately destroy them. The hard cap prevents clock anomalies from growing the DB.
        db.delete(TABLE, "minute_bucket NOT IN (SELECT minute_bucket FROM " + TABLE
                + " ORDER BY CASE WHEN captured_at<=? THEN 0 ELSE 1 END ASC,"
                + " CASE WHEN captured_at<=? THEN captured_at END DESC,"
                + " CASE WHEN captured_at>? THEN captured_at END ASC LIMIT " + MAX_ROWS + ")",
                new String[]{now, now, now});
    }

    private static long safeCutoff(long nowMillis) {
        return nowMillis < Long.MIN_VALUE + TrendMath.WINDOW_MILLIS
                ? Long.MIN_VALUE : nowMillis - TrendMath.WINDOW_MILLIS;
    }

    private static void putFinite(ContentValues values, String key, double value) {
        if (Double.isFinite(value)) values.put(key, value);
        else values.putNull(key);
    }

    private static double nullableDouble(Cursor cursor, int index) {
        return cursor.isNull(index) ? Double.NaN : cursor.getDouble(index);
    }
}
