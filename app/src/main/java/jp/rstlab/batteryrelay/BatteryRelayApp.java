package jp.rstlab.batteryrelay;

import android.app.Application;
import android.content.Context;
import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import jp.rstlab.batteryrelay.data.MeasurementRepository;
import jp.rstlab.batteryrelay.share.RemoteDeviceManager;
import jp.rstlab.batteryrelay.share.ShareHost;

public final class BatteryRelayApp extends Application {
    private final ExecutorService appIo = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "battery-relay-app-io");
        thread.setDaemon(true);
        return thread;
    });

    private MeasurementRepository repository;
    private ShareHost shareHost;
    private RemoteDeviceManager remoteDevices;

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new MeasurementRepository(this);
        shareHost = new ShareHost(this, repository);
        remoteDevices = new RemoteDeviceManager(this);
        repository.initializeAsync(appIo);
    }

    public MeasurementRepository repository() {
        return repository;
    }

    public ShareHost shareHost() {
        return shareHost;
    }

    public RemoteDeviceManager remoteDevices() {
        return remoteDevices;
    }

    /** Executes bounded database/file work without ever blocking an Android main thread. */
    public void executeIo(Runnable task) {
        if (task == null) return;
        try {
            appIo.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Process teardown: losing a final best-effort flush is safer than blocking main.
        }
    }

    @Override
    public void onTerminate() {
        appIo.shutdownNow();
        super.onTerminate();
    }

    public static BatteryRelayApp from(Context context) {
        return (BatteryRelayApp) context.getApplicationContext();
    }
}
