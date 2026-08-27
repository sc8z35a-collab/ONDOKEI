package jp.rstlab.batteryrelay.share;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DNS-SD discovery with serialized resolve calls for older Android implementations. */
public final class NsdBrowser {
    public static final String SERVICE_TYPE = "_batteryrelay._tcp.";
    private static final int MAX_DISCOVERY_RECORDS = 32;
    private static final int MAX_LOST_TOMBSTONES = 64;
    private static final long LOST_TOMBSTONE_MILLIS = 60_000L;

    public interface Listener {
        void onPeersChanged(List<DiscoveredPeer> peers);
        void onDiscoveryError(String message);
    }

    private final NsdManager nsdManager;
    private final WifiManager wifiManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Map<String, DiscoveredPeer> peers = new HashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private final Set<String> queuedNames = new HashSet<>();
    private final Map<String, Long> lostNames = new HashMap<>();
    private final Map<String, Integer> resolveFailures = new HashMap<>();
    private final Map<String, Runnable> retryTasks = new HashMap<>();
    private final Map<String, NsdManager.ServiceInfoCallback> serviceCallbacks = new HashMap<>();
    private WifiManager.MulticastLock multicastLock;
    private boolean running;
    private boolean resolving;
    private long generation;
    private NsdManager.DiscoveryListener discoveryListener;

    public NsdBrowser(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        this.nsdManager = app.getSystemService(NsdManager.class);
        this.wifiManager = app.getSystemService(WifiManager.class);
        this.listener = listener;
    }

    private NsdManager.DiscoveryListener createDiscoveryListener(long runGeneration) {
        return new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String serviceType) {}

