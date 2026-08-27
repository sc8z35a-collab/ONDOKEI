package jp.rstlab.batteryrelay.share;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import jp.rstlab.batteryrelay.core.CryptoBox;
import jp.rstlab.batteryrelay.core.TrendMath;
import jp.rstlab.batteryrelay.data.MeasurementRepository;
import jp.rstlab.batteryrelay.model.BatterySample;

/** Local-only encrypted snapshot server advertised over DNS-SD. */
public final class ShareHost {
    public interface Listener {
        void onHostStateChanged(boolean running, String pairingCode, int viewers, String error);
    }

    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int MAX_JSON_DEPTH = 8;
    private static final int MAX_VIEWERS = 8;
    private static final long SESSION_IDLE_MILLIS = Duration.ofHours(2).toMillis();
    private static final long MIN_FRESH_INTERVAL_MILLIS = 5_000L;
    private static final long UNCONFIRMED_SESSION_MILLIS = 30_000L;
    private static final int MAX_ACTIVE_CLIENTS = 8;
    private static final int MAX_REQUESTS_PER_ADDRESS_PER_MINUTE = 120;
    private static final int MAX_SNAPSHOT_REQUESTS_PER_SESSION_PER_MINUTE = 30;
    private static final int MAX_RATE_LIMIT_BUCKETS = 1024;
    private static final int CLIENT_READ_TIMEOUT_MILLIS = 1_500;
    private static final long REQUEST_DEADLINE_MILLIS = 4_000L;

    private final Context context;
    private final MeasurementRepository repository;
    private final NsdManager nsdManager;
    private final PowerManager powerManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, ViewerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, AttemptCounter> attempts = new ConcurrentHashMap<>();
    private final Map<String, AttemptCounter> requests = new ConcurrentHashMap<>();
    private final Map<String, Long> pairReplays = new ConcurrentHashMap<>();
    private volatile Map<String, AtomicInteger> activeAddresses = new ConcurrentHashMap<>();
    private final Set<Socket> openClients = ConcurrentHashMap.newKeySet();
    private final Object sessionLock = new Object();
    private final Object replayLock = new Object();
    private volatile Semaphore clientSlots = new Semaphore(MAX_ACTIVE_CLIENTS, true);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AtomicLong lastGlobalFreshElapsed = new AtomicLong(Long.MIN_VALUE);
    private final AtomicBoolean rebindScheduled = new AtomicBoolean();
    private volatile String pairingCode = "";
    private volatile String lastError;
    private volatile ServerSocket serverSocket;
    private volatile InetAddress boundAddress;
    private volatile Network boundNetwork;
    private volatile KeyPair hostKeyPair;
    private volatile byte[] pairSalt;
    private volatile String shareId;
    private volatile ExecutorService acceptExecutor;
    private volatile ExecutorService workerExecutor;
    private volatile ScheduledExecutorService scheduler;
    private volatile Listener listener;
    private NsdManager.RegistrationListener registrationListener;
    private ConnectivityManager.NetworkCallback networkCallback;
    private long registrationGeneration;
    private int registrationFailures;
    private long registrationRetryFor = -1L;

    public ShareHost(Context context, MeasurementRepository repository) {
        if (context == null || repository == null) {
            throw new IllegalArgumentException("context/repository required");
        }
        this.context = context.getApplicationContext();
        this.repository = repository;
        this.nsdManager = this.context.getSystemService(NsdManager.class);
        this.powerManager = this.context.getSystemService(PowerManager.class);
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
        publishState();
    }

