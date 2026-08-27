package jp.rstlab.batteryrelay;

import android.app.Application;
import android.content.Context;

import jp.rstlab.batteryrelay.data.MeasurementRepository;
import jp.rstlab.batteryrelay.share.RemoteDeviceManager;
import jp.rstlab.batteryrelay.share.ShareHost;

public final class BatteryRelayApp extends Application {
    private MeasurementRepository repository;
    private ShareHost shareHost;
    private RemoteDeviceManager remoteDevices;

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new MeasurementRepository(this);
        repository.initialize();
        shareHost = new ShareHost(this, repository);
        remoteDevices = new RemoteDeviceManager(this);
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

    public static BatteryRelayApp from(Context context) {
        return (BatteryRelayApp) context.getApplicationContext();
    }
}