        @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
            synchronized (NsdBrowser.this) {
                if (!isCurrent(runGeneration) || !isOurType(serviceInfo.getServiceType())) return;
                String name = serviceInfo.getServiceName();
                pruneLostLocked();
                lostNames.remove(name);
                cancelRetryLocked(name);
                if (!canTrackNameLocked(name)) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    trackServiceLocked(serviceInfo, runGeneration);
                    return;
                }
                if (queuedNames.add(name)) {
                    resolveQueue.add(serviceInfo);
                    resolveNextLocked(runGeneration);
                }
            }
        }

        @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
            synchronized (NsdBrowser.this) {
                if (!isCurrent(runGeneration)) return;
                String name = serviceInfo.getServiceName();
                markLostLocked(name);
                cancelRetryLocked(name);
                stopTrackingServiceLocked(name);
                resolveQueue.removeIf(info -> info.getServiceName().equals(name));
                queuedNames.remove(name);
                resolveFailures.remove(name);
                peers.entrySet().removeIf(entry -> entry.getValue().serviceName.equals(name));
                publishLocked(runGeneration);
            }
        }

        @Override public void onDiscoveryStopped(String serviceType) {
            synchronized (NsdBrowser.this) {
                // An explicit stop advances the generation first. Reaching this branch therefore
                // means Android stopped discovery unexpectedly and the UI must not stay spinning.
                if (!isCurrent(runGeneration)) return;
                running = false;
                generation++;
                discoveryListener = null;
                clearPendingLocked();
                stopAllServiceTrackingLocked();
                releaseLock();
                fail("端末検索が中断されました。画面を開き直して再試行してください");
            }
        }

        @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            if (!isCurrentThreadSafe(runGeneration)) return;
            fail("端末検索を開始できませんでした (" + errorCode + ")");
            stop();
        }

        @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            if (isCurrentThreadSafe(runGeneration)) {
                fail("端末検索の停止に失敗しました (" + errorCode + ")");
            }
        }
        };
    }

    public synchronized void start() {
        if (running) return;
        if (nsdManager == null) {
            fail("この端末はネットワーク探索に対応していません");
            return;
        }
        running = true;
        long runGeneration = ++generation;
        peers.clear();
        clearPendingLocked();
        NsdManager.DiscoveryListener runListener = createDiscoveryListener(runGeneration);
        discoveryListener = runListener;
        try {
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("BatteryRelayDiscovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, runListener);
        } catch (RuntimeException error) {
            running = false;
            generation++;
            discoveryListener = null;
            clearPendingLocked();
            releaseLock();
            fail("端末検索を開始できません: " + safeMessage(error));
        }
    }

    public synchronized void stop() {
        if (!running) {
            clearPendingLocked();
            return;
        }
        running = false;
        generation++;
        NsdManager.DiscoveryListener stoppingListener = discoveryListener;
        discoveryListener = null;
        try {
            if (stoppingListener != null) nsdManager.stopServiceDiscovery(stoppingListener);
        } catch (RuntimeException ignored) {
            // Listener can already have been stopped by the framework.
        }
        clearPendingLocked();
        stopAllServiceTrackingLocked();
        releaseLock();
    }

    private void resolveNextLocked(long runGeneration) {
        if (!isCurrent(runGeneration) || resolving || resolveQueue.isEmpty()) return;
        NsdServiceInfo service = resolveQueue.removeFirst();
        String name = service.getServiceName();
        if (isLostLocked(name)) {
            queuedNames.remove(name);
            resolveNextLocked(runGeneration);
            return;
        }
        resolving = true;
        try {
            nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    scheduleResolveRetry(info, runGeneration);
                }

                @Override public void onServiceResolved(NsdServiceInfo info) {
                    synchronized (NsdBrowser.this) {
                        if (!isCurrent(runGeneration)) return;
                        try {
                            if (isLostLocked(info.getServiceName())) {
                                finishResolveLocked(info.getServiceName(), runGeneration);
                                return;
                            }
                            Map<String, byte[]> attrs = info.getAttributes();
                            DiscoveredPeer peer = DiscoveredPeer.fromTxt(
                                    info.getServiceName(), info.getHost(), info.getPort(),
                                    attrs.get("sid"), attrs.get("salt"), attrs.get("pub"));
                            peers.entrySet().removeIf(entry -> entry.getValue().serviceName
                                    .equals(peer.serviceName));
                            peers.put(peer.stableKey(), peer);
                            resolveFailures.remove(info.getServiceName());
                            cancelRetryLocked(info.getServiceName());
                            publishLocked(runGeneration);
                        } catch (Exception ignored) {
                            // Ignore unrelated or malformed mDNS records.
                        }
                        finishResolveLocked(info.getServiceName(), runGeneration);
                    }
                }
            });
        } catch (RuntimeException ignored) {
            scheduleResolveRetryLocked(service, runGeneration);
        }
    }

    // Every caller is guarded by SDK_INT >= 34. Lint models the modular NSD APIs as
    // extension-only as well, so keep the suppression scoped to this guarded branch.
    @SuppressLint("NewApi")
    private void trackServiceLocked(NsdServiceInfo service, long runGeneration) {
        String name = service.getServiceName();
        if (!isCurrent(runGeneration) || serviceCallbacks.containsKey(name)
                || !canTrackNameLocked(name)) return;
        NsdManager.ServiceInfoCallback callback = new NsdManager.ServiceInfoCallback() {
            @Override public void onServiceInfoCallbackRegistrationFailed(int errorCode) {
                synchronized (NsdBrowser.this) {
                    if (!isCurrent(runGeneration)) return;
                    serviceCallbacks.remove(name, this);
                    // A few OEM implementations fail the modern tracker intermittently.
                    if (!isLostLocked(name) && canTrackNameLocked(name) && queuedNames.add(name)) {
                        resolveQueue.addLast(service);
                        resolveNextLocked(runGeneration);
                    }
                }
            }

            @Override public void onServiceInfoCallbackUnregistered() {
                synchronized (NsdBrowser.this) {
                    serviceCallbacks.remove(name, this);
                }
            }

            @Override public void onServiceLost() {
                synchronized (NsdBrowser.this) {
                    if (!isCurrent(runGeneration)) return;
                    serviceCallbacks.remove(name, this);
                    markLostLocked(name);
                    cancelRetryLocked(name);
                    resolveFailures.remove(name);
                    peers.entrySet().removeIf(entry -> entry.getValue().serviceName.equals(name));
                    publishLocked(runGeneration);
                }
            }

            @Override public void onServiceUpdated(NsdServiceInfo info) {
                synchronized (NsdBrowser.this) {
                    if (!isCurrent(runGeneration) || isLostLocked(name)) return;
                    try {
                        Map<String, byte[]> attrs = info.getAttributes();
                        DiscoveredPeer peer = DiscoveredPeer.fromTxt(name,
                                preferredHost(info), info.getPort(), attrs.get("sid"),
                                attrs.get("salt"), attrs.get("pub"), info.getNetwork());
                        peers.entrySet().removeIf(entry -> entry.getValue().serviceName.equals(name));
                        peers.put(peer.stableKey(), peer);
                        publishLocked(runGeneration);
                    } catch (Exception ignored) {
                        // Wait for a subsequent complete service update.
                    }
                }
            }
        };
        serviceCallbacks.put(name, callback);
        try {
            nsdManager.registerServiceInfoCallback(service,
                    command -> mainHandler.post(command), callback);
        } catch (RuntimeException error) {
            serviceCallbacks.remove(name, callback);
            if (!isLostLocked(name) && canTrackNameLocked(name) && queuedNames.add(name)) {
                resolveQueue.addLast(service);
                resolveNextLocked(runGeneration);
            }
        }
    }

    private void stopTrackingServiceLocked(String serviceName) {
        NsdManager.ServiceInfoCallback callback = serviceCallbacks.remove(serviceName);
        if (callback == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
        try {
            nsdManager.unregisterServiceInfoCallback(callback);
        } catch (RuntimeException ignored) {}
    }

    private void stopAllServiceTrackingLocked() {
        if (serviceCallbacks.isEmpty()) return;
        ArrayList<NsdManager.ServiceInfoCallback> callbacks =
                new ArrayList<>(serviceCallbacks.values());
        serviceCallbacks.clear();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
        for (NsdManager.ServiceInfoCallback callback : callbacks) {
            try {
                nsdManager.unregisterServiceInfoCallback(callback);
            } catch (RuntimeException ignored) {}
        }
    }

    @SuppressLint("NewApi")
    private static java.net.InetAddress preferredHost(NsdServiceInfo info) {
        java.net.InetAddress fallback = null;
        for (java.net.InetAddress address : info.getHostAddresses()) {
            if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()) continue;
            if (address instanceof java.net.Inet4Address) return address;
            if (fallback == null) fallback = address;
        }
        return fallback != null ? fallback : info.getHost();
    }

    private synchronized void scheduleResolveRetry(NsdServiceInfo info, long runGeneration) {
        scheduleResolveRetryLocked(info, runGeneration);
    }

    private void scheduleResolveRetryLocked(NsdServiceInfo info, long runGeneration) {
        if (!isCurrent(runGeneration)) return;
        String name = info.getServiceName();
        int count = Math.min(6, resolveFailures.getOrDefault(name, 0) + 1);
        resolveFailures.put(name, count);
        finishResolveLocked(name, runGeneration);
        if (!isCurrent(runGeneration) || isLostLocked(name) || !canTrackNameLocked(name)) return;
        long delay = count >= 6 ? 30_000L
                : Math.min(8_000L, 500L << Math.min(4, count - 1));
        cancelRetryLocked(name);
        Runnable retry = () -> {
            synchronized (NsdBrowser.this) {
                retryTasks.remove(name);
                if (!isCurrent(runGeneration) || isLostLocked(name)
                        || queuedNames.contains(name) || !canTrackNameLocked(name)) return;
                queuedNames.add(name);
                resolveQueue.addLast(info);
                resolveNextLocked(runGeneration);
            }
        };
        retryTasks.put(name, retry);
        mainHandler.postDelayed(retry, delay);
    }

    private void finishResolveLocked(String serviceName, long runGeneration) {
        queuedNames.remove(serviceName);
        resolving = false;
        resolveNextLocked(runGeneration);
    }

    private void publishLocked(long runGeneration) {
        ArrayList<DiscoveredPeer> copy = new ArrayList<>(peers.values());
        copy.sort(Comparator.comparing(peer -> peer.serviceName, String.CASE_INSENSITIVE_ORDER));
        List<DiscoveredPeer> immutable = Collections.unmodifiableList(copy);
        mainHandler.post(() -> {
            synchronized (NsdBrowser.this) {
                if (!isCurrent(runGeneration)) return;
            }
            listener.onPeersChanged(immutable);
        });
    }

    private boolean hasPeerNamedLocked(String serviceName) {
        for (DiscoveredPeer peer : peers.values()) {
            if (peer.serviceName.equals(serviceName)) return true;
        }
        return false;
    }

    private boolean canTrackNameLocked(String serviceName) {
        if (serviceName == null || serviceName.isEmpty()) return false;
        if (queuedNames.contains(serviceName) || serviceCallbacks.containsKey(serviceName)
                || hasPeerNamedLocked(serviceName)) return true;
        Set<String> names = new HashSet<>();
        names.addAll(queuedNames);
        names.addAll(serviceCallbacks.keySet());
        for (DiscoveredPeer peer : peers.values()) names.add(peer.serviceName);
        return names.size() < MAX_DISCOVERY_RECORDS;
    }

    private void markLostLocked(String name) {
        pruneLostLocked();
        if (lostNames.size() >= MAX_LOST_TOMBSTONES && !lostNames.containsKey(name)) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, Long> entry : lostNames.entrySet()) {
                if (entry.getValue() < oldestAt) {
                    oldestAt = entry.getValue();
                    oldest = entry.getKey();
                }
            }
            if (oldest != null) lostNames.remove(oldest);
        }
        lostNames.put(name, SystemClock.elapsedRealtime());
    }

    private boolean isLostLocked(String name) {
        pruneLostLocked();
        return lostNames.containsKey(name);
    }

    private void pruneLostLocked() {
        long cutoff = SystemClock.elapsedRealtime() - LOST_TOMBSTONE_MILLIS;
        lostNames.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private void cancelRetryLocked(String name) {
        Runnable retry = retryTasks.remove(name);
        if (retry != null) mainHandler.removeCallbacks(retry);
    }

    private void clearPendingLocked() {
        for (Runnable retry : retryTasks.values()) mainHandler.removeCallbacks(retry);
        retryTasks.clear();
        resolveQueue.clear();
        queuedNames.clear();
        lostNames.clear();
        resolving = false;
        resolveFailures.clear();
    }

    private void fail(String message) {
        mainHandler.post(() -> listener.onDiscoveryError(message));
    }

    private void releaseLock() {
        if (multicastLock != null) {
            try {
                if (multicastLock.isHeld()) multicastLock.release();
            } catch (RuntimeException ignored) {}
            multicastLock = null;
        }
    }

    private boolean isCurrent(long runGeneration) {
        return running && generation == runGeneration;
    }

    private synchronized boolean isCurrentThreadSafe(long runGeneration) {
        return isCurrent(runGeneration);
    }

    private static boolean isOurType(String type) {
        if (type == null) return false;
        String normalized = type.endsWith(".") ? type : type + ".";
        return SERVICE_TYPE.equalsIgnoreCase(normalized);
    }

    private static String safeMessage(Throwable error) {
        String text = error.getMessage();
        return text == null || text.trim().isEmpty() ? error.getClass().getSimpleName() : text;
    }
}