    public synchronized void start() throws IOException, GeneralSecurityException {
        if (running.get()) {
            publishState();
            return;
        }
        if (nsdManager == null) throw new IOException("NSD is unavailable");

        lastError = null;
        long generation = lifecycleGeneration.incrementAndGet();
        ServerSocket socket = new ServerSocket();
        serverSocket = socket;
        clientSlots = new Semaphore(MAX_ACTIVE_CLIENTS, true);
        activeAddresses = new ConcurrentHashMap<>();
        requests.clear();
        attempts.clear();
        lastGlobalFreshElapsed.set(Long.MIN_VALUE);
        registrationFailures = 0;
        registrationRetryFor = -1L;
        socket.setReuseAddress(true);
        try {
            WifiBinding binding = requireWifiBinding();
            boundAddress = binding.address;
            boundNetwork = binding.network;
            socket.bind(new InetSocketAddress(boundAddress, 0));
            hostKeyPair = CryptoBox.generateKeyPair();
            pairSalt = CryptoBox.randomBytes(16);
            byte[] idBytes = CryptoBox.randomBytes(12);
            try {
                shareId = CryptoBox.b64(idBytes);
            } finally {
                java.util.Arrays.fill(idBytes, (byte) 0);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException error) {
            stop();
            if (error instanceof IOException) throw (IOException) error;
            if (error instanceof GeneralSecurityException) throw (GeneralSecurityException) error;
            throw error;
        }
        acceptExecutor = Executors.newSingleThreadExecutor(r -> namedThread(r, "relay-accept"));
        ThreadPoolExecutor workers = new ThreadPoolExecutor(
                MAX_ACTIVE_CLIENTS, MAX_ACTIVE_CLIENTS, 90L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_ACTIVE_CLIENTS), r -> namedThread(r, "relay-worker"),
                new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        workerExecutor = workers;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> namedThread(r, "relay-timer"));
        running.set(true);
        rotatePairingCode();

        try {
            registerService(socket.getLocalPort());
            acceptExecutor.execute(() -> acceptLoop(generation));
            scheduler.scheduleWithFixedDelay(() -> {
                        if (isCurrent(generation)) rotatePairingCode();
                    }, 5, 5, TimeUnit.MINUTES);
            scheduler.scheduleWithFixedDelay(() -> {
                        if (isCurrent(generation)) removeExpiredSessions();
                    }, 1, 1, TimeUnit.MINUTES);
            registerWifiCallback(generation);
            publishState();
        } catch (RuntimeException error) {
            stop();
            throw new IOException("共有サービスを開始できません", error);
        }
    }

    public synchronized void stop() {
        boolean wasRunning = running.getAndSet(false);
        synchronized (sessionLock) {
            lifecycleGeneration.incrementAndGet();
            sessions.values().forEach(ViewerSession::destroy);
            sessions.clear();
        }
        closeQuietly(serverSocket);
        for (Socket client : openClients) closeQuietly(client);
        openClients.clear();
        serverSocket = null;
        boundAddress = null;
        boundNetwork = null;
        unregisterWifiCallback();
        unregisterServiceLocked();
        registrationRetryFor = -1L;
        registrationFailures = 0;
        shutdown(acceptExecutor);
        shutdown(workerExecutor);
        shutdown(scheduler);
        acceptExecutor = null;
        workerExecutor = null;
        scheduler = null;
        attempts.clear();
        requests.clear();
        activeAddresses.clear();
        pairReplays.clear();
        pairingCode = "";
        hostKeyPair = null;
        if (pairSalt != null) java.util.Arrays.fill(pairSalt, (byte) 0);
        pairSalt = null;
        shareId = null;
        lastGlobalFreshElapsed.set(Long.MIN_VALUE);
        if (wasRunning) publishState();
    }

    public boolean isRunning() { return running.get(); }
    public String getPairingCode() { return pairingCode; }

    public int getViewerCount() {
        removeExpiredSessions();
        return sessions.size();
    }

    public String getShareId() { return shareId; }
    public void refreshPairingCode() { rotatePairingCode(); }

