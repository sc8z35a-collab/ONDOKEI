package jp.rstlab.batteryrelay.share;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.PowerManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.RouteInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import jp.rstlab.batteryrelay.core.CryptoBox;
import jp.rstlab.batteryrelay.core.SamplingPolicy;
import jp.rstlab.batteryrelay.model.RemoteSnapshot;

/** Pairs once, then polls using an adaptive interval and supports a forced fresh read. */
public final class RemoteClient {
    public interface Listener {
        void onPairingSucceeded(String deviceName);
        void onSnapshot(RemoteSnapshot snapshot);
        void onConnectionError(String message, boolean terminal);
    }

    private static final int MAX_LINE_BYTES = 24 * 1024;
    private static final int MAX_JSON_DEPTH = 8;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final PowerManager powerManager;
    private final ConnectivityManager connectivityManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong freshRequestGeneration = new AtomicLong(1L);
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final Object scheduleLock = new Object();
    private ExecutorService executor;
    private volatile byte[] sessionKey;
    private volatile String activeSessionId;
    private volatile DiscoveredPeer currentPeer;
    private final AtomicLong nextSequence = new AtomicLong(1L);
    private volatile long fulfilledFreshGeneration;
    private volatile long pollIntervalMillis = SamplingPolicy.NORMAL_INTERVAL_MILLIS;

    public RemoteClient(Context context, Listener listener) {
        this.listener = listener;
        Context app = context.getApplicationContext();
        this.powerManager = app.getSystemService(PowerManager.class);
        this.connectivityManager = app.getSystemService(ConnectivityManager.class);
    }

