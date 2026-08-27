package jp.rstlab.batteryrelay.share;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.rstlab.batteryrelay.core.SamplingPolicy;
import jp.rstlab.batteryrelay.model.RemoteSnapshot;

/** Keeps encrypted remote sessions alive while the user switches between device tabs. */
public final class RemoteDeviceManager {
    public static final int MAX_CONNECTIONS = 8;

    public interface Listener {
        void onDevicesChanged(List<Device> devices);
        void onDeviceMessage(String key, String message, boolean terminal);
    }

    public static final class Device {
        public final String key;
        public final String serviceName;
        public final String displayName;
        public final boolean connected;
        public final RemoteSnapshot snapshot;

        private Device(String key, String serviceName, String displayName,
                       boolean connected, RemoteSnapshot snapshot) {
            this.key = key;
            this.serviceName = serviceName;
            this.displayName = displayName;
            this.connected = connected;
            this.snapshot = snapshot;
        }
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private Listener listener;
    private String activeKey;
    private boolean turbo;
    private boolean uiVisible;
    private NsdBrowser recoveryBrowser;
    private final Runnable stopRecovery = () -> {
        synchronized (RemoteDeviceManager.this) {
            stopRecoveryLocked();
        }
    };

    public RemoteDeviceManager(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        this.context = context.getApplicationContext();
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
        publishLocked();
    }

    public synchronized void connect(DiscoveredPeer peer, String code, boolean turboMode) {
        if (peer == null) {
            postMessageLocked("", "接続先が見つかりません。端末を再検索してください", true);
            return;
        }
        String key = peer.stableKey();
        Connection existing = connections.get(key);
        turbo = turboMode;
        if (existing != null) {
            existing.peer = peer;
            existing.client.updatePeer(peer);
            activeKey = key;
            updateIntervalsLocked();
            existing.client.requestRefresh();
            publishLocked();
            return;
        }
        if (connections.size() >= MAX_CONNECTIONS) {
            postMessageLocked(key, "接続端末は最大8台です", true);
            return;
        }

        activeKey = key;
        Connection connection = new Connection(key, peer.serviceName, peer);
        RemoteClient client = new RemoteClient(context, new RemoteClient.Listener() {
            @Override public void onPairingSucceeded(String deviceName) {
                synchronized (RemoteDeviceManager.this) {
                    Connection current = connections.get(key);
                    if (current == null || current.client != connection.client) return;
                    current.connected = true;
                    publishLocked();
                    postMessageLocked(key, "接続しました", false);
                }
            }

            @Override public void onSnapshot(RemoteSnapshot snapshot) {
                if (snapshot == null) return;
                synchronized (RemoteDeviceManager.this) {
                    Connection current = connections.get(key);
                    if (current == null || current.client != connection.client) return;
                    current.connected = true;
                    current.snapshot = snapshot;
                    current.displayName = snapshot.deviceName;
                    publishLocked();
                }
            }

            @Override public void onConnectionError(String message, boolean terminal) {
                synchronized (RemoteDeviceManager.this) {
                    Connection current = connections.get(key);
                    if (current == null || current.client != connection.client) return;
                    current.connected = false;
                    if (terminal) {
                        connections.remove(key, current);
                        if (key.equals(activeKey)) activeKey = null;
                        current.client.disconnect();
                    } else {
                        startRecoveryDiscoveryLocked();
                    }
                    updateIntervalsLocked();
                    publishLocked();
                    postMessageLocked(key, message, terminal);
                }
            }
        });
        connection.client = client;
        connections.put(key, connection);
        updateIntervalsLocked();
        publishLocked();
        client.pairAndStart(peer, code);
    }

    public synchronized void setActive(String key, boolean turboMode) {
        turbo = turboMode;
        activeKey = key != null && connections.containsKey(key) ? key : null;
        updateIntervalsLocked();
        publishLocked();
    }

    public synchronized void setUiVisible(boolean visible) {
        uiVisible = visible;
        updateIntervalsLocked();
    }

    public synchronized void setTurbo(boolean turboMode) {
        turbo = turboMode;
        updateIntervalsLocked();
    }

    public synchronized boolean isTurbo() {
        return turbo;
    }

    public synchronized void refresh(String key) {
        if (key == null) return;
        Connection connection = connections.get(key);
        if (connection != null) connection.client.requestRefresh();
    }

