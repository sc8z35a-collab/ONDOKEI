package jp.rstlab.batteryrelay.data;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import jp.rstlab.batteryrelay.model.BatterySample;

/** Reads only documented Android battery/thermal APIs; no root or vendor-only files. */
public final class BatteryReader {
    private final Context context;
    private final BatteryManager batteryManager;
    private final PowerManager powerManager;

    public BatteryReader(Context context) {
        this.context = context.getApplicationContext();
        this.batteryManager = this.context.getSystemService(BatteryManager.class);
        this.powerManager = this.context.getSystemService(PowerManager.class);
    }

    public BatterySample read(long nowMillis) {
        Intent state = null;
        try {
            state = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (RuntimeException ignored) {
            // Some OEM battery services fail transiently; documented properties remain a fallback.
        }

        int level = readPercent(state);
        double temperature = readTemperature(state);
        double remainingMah = readIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 0, 30_000_000) / 1000d;
        double currentMa = readIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -20_000_000, 20_000_000) / 1000d;
        int voltage = state == null ? 0 : state.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        if (voltage < 0 || voltage > BatterySample.MAX_VOLTAGE_MV) voltage = 0;
        int status = state == null ? BatteryManager.BATTERY_STATUS_UNKNOWN
                : state.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int thermal = -1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                thermal = powerManager.getCurrentThermalStatus();
            } catch (RuntimeException ignored) {
                // Thermal service availability differs across OEM builds.
            }
        }

        if (!Double.isFinite(remainingMah) || remainingMah < 0d) remainingMah = Double.NaN;
        if (!Double.isFinite(currentMa)) currentMa = Double.NaN;
        return new BatterySample(nowMillis, level, temperature, remainingMah, currentMa,
                voltage, charging, thermal);
    }

    private int readPercent(Intent state) {
        if (state != null) {
            int level = state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = state.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) return Math.round(level * 100f / scale);
        }
        if (batteryManager != null) {
            int value = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (value != Integer.MIN_VALUE && value >= 0 && value <= 100) return value;
        }
        return -1;
    }

    private static double readTemperature(Intent state) {
        if (state == null || !state.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) return Double.NaN;
        int tenths = state.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (tenths == Integer.MIN_VALUE) return Double.NaN;
        double value = tenths / 10d;
        // Negative values are valid in cold environments. Reject only implausible OEM sentinels.
        return value >= -40d && value < 90d ? value : Double.NaN;
    }

    private double readIntProperty(int property, int minInclusive, int maxInclusive) {
        if (batteryManager == null) return Double.NaN;
        int value;
        try {
            value = batteryManager.getIntProperty(property);
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
        if (value == Integer.MIN_VALUE || value < minInclusive || value > maxInclusive) {
            return Double.NaN;
        }
        return value;
    }
}