    public synchronized void pairAndStart(DiscoveredPeer peer, String sixDigitCode) {
        disconnect();
        String secret = normalizeSecret(sixDigitCode);
        if (!secret.matches("[2-9A-HJ-NP-Z]{26}")) {
            postError("26文字の共有キーを入力してください", true);
            return;
        }
        fulfilledFreshGeneration = 0L;
        freshRequestGeneration.incrementAndGet();
        running.set(true);
        long generation = connectionGeneration.incrementAndGet();
        ExecutorService ownedExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "relay-remote");
            thread.setDaemon(true);
            return thread;
        });
        executor = ownedExecutor;
        currentPeer = peer;
        AtomicReference<String> oneUseSecret = new AtomicReference<>(secret);
        ownedExecutor.execute(() -> connectLoop(
                oneUseSecret.getAndSet(null), generation, ownedExecutor));
    }

    public synchronized void disconnect() {
        DiscoveredPeer peer = currentPeer;
        String sessionId = activeSessionId;
        byte[] key = sessionKey == null ? null : sessionKey.clone();
        long sequence = nextSequence.getAndIncrement();
        connectionGeneration.incrementAndGet();
        running.set(false);
        ExecutorService closing = executor;
        if (closing != null && peer != null && sessionId != null && key != null) {
            try {
                closing.execute(() -> {
                    try { sendLogout(peer, sessionId, key, sequence); }
                    catch (Exception ignored) {}
                    finally { java.util.Arrays.fill(key, (byte) 0); }
                });
                closing.shutdown();
            } catch (RuntimeException rejected) {
                java.util.Arrays.fill(key, (byte) 0);
                closing.shutdownNow();
            }
        } else if (closing != null) closing.shutdownNow();
        executor = null;
        if (closing == null && sessionKey != null) java.util.Arrays.fill(sessionKey, (byte) 0);
        sessionKey = null;
        activeSessionId = null;
        currentPeer = null;
        synchronized (scheduleLock) {
            scheduleLock.notifyAll();
        }
    }

    public void updatePeer(DiscoveredPeer peer) {
        if (peer != null) currentPeer = peer;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setPollIntervalMillis(long intervalMillis) {
        long next = SamplingPolicy.clampRemoteInterval(intervalMillis);
        pollIntervalMillis = next;
        synchronized (scheduleLock) {
            scheduleLock.notifyAll();
        }
    }

    /** Wakes the polling loop and keeps the request pending until a fresh-request response arrives. */
    public void requestRefresh() {
        if (!running.get()) return;
        freshRequestGeneration.incrementAndGet();
        synchronized (scheduleLock) {
            scheduleLock.notifyAll();
        }
    }

    private void connectLoop(String code, long generation, ExecutorService ownedExecutor) {
        byte[] activeKey = null;
        try {
            DiscoveredPeer peer = currentPeer;
            if (peer == null) throw new IOException("peer_missing");
            PairSession pair = pair(peer, code);
            code = null;
            if (connectionGeneration.get() != generation || !running.get()) {
                java.util.Arrays.fill(pair.key, (byte) 0);
                return;
            }
            activeKey = pair.key;
            sessionKey = pair.key;
            activeSessionId = pair.sessionId;
            nextSequence.set(1L);
            postIfCurrent(generation, () -> listener.onPairingSucceeded(peer.serviceName));
            int consecutiveFailures = 0;
            while (running.get() && connectionGeneration.get() == generation
                    && !Thread.currentThread().isInterrupted()) {
                long requestedFreshGeneration = freshRequestGeneration.get();
                boolean forceFresh = requestedFreshGeneration > fulfilledFreshGeneration;
                try {
                    DiscoveredPeer endpoint = currentPeer;
                    if (endpoint == null) throw new IOException("peer_missing");
                    RemoteSnapshot snapshot = fetch(endpoint, pair.sessionId, pair.key,
                            nextSequence.getAndIncrement(), forceFresh);
                    if (forceFresh) {
                        // Only the exact fresh requests covered by this successful response are
                        // consumed. A request arriving during the network call remains pending.
                        fulfilledFreshGeneration = Math.max(
                                fulfilledFreshGeneration, requestedFreshGeneration);
                    }
                    consecutiveFailures = 0;
                    postIfCurrent(generation, () -> listener.onSnapshot(snapshot));
                    waitForNextPoll();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception error) {
                    consecutiveFailures++;
                    ErrorDecision decision = classifyPollingError(error);
                    // Server error envelopes are not authenticated. Require repetition before a
                    // remote peer can force us to discard an otherwise valid session.
                    boolean terminal = decision.terminal
                            && (!(error instanceof ServerException) || consecutiveFailures >= 3);
                    postErrorIfCurrent(generation, decision.message, terminal);
                    if (terminal) break;
                    try {
                        waitForDelay(decision.retryDelayMillis > 0L
                                ? decision.retryDelayMillis
                                : Math.min(60_000L,
                                2_000L << Math.min(5, consecutiveFailures - 1)),
                                requestedFreshGeneration);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception error) {
            if (connectionGeneration.get() == generation) {
                postErrorIfCurrent(generation, pairingErrorMessage(error), true);
            }
        } finally {
            if (activeKey != null) java.util.Arrays.fill(activeKey, (byte) 0);
            synchronized (this) {
                if (connectionGeneration.get() == generation) {
                    running.set(false);
                    if (sessionKey == activeKey) sessionKey = null;
                    activeSessionId = null;
                }
                if (executor == ownedExecutor) executor = null;
            }
            // A failed initial pairing must not leave an idle non-timeout thread behind.
            ownedExecutor.shutdown();
        }
    }

    private PairSession pair(DiscoveredPeer peer, String code) throws Exception {
        KeyPair clientKeys = CryptoBox.generateKeyPair();
        byte[] pairKey = CryptoBox.derivePairKey(clientKeys.getPrivate(), peer.publicKey,
                peer.salt, code, peer.shareId);
        try {
            JSONObject hello = new JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("device", safeDeviceName());
            byte[] nonce = CryptoBox.randomBytes(CryptoBox.GCM_NONCE_BYTES);
            byte[] box = CryptoBox.encrypt(pairKey, nonce,
                    hello.toString().getBytes(StandardCharsets.UTF_8),
                    CryptoBox.aad("pair-request", peer.shareId));
            JSONObject request = new JSONObject()
                    .put("v", 1)
                    .put("op", "pair")
                    .put("sid", peer.shareId)
                    .put("clientPub", CryptoBox.b64(clientKeys.getPublic().getEncoded()))
                    .put("nonce", CryptoBox.b64(nonce))
                    .put("box", CryptoBox.b64(box));
            JSONObject response = send(peer, request);
            if (!response.optBoolean("ok", false)) {
                throw new ServerException(response.optString("error", "pair_rejected"));
            }
            byte[] plain = CryptoBox.decrypt(pairKey,
                    CryptoBox.unb64(response.getString("nonce")),
                    CryptoBox.unb64(response.getString("box")),
                    CryptoBox.aad("pair-response", peer.shareId));
            JSONObject ack = parseObjectLimited(new String(plain, StandardCharsets.UTF_8));
            byte[] key = CryptoBox.unb64(ack.getString("key"));
            if (key.length != CryptoBox.AES_KEY_BYTES) throw new IOException("invalid_session_key");
            String session = ack.getString("session");
            if (!session.matches("[A-Za-z0-9_-]{16}")) {
                java.util.Arrays.fill(key, (byte) 0);
                throw new IOException("invalid_session_id");
            }
            return new PairSession(session, key);
        } finally {
            java.util.Arrays.fill(pairKey, (byte) 0);
        }
    }

    private RemoteSnapshot fetch(DiscoveredPeer peer, String sessionId, byte[] key, long sequence,
                                 boolean forceFresh)
            throws Exception {
        String aadId = sessionId + "/" + sequence;
        JSONObject inner = new JSONObject().put("ts", System.currentTimeMillis())
                .put("seq", sequence).put("fresh", forceFresh);
        byte[] nonce = CryptoBox.randomBytes(CryptoBox.GCM_NONCE_BYTES);
        byte[] box = CryptoBox.encrypt(key, nonce,
                inner.toString().getBytes(StandardCharsets.UTF_8),
                CryptoBox.aad("snapshot-request", aadId));
        JSONObject request = new JSONObject()
                .put("v", 1)
                .put("op", "snapshot")
                .put("session", sessionId)
                .put("seq", sequence)
                .put("nonce", CryptoBox.b64(nonce))
                .put("box", CryptoBox.b64(box));
        JSONObject response = send(peer, request);
        if (!response.optBoolean("ok", false)) {
            throw new ServerException(response.optString("error", "snapshot_rejected"));
        }
        byte[] plain = CryptoBox.decrypt(key,
                CryptoBox.unb64(response.getString("nonce")),
                CryptoBox.unb64(response.getString("box")),
                CryptoBox.aad("snapshot-response", aadId));
        return RemoteSnapshot.fromJson(parseObjectLimited(
                        new String(plain, StandardCharsets.UTF_8)),
                System.currentTimeMillis());
    }

    private void waitForNextPoll() throws InterruptedException {
        long anchor = SystemClock.elapsedRealtime();
        synchronized (scheduleLock) {
            while (running.get() && !hasPendingFreshRequest()) {
                long interval = pollIntervalMillis;
                if (isProtectionActive()) interval = SamplingPolicy.BACKGROUND_INTERVAL_MILLIS;
                long remaining = anchor + interval - SystemClock.elapsedRealtime();
                if (remaining <= 0L) break;
                scheduleLock.wait(remaining);
            }
        }
    }

    /**
     * Backs off the failed request even when that same fresh request remains pending. A newly
     * requested refresh (generation greater than the failed request) can still wake the delay.
     */
    private void waitForDelay(long delayMillis, long failedFreshGeneration)
            throws InterruptedException {
        synchronized (scheduleLock) {
            long deadline = SystemClock.elapsedRealtime() + delayMillis;
            while (running.get()) {
                if (freshRequestGeneration.get() > failedFreshGeneration) break;
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0L) break;
                scheduleLock.wait(remaining);
            }
        }
    }

    private boolean hasPendingFreshRequest() {
        return freshRequestGeneration.get() > fulfilledFreshGeneration;
    }

    private boolean isProtectionActive() {
        try {
            if (powerManager != null && powerManager.isPowerSaveMode()) return true;
        } catch (RuntimeException ignored) {}
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && safeThermalStatus() >= 3;
    }

    private void sendLogout(DiscoveredPeer peer, String sessionId, byte[] key, long sequence)
            throws Exception {
        String aadId = sessionId + "/" + sequence;
        JSONObject inner = new JSONObject().put("seq", sequence);
        byte[] nonce = CryptoBox.randomBytes(CryptoBox.GCM_NONCE_BYTES);
        byte[] box = CryptoBox.encrypt(key, nonce,
                inner.toString().getBytes(StandardCharsets.UTF_8),
                CryptoBox.aad("logout-request", aadId));
        send(peer, new JSONObject().put("v", 1).put("op", "logout")
                .put("session", sessionId).put("seq", sequence)
                .put("nonce", CryptoBox.b64(nonce)).put("box", CryptoBox.b64(box)));
    }

    private JSONObject send(DiscoveredPeer peer, JSONObject request) throws Exception {
        try (Socket socket = createWifiSocket(peer)) {
            socket.connect(new InetSocketAddress(peer.host, peer.port), 5_000);
            socket.setSoTimeout(7_000);
            socket.setTcpNoDelay(true);
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();
            return parseObjectLimited(readLineLimited(socket.getInputStream()));
        }
    }

    private Socket createWifiSocket(DiscoveredPeer peer) throws IOException {
        ConnectivityManager manager = connectivityManager;
        if (manager == null) throw new IOException("wifi_unavailable");
        if (peer.network != null && isWifi(manager, peer.network)) {
            return peer.network.getSocketFactory().createSocket();
        }
        Network active = manager.getActiveNetwork();
        if (isWifi(manager, active)) return active.getSocketFactory().createSocket();
        Network fallback = null;
        for (Network candidate : manager.getAllNetworks()) {
            if (!isWifi(manager, candidate)) continue;
            if (fallback == null) fallback = candidate;
            LinkProperties links = manager.getLinkProperties(candidate);
            if (links == null) continue;
            for (RouteInfo route : links.getRoutes()) {
                if (route.matches(peer.host)) return candidate.getSocketFactory().createSocket();
            }
        }
        if (fallback != null) return fallback.getSocketFactory().createSocket();
        throw new IOException("wifi_unavailable");
    }

    private static boolean isWifi(ConnectivityManager manager, Network network) {
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private int safeThermalStatus() {
        if (powerManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1;
        try {
            return powerManager.getCurrentThermalStatus();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static ErrorDecision classifyPollingError(Exception error) {
        if (error instanceof SecurityException) {
            return new ErrorDecision(
                    "ローカルネットワーク権限がありません。Androidの権限設定を確認してください",
                    true, 0L);
        }
        if (!(error instanceof ServerException)) {
            return new ErrorDecision("Wi‑Fiを再確認しています…（接続情報は保持）", false, 0L);
        }
        String code = ((ServerException) error).code;
        if ("invalid_session".equals(code)) {
            return new ErrorDecision(
                    "共有セッションの期限が切れました。再度共有キーで接続してください",
                    true, 0L);
        }
        if ("unsupported_version".equals(code) || "unsupported_operation".equals(code)) {
            return new ErrorDecision("共有元とアプリの通信バージョンが一致しません", true, 0L);
        }
        if ("host_stopped".equals(code) || "stale_share".equals(code)) {
            return new ErrorDecision("共有元で共有が停止または更新されました。再接続してください",
                    true, 0L);
        }
        if ("session_rate_limited".equals(code) || "rate_limited".equals(code)) {
            return new ErrorDecision("共有元の更新頻度制限中です。少し待って再試行します",
                    false, 5_000L);
        }
        if ("viewer_limit".equals(code)) {
            return new ErrorDecision("共有元の接続上限に達しています", true, 0L);
        }
        return new ErrorDecision("共有元で一時的な処理エラーが発生しました。再試行します",
                false, 0L);
    }

    private static String pairingErrorMessage(Exception error) {
        if (error instanceof SecurityException) {
            return "ローカルネットワーク権限がありません。Androidの権限設定を確認してください";
        }
        if (error instanceof ServerException) {
            String code = ((ServerException) error).code;
            if ("stale_share".equals(code)) return "共有情報が更新されました。端末を再検索してください";
            if ("viewer_limit".equals(code)) return "共有元の接続上限に達しています";
            if ("rate_limited".equals(code)) return "接続試行が多すぎます。少し待って再試行してください";
            if ("unsupported_version".equals(code)) return "共有元とアプリの通信バージョンが一致しません";
            if ("replayed_pair_request".equals(code) || "replay_table_full".equals(code)) {
                return "共有元の保護機能により接続が拒否されました。共有を開き直してください";
            }
            if ("request_failed".equals(code) || "pair_rejected".equals(code)) {
                return "接続できません。共有キーが正しいか確認してください";
            }
        }
        return "接続できません。同じWi‑Fiと共有キーを確認してください";
    }

    private static String readLineLimited(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') bytes.write(value);
            if (bytes.size() > MAX_LINE_BYTES) throw new IOException("message_too_large");
        }
        if (bytes.size() == 0 && value == -1) throw new IOException("empty_response");
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private static JSONObject parseObjectLimited(String text) throws org.json.JSONException {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
                continue;
            }
            if (value == '"') quoted = true;
            else if (value == '{' || value == '[') {
                if (++depth > MAX_JSON_DEPTH) throw new org.json.JSONException("json_too_deep");
            } else if (value == '}' || value == ']') {
                if (--depth < 0) throw new org.json.JSONException("invalid_json");
            }
        }
        if (quoted || depth != 0) throw new org.json.JSONException("invalid_json");
        return new JSONObject(text);
    }

    private void postError(String message, boolean terminal) {
        mainHandler.post(() -> listener.onConnectionError(message, terminal));
    }

    private void postErrorIfCurrent(long generation, String message, boolean terminal) {
        postIfCurrent(generation, () -> listener.onConnectionError(message, terminal));
    }

    private void postIfCurrent(long generation, Runnable callback) {
        mainHandler.post(() -> {
            if (connectionGeneration.get() == generation) callback.run();
        });
    }

    private static String safeDeviceName() {
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        String clean = model.replaceAll("[\\r\\n\\t]", " ").trim();
        if (clean.isEmpty()) return "Android";
        return clean.codePointCount(0, clean.length()) <= 48 ? clean
                : clean.substring(0, clean.offsetByCodePoints(0, 48));
    }

    private static String normalizeSecret(String value) {
        return value == null ? "" : value.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^2-9A-Z]", "");
    }

    private static final class ErrorDecision {
        final String message;
        final boolean terminal;
        final long retryDelayMillis;

        ErrorDecision(String message, boolean terminal, long retryDelayMillis) {
            this.message = message;
            this.terminal = terminal;
            this.retryDelayMillis = retryDelayMillis;
        }
    }

    private static final class ServerException extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;
        ServerException(String code) {
            super(code);
            this.code = code;
        }
    }

    private static final class PairSession {
        final String sessionId;
        final byte[] key;

        PairSession(String sessionId, byte[] key) {
            this.sessionId = sessionId;
            this.key = key;
        }
    }
}