    /** Refreshes mutable NSD endpoints without dropping an authenticated session. */
    public synchronized void updateDiscoveredPeers(List<DiscoveredPeer> peers) {
        if (peers == null || peers.isEmpty()) return;
        boolean updated = false;
        for (DiscoveredPeer peer : peers) {
            if (peer == null) continue;
            Connection connection = connections.get(peer.stableKey());
            if (connection != null) {
                connection.peer = peer;
                connection.client.updatePeer(peer);
                updated = true;
            }
        }
        if (updated && recoveryBrowser != null) stopRecoveryLocked();
    }

    public synchronized void disconnect(String key) {
        if (key == null) return;
        Connection connection = connections.remove(key);
        if (connection == null) return;
        connection.client.disconnect();
        if (key.equals(activeKey)) activeKey = null;
        updateIntervalsLocked();
        publishLocked();
    }

    /** Explicit global stop; ordinary tab switches intentionally never call this. */
    public synchronized void disconnectAll() {
        stopRecoveryLocked();
        if (connections.isEmpty()) {
            activeKey = null;
            return;
        }
        ArrayList<Connection> closing = new ArrayList<>(connections.values());
        connections.clear();
        activeKey = null;
        for (Connection connection : closing) connection.client.disconnect();
        publishLocked();
    }

    public synchronized boolean contains(String key) {
        return key != null && connections.containsKey(key);
    }

    public synchronized Device get(String key) {
        if (key == null) return null;
        Connection connection = connections.get(key);
        return connection == null ? null : connection.copy();
    }

    public synchronized String getActiveKey() {
        return activeKey;
    }

    public synchronized List<Device> devices() {
        return immutableCopyLocked();
    }

    private void updateIntervalsLocked() {
        for (Connection connection : connections.values()) {
            boolean active = connection.key.equals(activeKey);
            connection.client.setPollIntervalMillis(
                    SamplingPolicy.remoteInterval(active, turbo, uiVisible));
        }
    }

    private void startRecoveryDiscoveryLocked() {
        if (recoveryBrowser != null || connections.isEmpty()) return;
        NsdBrowser browser = new NsdBrowser(context, new NsdBrowser.Listener() {
            @Override public void onPeersChanged(List<DiscoveredPeer> peers) {
                updateDiscoveredPeers(peers);
            }

            @Override public void onDiscoveryError(String message) {
                synchronized (RemoteDeviceManager.this) {
                    // Allow the next transport failure to retry discovery immediately rather than
                    // being blocked by a dead browser until the 15-second timer fires.
                    if (recoveryBrowser != null) stopRecoveryLocked();
                }
            }
        });
        recoveryBrowser = browser;
        browser.start();
        mainHandler.removeCallbacks(stopRecovery);
        mainHandler.postDelayed(stopRecovery, 15_000L);
    }

    private void stopRecoveryLocked() {
        mainHandler.removeCallbacks(stopRecovery);
        NsdBrowser browser = recoveryBrowser;
        recoveryBrowser = null;
        if (browser != null) browser.stop();
    }

    private void publishLocked() {
        Listener target = listener;
        if (target == null) return;
        List<Device> snapshot = immutableCopyLocked();
        mainHandler.post(() -> {
            synchronized (RemoteDeviceManager.this) {
                if (listener != target) return;
            }
            target.onDevicesChanged(snapshot);
        });
    }

    private void postMessageLocked(String key, String message, boolean terminal) {
        Listener target = listener;
        if (target == null) return;
        String safeKey = key == null ? "" : key;
        String safeMessage = message == null ? "接続状態が変化しました" : message;
        mainHandler.post(() -> {
            synchronized (RemoteDeviceManager.this) {
                if (listener != target) return;
            }
            target.onDeviceMessage(safeKey, safeMessage, terminal);
        });
    }

    private List<Device> immutableCopyLocked() {
        ArrayList<Device> copy = new ArrayList<>(connections.size());
        for (Connection connection : connections.values()) copy.add(connection.copy());
        return Collections.unmodifiableList(copy);
    }

    private static final class Connection {
        final String key;
        final String serviceName;
        String displayName;
        boolean connected;
        RemoteSnapshot snapshot;
        RemoteClient client;
        DiscoveredPeer peer;

        Connection(String key, String serviceName, DiscoveredPeer peer) {
            this.key = key;
            this.serviceName = serviceName;
            this.displayName = serviceName;
            this.peer = peer;
        }

        Device copy() {
            return new Device(key, serviceName, displayName, connected, snapshot);
        }
    }
}
