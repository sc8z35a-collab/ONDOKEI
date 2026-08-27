package jp.rstlab.batteryrelay.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/** Immutable measurement captured from Android's public battery APIs. */
public final class BatterySample {
    public static final double MIN_TEMPERATURE_C = -40d;
    public static final double MAX_TEMPERATURE_C = 90d;
    public static final double MAX_REMAINING_MAH = 30_000d;
    public static final double MAX_ABS_CURRENT_MA = 20_000d;
    public static final int MAX_VOLTAGE_MV = 30_000;
    public final long timestampMillis;
    public final int levelPercent;
    public final double temperatureC;
    public final double remainingMah;
    public final double currentMa;
    public final int voltageMv;
    public final boolean charging;
    public final int thermalStatus;

    public BatterySample(
            long timestampMillis,
            int levelPercent,
            double temperatureC,
            double remainingMah,
            double currentMa,
            int voltageMv,
            boolean charging,
            int thermalStatus
    ) {
        this.timestampMillis = timestampMillis;
        this.levelPercent = levelPercent < 0 ? -1 : Math.min(100, levelPercent);
        this.temperatureC = Double.isFinite(temperatureC)
                && temperatureC >= MIN_TEMPERATURE_C && temperatureC <= MAX_TEMPERATURE_C
                ? temperatureC : Double.NaN;
        this.remainingMah = finiteInRange(remainingMah, 0d, MAX_REMAINING_MAH)
                ? remainingMah : Double.NaN;
        this.currentMa = finiteInRange(currentMa,
                -MAX_ABS_CURRENT_MA, MAX_ABS_CURRENT_MA) ? currentMa : Double.NaN;
        this.voltageMv = voltageMv >= 0 && voltageMv <= MAX_VOLTAGE_MV ? voltageMv : 0;
        this.charging = charging;
        this.thermalStatus = thermalStatus >= 0 && thermalStatus <= 6 ? thermalStatus : -1;
    }

    public boolean hasTemperature() {
        return Double.isFinite(temperatureC);
    }

    public boolean hasRemainingMah() {
        return Double.isFinite(remainingMah) && remainingMah >= 0d;
    }

    public boolean hasCurrent() {
        return Double.isFinite(currentMa);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("t", timestampMillis);
        json.put("battery", levelPercent);
        if (hasTemperature()) json.put("tempC", temperatureC);
        if (hasRemainingMah()) json.put("remainingMah", remainingMah);
        if (hasCurrent()) json.put("currentMa", currentMa);
        if (voltageMv > 0) json.put("voltageMv", voltageMv);
        json.put("charging", charging);
        json.put("thermal", thermalStatus);
        return json;
    }

    public static BatterySample fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new JSONException("sample_missing");
        return new BatterySample(
                json.getLong("t"),
                json.getInt("battery"),
                json.has("tempC") ? json.getDouble("tempC") : Double.NaN,
                json.has("remainingMah") ? json.getDouble("remainingMah") : Double.NaN,
                json.has("currentMa") ? json.getDouble("currentMa") : Double.NaN,
                json.optInt("voltageMv", 0),
                json.optBoolean("charging", false),
                json.optInt("thermal", -1)
        );
    }

    private static boolean finiteInRange(double value, double minInclusive, double maxInclusive) {
        return Double.isFinite(value) && value >= minInclusive && value <= maxInclusive;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BatterySample)) return false;
        BatterySample that = (BatterySample) other;
        return timestampMillis == that.timestampMillis
                && levelPercent == that.levelPercent
                && Double.compare(temperatureC, that.temperatureC) == 0
                && Double.compare(remainingMah, that.remainingMah) == 0
                && Double.compare(currentMa, that.currentMa) == 0
                && voltageMv == that.voltageMv
                && charging == that.charging
                && thermalStatus == that.thermalStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestampMillis, levelPercent, temperatureC, remainingMah,
                currentMa, voltageMv, charging, thermalStatus);
    }
}
