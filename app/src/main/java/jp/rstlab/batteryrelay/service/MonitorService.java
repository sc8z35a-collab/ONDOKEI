package jp.rstlab.batteryrelay.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.util.Locale;

import jp.rstlab.batteryrelay.BatteryRelayApp;
import jp.rstlab.batteryrelay.MainActivity;
import jp.rstlab.batteryrelay.R;
import jp.rstlab.batteryrelay.core.SamplingPolicy;
import jp.rstlab.batteryrelay.data.MeasurementRepository;
import jp.rstlab.batteryrelay.model.BatterySample;

/** User-visible continuous sampler. One database row is retained per minute for only 30 minutes. */
public final class MonitorService extends Service {
    public static final String ACTION_START = "jp.rstlab.batteryrelay.action.START";
    public static final String ACTION_STOP = "jp.rstlab.batteryrelay.action.STOP";
    public static final String ACTION_REFRESH = "jp.rstlab.batteryrelay.action.REFRESH";
    public static final String ACTION_SET_TURBO = "jp.rstlab.batteryrelay.action.SET_TURBO";
    public static final String EXTRA_TURBO = "turbo";
    private static final String CHANNEL_ID = "battery_monitor";
    private static final int NOTIFICATION_ID = 3011;
    private static final long WAKE_LOCK_TIMEOUT_MILLIS = 10L * 60L * 1000L;
    private static final long WAKE_LOCK_RENEW_MILLIS = 5L * 60L * 1000L;

    private HandlerThread workerThread;
    private Handler worker;
    private MeasurementRepository repository;
    private NotificationManager notificationManager;
    private PowerManager powerManager;
    private PowerManager.WakeLock samplingWakeLock;
    private volatile boolean sampling;
    private volatile boolean turbo;
    private boolean foregroundStarted;
    private volatile int lastThermalStatus = -1;
    private long lastNotificationAt;
    private volatile long samplingGeneration;

    private final Runnable wakeLockRenewal = new Runnable() {
        @Override public void run() {
            if (!sampling) return;
            renewSamplingWakeLock();
            if (sampling && worker != null) worker.postDelayed(this, WAKE_LOCK_RENEW_MILLIS);
        }
    };

