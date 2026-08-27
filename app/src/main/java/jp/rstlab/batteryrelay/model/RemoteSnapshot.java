package jp.rstlab.batteryrelay.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jp.rstlab.batteryrelay.core.TrendMath;

public final class RemoteSnapshot {
    private static final int MAX_REMOTE_SAMPLES = 31;
    public final String deviceName;
    public final long generatedAt;
    public final List<BatterySample> samples;
    public final boolean freshRequested;
    public final boolean freshApplied;
    public final long requestSequence;

    public RemoteSnapshot(String deviceName, long generatedAt, List<BatterySample> samples,
                          boolean freshRequested, boolean freshApplied, long requestSequence) {
        this.deviceName = deviceName;
        this.generatedAt = generatedAt;
        this.samples = Collections.unmodifiableList(new ArrayList<>(samples));
        this.freshRequested = freshRequested;
        this.freshApplied = freshApplied;
        this.requestSequence = requestSequence;
    }

    public static RemoteSnapshot fromJson(JSONObject json) throws JSONException {
        return fromJson(json, System.currentTimeMillis());
    }

    public static RemoteSnapshot fromJson(JSONObject json, long receivedAt) throws JSONException {
        JSONArray array = json.getJSONArray("samples");
        if (array.length() > MAX_REMOTE_SAMPLES) {
            throw new JSONException("too_many_samples");
        }
        ArrayList<BatterySample> samples = new ArrayList<>(array.length());
        long hostGeneratedAt = json.getLong("generatedAt");
        for (int i = 0; i < array.length(); i++) {
            BatterySample raw = BatterySample.fromJson(array.getJSONObject(i));
            long age;
            try {
                age = Math.subtractExact(hostGeneratedAt, raw.timestampMillis);
            } catch (ArithmeticException overflow) {
                continue;
            }
            if (age < 0L || age > TrendMath.WINDOW_MILLIS) continue;
            long mappedTime;
            try {
                mappedTime = Math.subtractExact(receivedAt, age);
            } catch (ArithmeticException overflow) {
                continue;
            }
            samples.add(new BatterySample(mappedTime, raw.levelPercent, raw.temperatureC,
                    raw.remainingMah, raw.currentMa, raw.voltageMv, raw.charging,
                    raw.thermalStatus));
        }
        List<BatterySample> retained = TrendMath.coalesceMinuteSamples(samples, receivedAt);
        String device = json.optString("device", "共有端末")
                .replaceAll("[\\r\\n\\t]", " ").trim();
        if (device.isEmpty()) device = "共有端末";
        if (device.codePointCount(0, device.length()) > 48) {
            device = device.substring(0, device.offsetByCodePoints(0, 48));
        }
        return new RemoteSnapshot(device, receivedAt, retained,
                json.optBoolean("freshRequested", false),
                json.optBoolean("freshApplied", false), requirePositiveSequence(json));
    }

    private static long requirePositiveSequence(JSONObject json) throws JSONException {
        long sequence = json.getLong("requestSequence");
        if (sequence < 1L) throw new JSONException("invalid_request_sequence");
        return sequence;
    }
}