    private synchronized void registerService(int port) {
        if (!running.get() || port < 1 || port > 65_535 || shareId == null
                || pairSalt == null || hostKeyPair == null) return;
        unregisterServiceLocked();
        long registration = ++registrationGeneration;
        registrationRetryFor = -1L;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceInstanceName());
        info.setServiceType(NsdBrowser.SERVICE_TYPE);
        info.setPort(port);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && boundNetwork != null) {
            info.setNetwork(boundNetwork);
        }
        info.setAttribute("v", "1");
        info.setAttribute("sid", shareId);
        info.setAttribute("salt", CryptoBox.b64(pairSalt));
        info.setAttribute("pub", CryptoBox.b64(hostKeyPair.getPublic().getEncoded()));

        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                synchronized (ShareHost.this) {
                    if (registration == registrationGeneration && running.get()) {
                        registrationFailures = 0;
                        registrationRetryFor = -1L;
                        lastError = null;
                        publishState();
                    }
                }
            }

            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                synchronized (ShareHost.this) {
                    if (registration != registrationGeneration || !running.get()) return;
                    lastError = "端末の公開に失敗しました (" + errorCode + ")";
                    publishState();
                    scheduleRegistrationRetry(registration);
                }
            }

            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
        };
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    private synchronized void scheduleRegistrationRetry(long failedRegistration) {
        if (!running.get() || registrationRetryFor == failedRegistration) return;
        registrationRetryFor = failedRegistration;
        ScheduledExecutorService timer = scheduler;
        if (timer == null) {
            registrationRetryFor = -1L;
            return;
        }
        int failures = Math.min(6, ++registrationFailures);
        long delay = Math.min(60_000L, 2_000L << Math.max(0, failures - 1));
        try {
            timer.schedule(() -> {
                synchronized (ShareHost.this) {
                    if (registrationRetryFor != failedRegistration) return;
                    registrationRetryFor = -1L;
                    if (!running.get() || registrationGeneration != failedRegistration) return;
                    ServerSocket socket = serverSocket;
                    if (socket == null || socket.isClosed()) return;
                    try {
                        registerService(socket.getLocalPort());
                    } catch (RuntimeException retryFailure) {
                        lastError = "端末の再公開を待っています";
                        publishState();
                        scheduleRegistrationRetry(registrationGeneration);
                    }
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            registrationRetryFor = -1L;
        }
    }

    private synchronized void unregisterServiceLocked() {
        registrationGeneration++;
        NsdManager.RegistrationListener registered = registrationListener;
        registrationListener = null;
        if (registered == null || nsdManager == null) return;
        try { nsdManager.unregisterService(registered); }
        catch (RuntimeException ignored) {}
    }

    private synchronized void registerWifiCallback(long generation) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null || networkCallback != null) return;
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { scheduleWifiRebind(generation); }
            @Override public void onLost(Network network) { scheduleWifiRebind(generation); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) {
                scheduleWifiRebind(generation);
            }
        };
        networkCallback = callback;
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        manager.registerNetworkCallback(request, callback);
    }

    private synchronized void unregisterWifiCallback() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        rebindScheduled.set(false);
        if (manager == null || callback == null) return;
        try { manager.unregisterNetworkCallback(callback); }
        catch (RuntimeException ignored) {}
    }

    private void scheduleWifiRebind(long generation) {
        if (!isCurrent(generation) || !rebindScheduled.compareAndSet(false, true)) return;
        ScheduledExecutorService timer = scheduler;
        if (timer == null) {
            rebindScheduled.set(false);
            return;
        }
        try {
            timer.schedule(() -> {
                try {
                    if (isCurrent(generation)) rebindToCurrentWifi(generation);
                } finally {
                    rebindScheduled.set(false);
                }
            }, 750L, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            rebindScheduled.set(false);
        }
    }

    /** Rebinds only the transport; authenticated sessions and the sharing secret stay intact. */
    private void rebindToCurrentWifi(long generation) {
        if (!isCurrent(generation)) return;
        WifiBinding binding;
        try {
            binding = requireWifiBinding();
        } catch (IOException unavailable) {
            synchronized (this) {
                if (!isCurrent(generation)) return;
                ServerSocket old = serverSocket;
                serverSocket = null;
                boundAddress = null;
                boundNetwork = null;
                unregisterServiceLocked();
                closeQuietly(old);
                lastError = "Wi‑Fiの再接続を待っています";
            }
            publishState();
            return;
        }

        synchronized (this) {
            if (!isCurrent(generation)) return;
            ServerSocket current = serverSocket;
            if (binding.address.equals(boundAddress) && binding.network.equals(boundNetwork)
                    && current != null && !current.isClosed()) return;
            InetAddress previousAddress = boundAddress;
            Network previousNetwork = boundNetwork;
            ServerSocket replacement = null;
            try {
                replacement = new ServerSocket();
                replacement.setReuseAddress(true);
                replacement.bind(new InetSocketAddress(binding.address, 0));
                if (!isCurrent(generation)) {
                    closeQuietly(replacement);
                    return;
                }
                serverSocket = replacement;
                boundAddress = binding.address;
                boundNetwork = binding.network;
                registerService(replacement.getLocalPort());
                closeQuietly(current);
                lastError = null;
            } catch (IOException | RuntimeException error) {
                closeQuietly(replacement);
                serverSocket = current;
                boundAddress = previousAddress;
                boundNetwork = previousNetwork;
                lastError = "Wi‑Fi待受を更新できません: " + safeMessage(error);
                if (current != null && !current.isClosed()) {
                    scheduleRegistrationRetry(registrationGeneration);
                }
            }
        }
        publishState();
    }

    private void acceptLoop(long generation) {
        while (isCurrent(generation)) {
            ServerSocket listening = serverSocket;
            if (listening == null) {
                SystemClock.sleep(250L);
                continue;
            }
            try {
                Socket client = listening.accept();
                InetAddress remoteAddress = client.getInetAddress();
                String rateAddress = rateLimitKey(remoteAddress);
                Semaphore slots = clientSlots;
                Map<String, AtomicInteger> addressCounters = activeAddresses;
                if (!consumeRequest(rateAddress)) {
                    closeQuietly(client);
                    continue;
                }
                // The same /64 is one actor for IPv6 rate limiting and active connection limits.
                // Otherwise a LAN peer can rotate privacy addresses to occupy all worker slots.
                if (!acquireAddress(addressCounters, rateAddress)) {
                    closeQuietly(client);
                    continue;
                }
                if (!slots.tryAcquire()) {
                    releaseAddress(addressCounters, rateAddress);
                    closeQuietly(client);
                    continue;
                }
                openClients.add(client);
                client.setSoTimeout(CLIENT_READ_TIMEOUT_MILLIS);
                client.setTcpNoDelay(true);
                ExecutorService workers = workerExecutor;
                if (workers != null) {
                    try {
                        workers.execute(() -> {
                            try { handleClient(client, rateAddress, generation); }
                            finally {
                                openClients.remove(client);
                                slots.release();
                                releaseAddress(addressCounters, rateAddress);
                            }
                        });
                    } catch (RejectedExecutionException overloaded) {
                        openClients.remove(client);
                        slots.release();
                        releaseAddress(addressCounters, rateAddress);
                        closeQuietly(client);
                    }
                } else {
                    openClients.remove(client);
                    slots.release();
                    releaseAddress(addressCounters, rateAddress);
                    closeQuietly(client);
                }
            } catch (IOException error) {
                if (running.get() && listening == serverSocket) {
                    lastError = "共有接続を待機できません: " + safeMessage(error);
                    publishState();
                }
            }
        }
    }

    private void handleClient(Socket socket, String address, long generation) {
        try (Socket client = socket) {
            try {
                String line = readLineLimited(client.getInputStream(), REQUEST_DEADLINE_MILLIS);
                JSONObject request = parseObjectLimited(line);
                if (!isCurrent(generation)) throw new ProtocolException("host_stopped");
                if (request.optInt("v", -1) != 1) throw new ProtocolException("unsupported_version");
                String op = request.optString("op", "");
                JSONObject response;
                if ("pair".equals(op)) response = handlePair(request, address, generation);
                else if ("snapshot".equals(op)) response = handleSnapshot(request, generation);
                else if ("logout".equals(op)) response = handleLogout(request, generation);
                else throw new ProtocolException("unsupported_operation");
                writeLine(client.getOutputStream(), response.toString());
            } catch (Exception error) {
                try {
                    String code = error instanceof ProtocolException
                            ? error.getMessage() : "request_failed";
                    writeLine(client.getOutputStream(), new JSONObject()
                            .put("ok", false).put("error", code).toString());
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private JSONObject handlePair(JSONObject request, String address, long generation) throws Exception {
        if (!isCurrent(generation)) throw new ProtocolException("host_stopped");
        if (!consumeAttempt(address)) throw new ProtocolException("rate_limited");
        byte[] pairKey = null;
        byte[] plaintext = null;
        try {
            String sid = request.getString("sid");
            if (!sid.equals(shareId)) throw new ProtocolException("stale_share");
            long now = SystemClock.elapsedRealtime();
            String replayToken = pairReplayToken(request);
            trimPairReplays(now);
            if (pairReplays.containsKey(replayToken)) {
                throw new ProtocolException("replayed_pair_request");
            }
            PublicKey clientPublic = CryptoBox.decodePublicKey(
                    CryptoBox.unb64(request.getString("clientPub")));
            pairKey = CryptoBox.derivePairKey(hostKeyPair.getPrivate(), clientPublic,
                    pairSalt, pairingCode, shareId);
            plaintext = CryptoBox.decrypt(pairKey,
                    CryptoBox.unb64(request.getString("nonce")),
                    CryptoBox.unb64(request.getString("box")),
                    CryptoBox.aad("pair-request", shareId));
            JSONObject hello = parseObjectLimited(new String(plaintext, StandardCharsets.UTF_8));
            hello.getLong("ts");
            synchronized (replayLock) {
                if (!isCurrent(generation)) throw new ProtocolException("host_stopped");
                trimPairReplays(now);
                if (pairReplays.containsKey(replayToken)) {
                    throw new ProtocolException("replayed_pair_request");
                }
                if (pairReplays.size() >= 1024) {
                    throw new ProtocolException("replay_table_full");
                }
                pairReplays.put(replayToken, now);
            }

            String sessionId;
            byte[] idBytes = CryptoBox.randomBytes(12);
            try { sessionId = CryptoBox.b64(idBytes); }
            finally { java.util.Arrays.fill(idBytes, (byte) 0); }
            byte[] sessionKey = CryptoBox.randomBytes(CryptoBox.AES_KEY_BYTES);
            try {
                synchronized (sessionLock) {
                    if (!isCurrent(generation)) throw new ProtocolException("host_stopped");
                    removeExpiredSessionsLocked(now);
                    if (sessions.size() >= MAX_VIEWERS) throw new ProtocolException("viewer_limit");
                    sessions.put(sessionId, new ViewerSession(sessionKey, now));
                }

                JSONObject ack = new JSONObject()
                        .put("session", sessionId)
                        .put("key", CryptoBox.b64(sessionKey))
                        .put("expiresAfterIdleMs", SESSION_IDLE_MILLIS);
                byte[] nonce = CryptoBox.randomBytes(CryptoBox.GCM_NONCE_BYTES);
                byte[] box = CryptoBox.encrypt(pairKey, nonce,
                        ack.toString().getBytes(StandardCharsets.UTF_8),
                        CryptoBox.aad("pair-response", shareId));
                publishState();
                return new JSONObject().put("ok", true)
                        .put("nonce", CryptoBox.b64(nonce))
                        .put("box", CryptoBox.b64(box));
            } finally {
                java.util.Arrays.fill(sessionKey, (byte) 0);
            }
        } finally {
            if (plaintext != null) java.util.Arrays.fill(plaintext, (byte) 0);
            if (pairKey != null) java.util.Arrays.fill(pairKey, (byte) 0);
        }
    }

    private JSONObject handleSnapshot(JSONObject request, long generation) throws Exception {
        if (!isCurrent(generation)) throw new ProtocolException("host_stopped");
        String sessionId = request.getString("session");
        long seq = request.getLong("seq");
        ViewerSession session = sessions.get(sessionId);
        long now = SystemClock.elapsedRealtime();
        if (session == null || session.isExpired(now) || !session.isSequenceFresh(seq)) {
            if (session != null && session.isExpired(now)
                    && sessions.remove(sessionId, session)) {
                session.destroy();
                publishState();
            }
            throw new ProtocolException("invalid_session");
        }
        String requestAadId = sessionId + "/" + seq;
        byte[] plaintext = null;
        try {
            plaintext = CryptoBox.decrypt(session.key,
                    CryptoBox.unb64(request.getString("nonce")),
                    CryptoBox.unb64(request.getString("box")),
                    CryptoBox.aad("snapshot-request", requestAadId));
            JSONObject inner = parseObjectLimited(new String(plaintext, StandardCharsets.UTF_8));
            if (inner.getLong("seq") != seq) throw new ProtocolException("invalid_request");
            if (!session.acceptSequence(seq, now, true)) {
                throw new ProtocolException("session_rate_limited");
            }

            boolean freshRequested = inner.optBoolean("fresh", false);
            boolean freshApplied = false;
            if (freshRequested && allowFreshSample(session, now)) {
                try {
                    repository.sampleNowIfOlderThan(MIN_FRESH_INTERVAL_MILLIS);
                    freshApplied = true;
                } catch (RuntimeException ignored) {}
            }

            JSONObject payload = snapshotJson(System.currentTimeMillis(), freshRequested,
                    freshApplied, seq);
            byte[] nonce = CryptoBox.randomBytes(CryptoBox.GCM_NONCE_BYTES);
            byte[] box = CryptoBox.encrypt(session.key, nonce,
                    payload.toString().getBytes(StandardCharsets.UTF_8),
                    CryptoBox.aad("snapshot-response", requestAadId));
            return new JSONObject().put("ok", true)
                    .put("nonce", CryptoBox.b64(nonce))
                    .put("box", CryptoBox.b64(box));
        } finally {
            if (plaintext != null) java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    private JSONObject handleLogout(JSONObject request, long generation) throws Exception {
        if (!isCurrent(generation)) throw new ProtocolException("host_stopped");
        String sessionId = request.getString("session");
        long seq = request.getLong("seq");
        ViewerSession session = sessions.get(sessionId);
        long now = SystemClock.elapsedRealtime();
        if (session == null || session.isExpired(now) || !session.isSequenceFresh(seq)) {
            if (session != null && session.isExpired(now)
                    && sessions.remove(sessionId, session)) {
                session.destroy();
                publishState();
            }
            throw new ProtocolException("invalid_session");
        }
        String aadId = sessionId + "/" + seq;
        byte[] plaintext = null;
        try {
            plaintext = CryptoBox.decrypt(session.key,
                    CryptoBox.unb64(request.getString("nonce")),
                    CryptoBox.unb64(request.getString("box")),
                    CryptoBox.aad("logout-request", aadId));
            JSONObject inner = parseObjectLimited(new String(plaintext, StandardCharsets.UTF_8));
            if (inner.getLong("seq") != seq || !session.acceptSequence(seq, now, false)) {
                throw new ProtocolException("invalid_logout");
            }
        } finally {
            if (plaintext != null) java.util.Arrays.fill(plaintext, (byte) 0);
        }
        if (sessions.remove(sessionId, session)) session.destroy();
        publishState();
        return new JSONObject().put("ok", true);
    }

    private boolean allowFreshSample(ViewerSession session, long nowElapsed) {
        try {
            if (powerManager != null && powerManager.isPowerSaveMode()) return false;
        } catch (RuntimeException ignored) {}
        BatterySample latest = repository.latest();
        if (latest != null && latest.thermalStatus >= 3) return false;
        if (!session.canFresh(nowElapsed, MIN_FRESH_INTERVAL_MILLIS)) return false;

        while (true) {
            long prior = lastGlobalFreshElapsed.get();
            if (prior != Long.MIN_VALUE && nowElapsed >= prior
                    && nowElapsed - prior < MIN_FRESH_INTERVAL_MILLIS) return false;
            if (lastGlobalFreshElapsed.compareAndSet(prior, nowElapsed)) {
                if (session.commitFresh(nowElapsed, MIN_FRESH_INTERVAL_MILLIS)) return true;
                lastGlobalFreshElapsed.compareAndSet(nowElapsed, prior);
                return false;
            }
        }
    }

    private JSONObject snapshotJson(long now, boolean freshRequested, boolean freshApplied,
                                    long requestSequence) throws JSONException {
        List<BatterySample> samples = TrendMath.retainWindow(repository.snapshot(), now);
        JSONArray array = new JSONArray();
        for (BatterySample sample : samples) array.put(sample.toJson());
        return new JSONObject()
                .put("device", safeDeviceName())
                .put("generatedAt", now)
                .put("freshRequested", freshRequested)
                .put("freshApplied", freshApplied)
                .put("requestSequence", requestSequence)
                .put("retentionSeconds", 1800)
                .put("samples", array);
    }

    private synchronized void rotatePairingCode() {
        if (!running.get()) return;
        pairingCode = CryptoBox.randomPairingSecret();
        pairReplays.clear();
        publishState();
    }

    private void removeExpiredSessions() {
        long now = SystemClock.elapsedRealtime();
        boolean removed;
        synchronized (sessionLock) {
            removed = removeExpiredSessionsLocked(now);
        }
        attempts.entrySet().removeIf(entry -> entry.getValue().isStale(now));
        requests.entrySet().removeIf(entry -> entry.getValue().isStale(now));
        trimPairReplays(now);
        if (removed) publishState();
    }

    private boolean removeExpiredSessionsLocked(long now) {
        AtomicBoolean removed = new AtomicBoolean(false);
        sessions.forEach((id, session) -> {
            if (session.isExpired(now) && sessions.remove(id, session)) {
                session.destroy();
                removed.set(true);
            }
        });
        return removed.get();
    }

    private boolean consumeAttempt(String address) {
        return consumeBounded(attempts, address, 5);
    }

    private boolean consumeRequest(String address) {
        return consumeBounded(requests, address, MAX_REQUESTS_PER_ADDRESS_PER_MINUTE);
    }

    private static boolean consumeBounded(Map<String, AttemptCounter> counters,
                                          String address, int limit) {
        String key = address == null ? "unknown" : address;
        AttemptCounter existing = counters.get(key);
        if (existing != null) return existing.consume(SystemClock.elapsedRealtime(), limit);
        if (counters.size() >= MAX_RATE_LIMIT_BUCKETS) return false;
        AttemptCounter created = new AttemptCounter();
        AttemptCounter winner = counters.putIfAbsent(key, created);
        return (winner == null ? created : winner).consume(SystemClock.elapsedRealtime(), limit);
    }

    private static boolean acquireAddress(Map<String, AtomicInteger> counters, String address) {
        String key = address == null ? "unknown" : address;
        AtomicInteger counter = counters.computeIfAbsent(key, ignored -> new AtomicInteger());
        int active = counter.incrementAndGet();
        if (active <= 1) return true;
        if (counter.decrementAndGet() == 0) counters.remove(key, counter);
        return false;
    }

    private static void releaseAddress(Map<String, AtomicInteger> counters, String address) {
        String key = address == null ? "unknown" : address;
        AtomicInteger counter = counters.get(key);
        if (counter != null && counter.decrementAndGet() <= 0) counters.remove(key, counter);
    }

    private static String pairReplayToken(JSONObject request) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(request.getString("clientPub").getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(request.getString("nonce").getBytes(StandardCharsets.UTF_8));
        byte[] token = digest.digest();
        try { return CryptoBox.b64(token); }
        finally { java.util.Arrays.fill(token, (byte) 0); }
    }

    private void trimPairReplays(long now) {
        pairReplays.entrySet().removeIf(entry -> now >= entry.getValue()
                && now - entry.getValue() > 6L * 60L * 1000L);
    }

    private void publishState() {
        Listener current = listener;
        if (current == null) return;
        boolean active = running.get();
        String code = pairingCode;
        int count = sessions.size();
        String error = lastError;
        mainHandler.post(() -> {
            synchronized (ShareHost.this) {
                if (listener != current) return;
            }
            current.onHostStateChanged(active, code, count, error);
        });
    }

    private static String safeDeviceName() {
        String maker = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "端末" : Build.MODEL.trim();
        String value = maker.equalsIgnoreCase(model) ? model : maker + " " + model;
        value = value.replaceAll("[\r\n\t]", " ").trim();
        if (value.isEmpty()) return "Android 端末";
        return value.codePointCount(0, value.length()) <= 48 ? value
                : value.substring(0, value.offsetByCodePoints(0, 48));
    }

    private static String serviceInstanceName() {
        String prefix = "Battery Relay - ";
        String source = safeDeviceName();
        StringBuilder clipped = new StringBuilder();
        int bytesUsed = 0;
        for (int offset = 0; offset < source.length();) {
            int codePoint = source.codePointAt(offset);
            String chars = new String(Character.toChars(codePoint));
            int bytes = chars.getBytes(StandardCharsets.UTF_8).length;
            if (bytesUsed + bytes > 44) break;
            clipped.append(chars);
            bytesUsed += bytes;
            offset += Character.charCount(codePoint);
        }
        return prefix + (clipped.length() == 0 ? "Android" : clipped);
    }

    static String readLineLimited(InputStream input, long deadlineMillis) throws IOException {
        if (input == null) throw new IOException("missing_input");
        if (deadlineMillis <= 0L) throw new IOException("request_deadline");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        long started = System.nanoTime();
        int value;
        while ((value = input.read()) != -1) {
            if (elapsedMillis(started) > deadlineMillis) {
                throw new IOException("request_deadline");
            }
            if (value == '\n') break;
            if (value != '\r') bytes.write(value);
            if (bytes.size() > MAX_LINE_BYTES) throw new IOException("message_too_large");
        }
        if (bytes.size() == 0 && value == -1) throw new IOException("empty_request");
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private static long elapsedMillis(long startedNanos) {
        long elapsed = System.nanoTime() - startedNanos;
        if (elapsed < 0L) return Long.MAX_VALUE;
        return elapsed / 1_000_000L;
    }

    private static JSONObject parseObjectLimited(String text) throws JSONException {
        if (text == null) throw new JSONException("missing_json");
        requireJsonDepth(text);
        return new JSONObject(text);
    }

    private static void requireJsonDepth(String text) throws JSONException {
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
                if (++depth > MAX_JSON_DEPTH) throw new JSONException("json_too_deep");
            } else if (value == '}' || value == ']') {
                if (--depth < 0) throw new JSONException("invalid_json");
            }
        }
        if (quoted || depth != 0) throw new JSONException("invalid_json");
    }

    private static void writeLine(OutputStream output, String line) throws IOException {
        if (output == null || line == null) throw new IOException("missing_output");
        output.write(line.getBytes(StandardCharsets.UTF_8));
        output.write('\n');
        output.flush();
    }

    private static Thread namedThread(Runnable runnable, String name) {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private WifiBinding requireWifiBinding() throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) throw new IOException("Wi‑Fi状態を取得できません");
        Network wifi = manager.getActiveNetwork();
        if (!isWifi(manager, wifi)) wifi = null;
        Network fallbackWifi = null;
        if (wifi == null) for (Network candidate : manager.getAllNetworks()) {
            if (isWifi(manager, candidate)) {
                if (fallbackWifi == null) fallbackWifi = candidate;
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(candidate);
                if (capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    wifi = candidate;
                    break;
                }
            }
        }
        if (wifi == null) wifi = fallbackWifi;
        LinkProperties links = wifi == null ? null : manager.getLinkProperties(wifi);
        if (links == null) throw new IOException("Wi‑Fiに接続してください");
        InetAddress fallback = null;
        for (LinkAddress link : links.getLinkAddresses()) {
            if (link == null) continue;
            InetAddress address = link.getAddress();
            if (address == null || address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) continue;
            if (address instanceof java.net.Inet4Address) return new WifiBinding(wifi, address);
            if (fallback == null) fallback = address;
        }
        if (fallback == null) throw new IOException("Wi‑Fiアドレスを取得できません");
        return new WifiBinding(wifi, fallback);
    }

    private static boolean isWifi(ConnectivityManager manager, Network network) {
        if (manager == null || network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private static String rateLimitKey(InetAddress address) {
        if (address == null) return "unknown";
        byte[] raw = address.getAddress();
        if (raw.length != 16) return address.getHostAddress();
        StringBuilder prefix = new StringBuilder("v6:");
        for (int i = 0; i < 8; i++) {
            prefix.append(Character.forDigit((raw[i] >>> 4) & 0xf, 16));
            prefix.append(Character.forDigit(raw[i] & 0xf, 16));
        }
        return prefix.toString();
    }

    private static final class WifiBinding {
        final Network network;
        final InetAddress address;
        WifiBinding(Network network, InetAddress address) {
            this.network = network;
            this.address = address;
        }
    }

    private boolean isCurrent(long generation) {
        return running.get() && lifecycleGeneration.get() == generation;
    }

    private static void shutdown(ExecutorService executor) {
        if (executor != null) executor.shutdownNow();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) {}
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String text = error.getMessage();
        return text == null || text.trim().isEmpty() ? error.getClass().getSimpleName() : text;
    }

    private static final class ViewerSession {
        final byte[] key;
        final long createdElapsed;
        volatile long lastSeen;
        private long highestSequence;
        private long lastFreshElapsed = Long.MIN_VALUE;
        private long requestWindowStart;
        private int requestsInWindow;
        private volatile boolean confirmed;

        ViewerSession(byte[] key, long now) {
            if (key == null) throw new IllegalArgumentException("session key required");
            this.key = key.clone();
            this.createdElapsed = now;
            this.lastSeen = now;
        }

        synchronized boolean isSequenceFresh(long sequence) {
            return sequence > highestSequence && sequence >= 1;
        }

        synchronized boolean acceptSequence(long sequence, long now, boolean countForRateLimit) {
            if (sequence <= highestSequence || sequence < 1) return false;
            if (countForRateLimit) {
                if (requestWindowStart == 0L || now < requestWindowStart
                        || now - requestWindowStart >= 60_000L) {
                    requestWindowStart = now;
                    requestsInWindow = 0;
                }
                if (requestsInWindow >= MAX_SNAPSHOT_REQUESTS_PER_SESSION_PER_MINUTE) return false;
                requestsInWindow++;
            }
            highestSequence = sequence;
            lastSeen = now;
            confirmed = true;
            return true;
        }

        boolean isExpired(long now) {
            if (now < createdElapsed || now < lastSeen) return false;
            return (!confirmed && now - createdElapsed > UNCONFIRMED_SESSION_MILLIS)
                    || now - lastSeen > SESSION_IDLE_MILLIS;
        }

        synchronized boolean canFresh(long now, long minimumInterval) {
            return lastFreshElapsed == Long.MIN_VALUE || now < lastFreshElapsed
                    || now - lastFreshElapsed >= minimumInterval;
        }

        synchronized boolean commitFresh(long now, long minimumInterval) {
            if (!canFresh(now, minimumInterval)) return false;
            lastFreshElapsed = now;
            return true;
        }

        void destroy() { java.util.Arrays.fill(key, (byte) 0); }
    }

    private static final class AttemptCounter {
        private long windowStart;
        private int attempts;
        private long lastAttempt;

        synchronized boolean consume(long now, int limit) {
            resetIfNeeded(now);
            lastAttempt = now;
            if (attempts >= limit) return false;
            attempts++;
            return true;
        }

        synchronized boolean isStale(long now) {
            return lastAttempt != 0L && now >= lastAttempt
                    && now - lastAttempt > 10L * 60L * 1000L;
        }

        private void resetIfNeeded(long now) {
            if (windowStart == 0L || now < windowStart || now - windowStart >= 60_000L) {
                windowStart = now;
                attempts = 0;
            }
        }
    }

    private static final class ProtocolException extends Exception {
        private static final long serialVersionUID = 1L;
        ProtocolException(String message) { super(message); }
    }
}