    private final Runnable sampleTask = new Runnable() {
        @Override public void run() {
            if (!sampling) return;
            long generation = samplingGeneration;
            try {
                BatterySample sample = repository.sampleNow();
                lastThermalStatus = sample.thermalStatus;
                if (sampling && generation == samplingGeneration) maybeUpdateNotification(sample);
            } catch (RuntimeException ignored) {
                // A later sample can recover from a transient OEM battery-service failure.
            } finally {
                if (sampling && generation == samplingGeneration) {
                    worker.removeCallbacks(this);
                    worker.postDelayed(this, effectiveIntervalMillis());
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        repository = BatteryRelayApp.from(this).repository();
        notificationManager = getSystemService(NotificationManager.class);
        powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            samplingWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "BatteryRelay:ContinuousSampling");
            samplingWakeLock.setReferenceCounted(false);
        }
        turbo = false;
        createNotificationChannel();
        workerThread = new HandlerThread("battery-relay-sampler", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            BatteryRelayApp app = BatteryRelayApp.from(this);
            app.shareHost().stop();
            app.remoteDevices().disconnectAll();
            stopSampling();
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SET_TURBO.equals(intent.getAction())) {
            turbo = intent.getBooleanExtra(EXTRA_TURBO, false);
        }

        if (!foregroundStarted) {
            Notification initial = notification(repository.latest());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initial,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, initial);
            }
            foregroundStarted = true;
            lastNotificationAt = SystemClock.elapsedRealtime();
        } else if (intent != null && ACTION_SET_TURBO.equals(intent.getAction())
                && notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification(repository.latest()));
            lastNotificationAt = SystemClock.elapsedRealtime();
        }
        if (!sampling) {
            sampling = true;
            samplingGeneration++;
            acquireSamplingWakeLock();
            repository.setSamplingActive(true);
            worker.removeCallbacks(sampleTask);
            worker.post(sampleTask);
        } else if (intent != null && (ACTION_REFRESH.equals(intent.getAction())
                || ACTION_SET_TURBO.equals(intent.getAction()))) {
            // User actions bypass the timer but remain serialized on the one background thread.
            worker.removeCallbacks(sampleTask);
            worker.post(sampleTask);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        BatteryRelayApp app = BatteryRelayApp.from(this);
        app.shareHost().stop();
        app.remoteDevices().disconnectAll();
        stopSampling();
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopSampling() {
        sampling = false;
        samplingGeneration++;
        if (repository != null) {
            repository.setSamplingActive(false);
            BatteryRelayApp.from(this).executeIo(repository::flushPending);
        }
        if (worker != null) worker.removeCallbacksAndMessages(null);
        releaseSamplingWakeLock();
    }

    private void acquireSamplingWakeLock() {
        if (worker != null) worker.removeCallbacks(wakeLockRenewal);
        renewSamplingWakeLock();
        if (sampling && worker != null) {
            worker.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_MILLIS);
        }
    }

    private void renewSamplingWakeLock() {
        PowerManager.WakeLock lock = samplingWakeLock;
        if (lock == null) return;
        try {
            // Refresh the framework timeout before it can expire. The tiny release/acquire window
            // is on the already-awake sampler thread and prevents an unbounded lock on bad paths.
            if (lock.isHeld()) lock.release();
            if (sampling) lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS);
        } catch (RuntimeException ignored) {
            // Sampling can still proceed while awake even on an OEM that rejects the lock.
        }
    }

    private void releaseSamplingWakeLock() {
        if (worker != null) worker.removeCallbacks(wakeLockRenewal);
        PowerManager.WakeLock lock = samplingWakeLock;
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (RuntimeException ignored) {
            // Process death also releases kernel wakelocks; never crash during teardown.
        }
    }

    private void createNotificationChannel() {
        if (notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private long effectiveIntervalMillis() {
        boolean powerSave = false;
        try {
            powerSave = powerManager != null && powerManager.isPowerSaveMode();
        } catch (RuntimeException ignored) {
            // A vendor service failure must not terminate the sampling worker.
        }
        // Turbo belongs to the selected source. Keep the local sampler at Normal while a remote
        // device is selected, then automatically resume local Turbo when the local tab is active.
        boolean localTurbo = turbo
                && BatteryRelayApp.from(this).remoteDevices().getActiveKey() == null;
        return SamplingPolicy.localInterval(localTurbo, powerSave, lastThermalStatus);
    }

    private void maybeUpdateNotification(BatterySample sample) {
        if (notificationManager == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationAt < SamplingPolicy.NOTIFICATION_INTERVAL_MILLIS) return;
        lastNotificationAt = now;
        notificationManager.notify(NOTIFICATION_ID, notification(sample));
    }

    private Notification notification(BatterySample sample) {
        String content;
        if (sample == null) {
            content = "初回データを取得しています";
        } else if (sample.levelPercent < 0 && sample.hasTemperature()) {
            content = String.format(Locale.JAPAN, "残量取得不可 ・ %.1f℃ ・ 30分のみ保存%s",
                    sample.temperatureC,
                    BatteryRelayApp.from(this).shareHost().isRunning() ? " ・ 共有中" : "");
        } else if (sample.levelPercent < 0) {
            content = "残量・温度取得不可 ・ 30分のみ保存";
        } else if (sample.hasTemperature()) {
            content = String.format(Locale.JAPAN, "残量 %d%% ・ %.1f℃ ・ 30分のみ保存%s",
                    sample.levelPercent, sample.temperatureC,
                    BatteryRelayApp.from(this).shareHost().isRunning() ? " ・ 共有中" : "");
        } else {
            content = String.format(Locale.JAPAN, "残量 %d%% ・ 温度取得不可 ・ 30分のみ保存%s",
                    sample.levelPercent,
                    BatteryRelayApp.from(this).shareHost().isRunning() ? " ・ 共有中" : "");
        }
        long interval = effectiveIntervalMillis();
        String mode = interval == SamplingPolicy.BACKGROUND_INTERVAL_MILLIS
                ? "保護60秒" : interval == SamplingPolicy.TURBO_INTERVAL_MILLIS
                ? "Turbo 5秒" : "標準15秒";
        content = content + " ・ " + mode;

        PendingIntent open = PendingIntent.getActivity(this, 11,
                new Intent(this, MainActivity.class), pendingFlags());
        PendingIntent stop = PendingIntent.getService(this, 12,
                new Intent(this, MonitorService.class).setAction(ACTION_STOP), pendingFlags());

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_monitor)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_monitor),
                        getString(R.string.notification_stop), stop).build())
                .build();
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
