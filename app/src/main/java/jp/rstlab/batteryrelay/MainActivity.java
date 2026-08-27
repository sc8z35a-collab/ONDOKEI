package jp.rstlab.batteryrelay;

import android.Manifest;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PersistableBundle;
import android.os.Process;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jp.rstlab.batteryrelay.core.SamplingPolicy;
import jp.rstlab.batteryrelay.core.TrendMath;
import jp.rstlab.batteryrelay.data.MeasurementRepository;
import jp.rstlab.batteryrelay.model.BatterySample;
import jp.rstlab.batteryrelay.model.RemoteSnapshot;
import jp.rstlab.batteryrelay.service.MonitorService;
import jp.rstlab.batteryrelay.share.DiscoveredPeer;
import jp.rstlab.batteryrelay.share.NsdBrowser;
import jp.rstlab.batteryrelay.share.RemoteDeviceManager;
import jp.rstlab.batteryrelay.share.ShareHost;
import jp.rstlab.batteryrelay.ui.TrendChartView;
import jp.rstlab.batteryrelay.ui.Ui;

public final class MainActivity extends android.app.Activity {
    private static final int REQUEST_NOTIFICATIONS = 41;
    private static final long FUTURE_CLOCK_TOLERANCE_MILLIS = 5_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService uiIo = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "relay-ui-io");
        thread.setDaemon(true);
        return thread;
    });
    private MeasurementRepository repository;
    private ShareHost shareHost;
    private RemoteDeviceManager remoteDevices;
    private NsdBrowser browser;
    private List<BatterySample> localSamples = Collections.emptyList();
    private List<BatterySample> displayedSamples = Collections.emptyList();
    private List<RemoteDeviceManager.Device> connectedDevices = Collections.emptyList();
    private boolean remoteMode;
    private boolean turboMode;
    private boolean refreshInFlight;
    private String selectedRemoteKey;
    private long displayedGeneratedAt;
    private long refreshBaselineGeneratedAt;
    private long refreshBaselineSequence;
    private long refreshBaselineRevision;
    private String refreshTargetKey;

    private LinearLayout deviceTabs;
    private TextView freshness;
    private TextView refreshButton;
    private TextView turboButton;
    private TextView statusPill;
    private TextView batteryValue;
    private TextView batteryRate;
    private TextView temperatureValue;
    private TextView temperatureRate;
    private TextView remainingValue;
    private TextView currentValue;
    private TextView voltageValue;
    private TextView chargingValue;
    private TextView thermalValue;
    private TextView shareButton;
    private TrendChartView batteryChart;
    private TrendChartView temperatureChart;

    private final MeasurementRepository.Listener measurementListener = samples -> {
        localSamples = samples;
        if (!remoteMode) showLocal();
        if (!remoteMode && refreshInFlight && refreshTargetKey == null
                && repository.sampleRevision() > refreshBaselineRevision) {
            finishRefresh();
        }
    };

    private final RemoteDeviceManager.Listener remoteDeviceListener =
            new RemoteDeviceManager.Listener() {
                @Override public void onDevicesChanged(List<RemoteDeviceManager.Device> devices) {
                    connectedDevices = devices;
                    rebuildDeviceTabs();
                    if (selectedRemoteKey == null) return;
                    RemoteDeviceManager.Device selected = findDevice(selectedRemoteKey);
                    if (selected == null) {
                        showLocal();
                    } else if (selected.snapshot != null) {
                        showRemote(selected.key, selected.snapshot);
                        if (refreshInFlight && selected.key.equals(refreshTargetKey)
                                && selected.snapshot.freshRequested
                                && selected.snapshot.requestSequence > refreshBaselineSequence) {
                            finishRefresh();
                            if (!selected.snapshot.freshApplied) {
                                Toast.makeText(MainActivity.this,
                                        "5秒制限または保護モードのため直前値を表示しています",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    } else {
                        render();
                    }
                }

                @Override public void onDeviceMessage(String key, String message, boolean terminal) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                    if (terminal && key.equals(selectedRemoteKey)) showLocal();
                }
            };

    private final Runnable freshnessTicker = new Runnable() {
        @Override public void run() {
            updateFreshness();
            mainHandler.postDelayed(this, SamplingPolicy.FRESHNESS_TICK_MILLIS);
        }
    };
    private final Runnable refreshTimeout = this::finishRefresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BatteryRelayApp app = BatteryRelayApp.from(this);
        repository = app.repository();
        shareHost = app.shareHost();
        remoteDevices = app.remoteDevices();
        turboMode = remoteDevices.isTurbo();
        connectedDevices = remoteDevices.devices();
        selectedRemoteKey = remoteDevices.getActiveKey();
        configureWindow();
        setContentView(buildScreen());
        startMonitor();
        maybeRequestNotificationPermission();
        localSamples = repository.snapshot();
        RemoteDeviceManager.Device selected = findDevice(selectedRemoteKey);
        if (selected != null) showRemote(selected.key, selected.snapshot);
        else showLocal();
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.addListener(measurementListener);
        remoteDevices.setListener(remoteDeviceListener);
        remoteDevices.setUiVisible(true);
        remoteDevices.setActive(selectedRemoteKey, turboMode);
        mainHandler.removeCallbacks(freshnessTicker);
        mainHandler.post(freshnessTicker);
        if (browser != null) browser.start();
    }

    @Override
    protected void onStop() {
        repository.removeListener(measurementListener);
        remoteDevices.setListener(null);
        remoteDevices.setUiVisible(false);
        mainHandler.removeCallbacks(freshnessTicker);
        if (browser != null) browser.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(freshnessTicker);
        mainHandler.removeCallbacks(refreshTimeout);
        if (browser != null) browser.stop();
        shareHost.setListener(null);
        uiIo.shutdownNow();
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        int visibility = window.getDecorView().getSystemUiVisibility();
        if (!Ui.isDark(this)) {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(visibility);
    }

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.canvas(this));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int page = Ui.dp(this, 20);
        content.setPadding(page, Ui.dp(this, 18), page, Ui.dp(this, 32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(buildHeader());
        content.addView(buildSourceBar(), marginParams(-1, -2, 0, 22));
        content.addView(buildMetricRow());
        content.addView(buildDetailsCard(), marginParams(-1, -2, 0, 14));
        content.addView(buildChartCard("バッテリー残量", "1分単位・直近30分", true),
                marginParams(-1, -2, 0, 14));
        content.addView(buildChartCard("バッテリー温度", "Android公開値・直近30分", false),
                marginParams(-1, -2, 0, 14));
        content.addView(buildRetentionNote(), marginParams(-1, -2, 0, 16));
        content.addView(buildActions());

        TextView foot = Ui.text(this,
                "温度はCPU温度ではなく、端末が公開するバッテリー温度です。Turboは5秒、通常は15秒。省電力中または重度の熱状態では自動的に60秒へ退避します。",
                11.5f, Ui.subtext(this), false);
        foot.setLineSpacing(0f, 1.25f);
        content.addView(foot, marginParams(-1, -2, 2, 0));
        return root;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, Ui.dp(this, 20));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.app_icon_foreground);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        row.addView(titles, titleParams);
        TextView eyebrow = Ui.text(this, "BATTERY RELAY", 11f, Ui.terracotta(this), true);
        eyebrow.setLetterSpacing(0.12f);
        titles.addView(eyebrow);
        TextView screenTitle = Ui.text(this, "端末コンディション", 24f, Ui.text(this), true);
        screenTitle.setSingleLine(true);
        screenTitle.setAutoSizeTextTypeUniformWithConfiguration(16, 24, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        titles.addView(screenTitle, marginParams(-1, -2, 0, 2));

        statusPill = Ui.text(this, "計測中", 11.5f, Ui.success(this), true);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        statusPill.setBackground(Ui.rounded(this,
                Color.argb(Ui.isDark(this) ? 35 : 22, 65, 130, 78), 99));
        row.addView(statusPill, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private View buildSourceBar() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        deviceTabs = new LinearLayout(this);
        deviceTabs.setOrientation(LinearLayout.HORIZONTAL);
        deviceTabs.setGravity(Gravity.CENTER_VERTICAL);
        scroller.addView(deviceTabs, new HorizontalScrollView.LayoutParams(-2, -2));
        scroller.setMinimumHeight(Ui.dp(this, 48));
        box.addView(scroller, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        freshness = Ui.text(this, "更新中", 12f, Ui.subtext(this), false);
        controls.addView(freshness, new LinearLayout.LayoutParams(0, -2, 1f));

        refreshButton = compactControl("更新", false);
        refreshButton.setContentDescription("表示中の端末を今すぐ更新");
        refreshButton.setOnClickListener(v -> requestImmediateRefresh());
        controls.addView(refreshButton, compactControlParams(0));

        turboButton = compactControl("Turbo 5秒", turboMode);
        turboButton.setContentDescription("Turboモードを切り替え");
        turboButton.setOnClickListener(v -> setTurboMode(!turboMode));
        controls.addView(turboButton, compactControlParams(7));
        box.addView(controls, marginParams(-1, -2, 8, 0));

        rebuildDeviceTabs();
        return box;
    }

    private TextView compactControl(String label, boolean selected) {
        TextView control = Ui.text(this, label, 12.5f, Ui.text(this), true);
        control.setMinHeight(Ui.dp(this, 48));
        Ui.styleChip(control, selected);
        return control;
    }

    private LinearLayout.LayoutParams compactControlParams(int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(Ui.dp(this, leftDp), 0, 0, 0);
        return params;
    }

    private void rebuildDeviceTabs() {
        if (deviceTabs == null) return;
        deviceTabs.removeAllViews();
        TextView local = deviceChip("この端末", selectedRemoteKey == null);
        local.setContentDescription("この端末を表示");
        local.setOnClickListener(v -> showLocal());
        deviceTabs.addView(local, deviceTabParams(0));

        for (RemoteDeviceManager.Device device : connectedDevices) {
            String label = compactDeviceName(device.displayName)
                    + (device.connected ? "" : " …");
            TextView chip = deviceChip(label, device.key.equals(selectedRemoteKey));
            chip.setContentDescription(device.displayName + "を表示。長押しで接続解除");
            chip.setOnClickListener(v -> selectRemote(device.key));
            chip.setOnLongClickListener(v -> {
                remoteDevices.disconnect(device.key);
                Toast.makeText(this, device.displayName + "との接続を解除しました",
                        Toast.LENGTH_SHORT).show();
                return true;
            });
            deviceTabs.addView(chip, deviceTabParams(7));
        }

        TextView add = deviceChip("＋ 端末", false);
        add.setContentDescription("共有中の端末を追加");
        add.setOnClickListener(v -> showDiscoveryDialog());
        deviceTabs.addView(add, deviceTabParams(7));
    }

    private TextView deviceChip(String label, boolean selected) {
        TextView chip = Ui.text(this, label, 12.5f, Ui.text(this), true);
        chip.setSingleLine(true);
        chip.setMinHeight(Ui.dp(this, 48));
        Ui.styleChip(chip, selected);
        return chip;
    }

    private LinearLayout.LayoutParams deviceTabParams(int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(Ui.dp(this, leftDp), 0, 0, 0);
        return params;
    }

    private static String compactDeviceName(String value) {
        String name = value == null ? "共有端末" : value
                .replaceFirst("(?i)^Battery Relay *- *", "").trim();
        int count = name.codePointCount(0, name.length());
        if (count <= 18) return name;
        int end = name.offsetByCodePoints(0, 17);
        return name.substring(0, end) + "…";
    }

    private RemoteDeviceManager.Device findDevice(String key) {
        if (key == null) return null;
        for (RemoteDeviceManager.Device device : connectedDevices) {
            if (key.equals(device.key)) return device;
        }
        return null;
    }

    private void requestImmediateRefresh() {
        if (refreshInFlight) return;
        refreshInFlight = true;
        refreshTargetKey = selectedRemoteKey;
        refreshBaselineGeneratedAt = displayedGeneratedAt;
        refreshBaselineRevision = repository.sampleRevision();
        RemoteDeviceManager.Device selected = findDevice(selectedRemoteKey);
        refreshBaselineSequence = selected == null || selected.snapshot == null ? 0L
                : selected.snapshot.requestSequence;
        if (!displayedSamples.isEmpty()) {
            refreshBaselineGeneratedAt = Math.max(refreshBaselineGeneratedAt,
                    displayedSamples.get(displayedSamples.size() - 1).timestampMillis);
        }
        refreshButton.setText("更新中…");
        refreshButton.setEnabled(false);
        mainHandler.removeCallbacks(refreshTimeout);
        mainHandler.postDelayed(refreshTimeout, 15_000L);
        if (selectedRemoteKey == null) {
            sendMonitorCommand(MonitorService.ACTION_REFRESH, turboMode);
        } else {
            remoteDevices.refresh(selectedRemoteKey);
        }
    }

    private void finishRefresh() {
        refreshInFlight = false;
        refreshTargetKey = null;
        mainHandler.removeCallbacks(refreshTimeout);
        if (refreshButton != null) {
            refreshButton.setText("更新");
            refreshButton.setEnabled(true);
        }
    }

    private void setTurboMode(boolean enabled) {
        turboMode = enabled;
        if (turboButton != null) Ui.styleChip(turboButton, enabled);
        remoteDevices.setTurbo(enabled);
        sendMonitorCommand(MonitorService.ACTION_SET_TURBO, enabled);
        render();
        String message = enabled && isLocalProtectionActive()
                ? remoteMode ? "Turboオン（この端末の省電力・熱保護で受信60秒）"
                : "Turboオン（省電力・重度熱状態では保護60秒）"
                : enabled ? "Turbo：5秒更新" : "標準：15秒更新";
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void sendMonitorCommand(String action, boolean turboValue) {
        Intent intent = new Intent(this, MonitorService.class).setAction(action)
                .putExtra(MonitorService.EXTRA_TURBO, turboValue);
        try {
            startForegroundService(intent);
        } catch (RuntimeException error) {
            finishRefresh();
            Toast.makeText(this, "更新を開始できません: " + safeMessage(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private View buildMetricRow() {
        LinearLayout row = new LinearLayout(this);
        boolean stacked = useStackedLayout();
        row.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        LinearLayout battery = metricCard(true);
        LinearLayout temperature = metricCard(false);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(
                stacked ? -1 : 0, -2, stacked ? 0f : 1f);
        left.setMargins(0, 0, stacked ? 0 : Ui.dp(this, 7), Ui.dp(this, 14));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
                stacked ? -1 : 0, -2, stacked ? 0f : 1f);
        right.setMargins(stacked ? 0 : Ui.dp(this, 7), 0, 0, Ui.dp(this, 14));
        row.addView(battery, left);
        row.addView(temperature, right);
        return row;
    }

    private LinearLayout metricCard(boolean battery) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16));
        card.setBackground(Ui.outlined(this, Ui.card(this), Ui.border(this), 20));
        TextView label = Ui.text(this, battery ? "BATTERY" : "TEMPERATURE", 10.5f,
                battery ? Ui.terracotta(this) : Ui.slate(this), true);
        label.setLetterSpacing(0.08f);
        card.addView(label);
        TextView value = Ui.text(this, battery ? "--%" : "--℃", 38f, Ui.text(this), true);
        value.setSingleLine(true);
        value.setAutoSizeTextTypeUniformWithConfiguration(18, 38, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        value.setTextScaleX(0.94f);
        value.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(value, marginParams(-1, -2, 8, 3));
        TextView rate = Ui.text(this, "計測待ち", 13f,
                battery ? Ui.terracotta(this) : Ui.slate(this), true);
        card.addView(rate);
        if (battery) {
            batteryValue = value;
            batteryRate = rate;
        } else {
            temperatureValue = value;
            temperatureRate = rate;
        }
        return card;
    }

    private View buildDetailsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        card.setBackground(Ui.outlined(this, Ui.card(this), Ui.border(this), 18));
        LinearLayout first = detailRow();
        remainingValue = detailItem(first, "推定残量", "-- mAh");
        currentValue = detailItem(first, "電流", "-- mA");
        voltageValue = detailItem(first, "電圧", "-- V");
        card.addView(first);
        View divider = new View(this);
        divider.setBackgroundColor(Ui.border(this));
        card.addView(divider, marginParams(-1, Ui.dp(this, 1), 10, 10));
        LinearLayout second = detailRow();
        chargingValue = detailItem(second, "電源", "確認中");
        thermalValue = detailItem(second, "熱状態", "確認中");
        card.addView(second);
        return card;
    }

    private LinearLayout detailRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(useStackedLayout() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView detailItem(LinearLayout row, String label, String initial) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(0, 0, Ui.dp(this, 8), 0);
        TextView small = Ui.text(this, label, 10.5f, Ui.subtext(this), false);
        TextView value = Ui.text(this, initial, 13.5f, Ui.text(this), true);
        value.setSingleLine(true);
        cell.addView(small);
        cell.addView(value, marginParams(-1, -2, 4, 0));
        LinearLayout.LayoutParams cellParams = row.getOrientation() == LinearLayout.VERTICAL
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1f);
        if (row.getOrientation() == LinearLayout.VERTICAL) {
            cellParams.setMargins(0, 0, 0, Ui.dp(this, 10));
        }
        row.addView(cell, cellParams);
        return value;
    }

    private View buildChartCard(String title, String note, boolean battery) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 15), Ui.dp(this, 10), Ui.dp(this, 8));
        card.setBackground(Ui.outlined(this, Ui.card(this), Ui.border(this), 20));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(useStackedLayout() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.text(this, title, 15f, Ui.text(this), true);
        head.addView(label, useStackedLayout()
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sub = Ui.text(this, note, 10.5f, Ui.subtext(this), false);
        sub.setGravity(Gravity.END);
        head.addView(sub, useStackedLayout()
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(-2, -2));
        card.addView(head);
        TrendChartView chart = new TrendChartView(this);
        card.addView(chart, new LinearLayout.LayoutParams(-1, Ui.dp(this, 198)));
        if (battery) batteryChart = chart;
        else temperatureChart = chart;
        return card;
    }

    private View buildRetentionNote() {
        LinearLayout note = new LinearLayout(this);
        note.setOrientation(LinearLayout.HORIZONTAL);
        note.setGravity(Gravity.CENTER_VERTICAL);
        note.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        note.setBackground(Ui.rounded(this, Ui.mutedFill(this), 16));
        TextView clock = Ui.text(this, "30", 22f, Ui.terracotta(this), true);
        clock.setGravity(Gravity.CENTER);
        note.addView(clock, new LinearLayout.LayoutParams(Ui.dp(this, 42), -2));
        TextView text = Ui.text(this,
                "画面と共有は直近30分だけ。端末内DBも最大31点に制限し、時計ずれ保護で一時保持した30分外の値は表示・共有しません。",
                12.5f, Ui.text(this), false);
        text.setLineSpacing(0f, 1.18f);
        note.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        return note;
    }

    private View buildActions() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        shareButton = Ui.text(this, "この端末の情報を共有", 15f, Ui.canvas(this), true);
        Ui.styleButton(shareButton, true);
        shareButton.setOnClickListener(v -> showHostDialog());
        box.addView(shareButton, marginParams(-1, Ui.dp(this, 54), 0, 14));
        return box;
    }

    private void showLocal() {
        if (refreshInFlight && refreshTargetKey != null) finishRefresh();
        boolean changed = selectedRemoteKey != null;
        selectedRemoteKey = null;
        remoteMode = false;
        displayedSamples = localSamples;
        displayedGeneratedAt = displayedSamples.isEmpty() ? 0L
                : displayedSamples.get(displayedSamples.size() - 1).timestampMillis;
        if (changed) remoteDevices.setActive(null, turboMode);
        rebuildDeviceTabs();
        render();
    }

    private void selectRemote(String key) {
        RemoteDeviceManager.Device device = findDevice(key);
        if (device == null) return;
        selectedRemoteKey = key;
        remoteDevices.setActive(key, turboMode);
        showRemote(key, device.snapshot);
        if (device.snapshot == null) remoteDevices.refresh(key);
    }

    private void showRemote(String key, RemoteSnapshot snapshot) {
        if (refreshInFlight && (refreshTargetKey == null || !refreshTargetKey.equals(key))) {
            finishRefresh();
        }
        selectedRemoteKey = key;
        remoteMode = true;
        displayedGeneratedAt = snapshot == null ? 0L : snapshot.generatedAt;
        displayedSamples = snapshot == null ? Collections.emptyList() : snapshot.samples;
        rebuildDeviceTabs();
        render();
    }

    private void render() {
        boolean localProtection = isLocalProtectionActive();
        boolean samplingActive = repository.isSamplingActive();
        RemoteDeviceManager.Device selected = remoteMode ? findDevice(selectedRemoteKey) : null;
        boolean remoteConnected = selected != null && selected.connected;
        String status;
        if (!remoteMode) {
            status = !samplingActive ? "停止中"
                    : localProtection ? "保護・60秒"
                    : turboMode ? "Turbo・5秒" : "計測中・15秒";
        } else if (!remoteConnected) {
            status = "再接続中";
        } else if (localProtection) {
            status = "受信保護・60秒";
        } else {
            status = turboMode ? "接続中・5秒" : "接続中・15秒";
        }
        statusPill.setText(status);
        statusPill.setTextColor(!remoteMode && !samplingActive ? Ui.subtext(this)
                : remoteMode ? remoteConnected ? Ui.slate(this) : Ui.subtext(this)
                : Ui.success(this));
        if (shareButton != null) {
            shareButton.setText(shareHost != null && shareHost.isRunning()
                    ? "共有中・設定を開く" : "この端末の情報を共有");
        }

        BatterySample latest = displayedSamples.isEmpty() ? null
                : displayedSamples.get(displayedSamples.size() - 1);
        if (latest == null) {
            batteryValue.setText("--%");
            temperatureValue.setTextSize(38f);
            temperatureValue.setText("--℃");
            batteryRate.setText("計測待ち");
            temperatureRate.setText("計測待ち");
            remainingValue.setText("非対応 / 待機中");
            currentValue.setText(String.format(Locale.JAPAN, "%s %s", "--", "mA"));
            voltageValue.setText("-- V");
            chargingValue.setText("確認中");
            thermalValue.setText("確認中");
        } else {
            batteryValue.setText(latest.levelPercent >= 0
                    ? String.format(Locale.JAPAN, "%d%%", latest.levelPercent) : "取得不可");
            temperatureValue.setTextSize(latest.hasTemperature() ? 38f : 22f);
            temperatureValue.setText(latest.hasTemperature()
                    ? String.format(Locale.JAPAN, "%.1f℃", latest.temperatureC) : "取得不可");
            batteryRate.setText(TrendMath.signedRate(
                    TrendMath.ratePerMinute(displayedSamples, TrendMath.Metric.BATTERY_PERCENT), "%"));
            temperatureRate.setText(TrendMath.signedRate(
                    TrendMath.ratePerMinute(displayedSamples, TrendMath.Metric.TEMPERATURE_C), "℃"));
            remainingValue.setText(latest.hasRemainingMah()
                    ? String.format(Locale.JAPAN, "%,.0f mAh", latest.remainingMah) : "非対応");
            currentValue.setText(latest.hasCurrent()
                    ? String.format(Locale.JAPAN, "%+.0f mA", latest.currentMa) : "非対応");
            voltageValue.setText(latest.voltageMv > 0
                    ? String.format(Locale.JAPAN, "%.2f V", latest.voltageMv / 1000d) : "非対応");
            chargingValue.setText(latest.charging ? "充電中" : "バッテリー");
            thermalValue.setText(thermalLabel(latest.thermalStatus));
        }

        long chartTime = System.currentTimeMillis();
        batteryChart.setData(displayedSamples, TrendMath.Metric.BATTERY_PERCENT,
                Ui.terracotta(this), chartTime);
        temperatureChart.setData(displayedSamples, TrendMath.Metric.TEMPERATURE_C,
                Ui.slate(this), chartTime);
        batteryChart.setContentDescription("バッテリー残量グラフ。現在値 "
                + (latest == null || latest.levelPercent < 0 ? "取得不可"
                : latest.levelPercent + "パーセント") + "。変化率 " + batteryRate.getText());
        temperatureChart.setContentDescription("バッテリー温度グラフ。現在値 "
                + (latest == null || !latest.hasTemperature() ? "取得不可"
                : String.format(Locale.JAPAN, "%.1f度", latest.temperatureC))
                + "。変化率 " + temperatureRate.getText());
        updateFreshness();
    }

    private boolean isLocalProtectionActive() {
        PowerManager power = getSystemService(PowerManager.class);
        boolean powerSave = false;
        try {
            powerSave = power != null && power.isPowerSaveMode();
        } catch (RuntimeException ignored) {}
        BatterySample latest = localSamples.isEmpty() ? null
                : localSamples.get(localSamples.size() - 1);
        return powerSave || latest != null
                && latest.thermalStatus >= SamplingPolicy.THERMAL_BACKOFF_STATUS;
    }

    private void updateFreshness() {
        if (freshness == null) return;
        long base = displayedGeneratedAt;
        if (!displayedSamples.isEmpty()) {
            base = Math.max(base, displayedSamples.get(displayedSamples.size() - 1).timestampMillis);
        }
        if (base <= 0) {
            freshness.setText("データ待機中");
            return;
        }
        long now = System.currentTimeMillis();
        if (base > now && base - now > FUTURE_CLOCK_TOLERANCE_MILLIS) {
            freshness.setText("端末時刻に差があります");
            return;
        }
        long seconds = Math.max(0L, (now - base) / 1000L);
        if (seconds < 5) freshness.setText("たった今更新");
        else if (seconds < 60) freshness.setText(String.format(Locale.JAPAN, "%d秒前に更新", seconds));
        else freshness.setText(String.format(Locale.JAPAN, "%d分前に更新", seconds / 60));
    }

    private void showHostDialog() {
        Dialog dialog = baseDialog();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        LinearLayout content = dialogContent();
        TextView title = Ui.text(this, "この端末から共有", 21f, Ui.text(this), true);
        content.addView(title);
        TextView description = Ui.text(this,
                "もう1台も同じWi‑Fiに接続し、画面上部の「＋ 端末」からこの端末を選びます。閉じても共有は継続します。",
                13f, Ui.subtext(this), false);
        description.setLineSpacing(0f, 1.2f);
        content.addView(description, marginParams(-1, -2, 8, 18));

        TextView codeLabel = Ui.text(this, "128ビット共有キー", 11f, Ui.subtext(this), true);
        codeLabel.setGravity(Gravity.CENTER);
        content.addView(codeLabel);
        TextView code = Ui.text(this, "開始中…", 20f, Ui.text(this), true);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(0.14f);
        code.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        code.setBackground(Ui.rounded(this, Ui.mutedFill(this), 16));
        content.addView(code, marginParams(-1, Ui.dp(this, 78), 6, 12));
        TextView state = Ui.text(this, "暗号化共有を準備しています", 12.5f, Ui.subtext(this), false);
        state.setGravity(Gravity.CENTER);
        content.addView(state, marginParams(-1, -2, 0, 16));

        TextView refresh = Ui.text(this, "新しい共有キーを発行", 14f, Ui.text(this), true);
        Ui.styleButton(refresh, false);
        refresh.setOnClickListener(v -> shareHost.refreshPairingCode());
        content.addView(refresh, marginParams(-1, Ui.dp(this, 50), 0, 8));

        TextView closeKeep = Ui.text(this, "共有を継続して閉じる", 14f, Ui.text(this), true);
        Ui.styleButton(closeKeep, false);
        closeKeep.setOnClickListener(v -> dialog.dismiss());
        content.addView(closeKeep, marginParams(-1, Ui.dp(this, 50), 0, 8));

        TextView stop = Ui.text(this, "共有を停止", 14f, Ui.canvas(this), true);
        Ui.styleButton(stop, true);
        stop.setOnClickListener(v -> {
            shareHost.stop();
            dialog.dismiss();
            if (shareButton != null) shareButton.setText("この端末の情報を共有");
            Toast.makeText(this, "共有を停止し、接続鍵を破棄しました", Toast.LENGTH_SHORT).show();
        });
        content.addView(stop, marginParams(-1, Ui.dp(this, 50), 0, 10));
        TextView privacy = Ui.text(this,
                "共有キーは通信に平文送信されません。コピー時は機密扱いとなり60秒で消去します。共有停止で接続鍵を全破棄します。",
                11f, Ui.subtext(this), false);
        privacy.setGravity(Gravity.CENTER);
        content.addView(privacy);
        dialog.setContentView(content);
        sizeDialog(dialog);

        shareHost.setListener((running, pairingCode, viewers, error) -> {
            if (!dialog.isShowing()) return;
            if (running && pairingCode.length() == 26) {
                if (shareButton != null) shareButton.setText("共有中・設定を開く");
                code.setText(groupSecret(pairingCode));
                code.setOnClickListener(v -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    if (clipboard != null) {
                        ClipData clip = ClipData.newPlainText("Battery Relay共有キー", pairingCode);
                        PersistableBundle extras = new PersistableBundle();
                        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                        clip.getDescription().setExtras(extras);
                        clipboard.setPrimaryClip(clip);
                        mainHandler.postDelayed(() -> clearSecretClipboard(clipboard, pairingCode), 60_000L);
                        Toast.makeText(this, "共有キーをコピーしました（60秒で消去）", Toast.LENGTH_SHORT).show();
                    }
                });
                state.setText(error != null ? error : viewers > 0
                        ? viewers + "台が閲覧中" : "待機中・5分ごとにコード更新");
            } else if (error != null) {
                code.setText("開始失敗");
                state.setText(error);
            }
        });
        dialog.setOnDismissListener(ignored -> shareHost.setListener(null));
        dialog.show();
        sizeDialog(dialog);

        if (!shareHost.isRunning()) {
            uiIo.execute(() -> {
                try {
                    shareHost.start();
                } catch (IOException | GeneralSecurityException error) {
                    mainHandler.post(() -> {
                        if (!isDestroyed() && dialog.isShowing()) {
                            state.setText(String.format(Locale.JAPAN,
                                    "共有を開始できません: %s", safeMessage(error)));
                        }
                    });
                }
            });
        }
    }

    private void showDiscoveryDialog() {
        if (browser != null) {
            Toast.makeText(this, "端末を検索中です", Toast.LENGTH_SHORT).show();
            return;
        }
        Dialog dialog = baseDialog();
        LinearLayout content = dialogContent();
        content.addView(Ui.text(this, "端末を追加", 21f, Ui.text(this), true));
        TextView description = Ui.text(this,
                "共有元と同じWi‑Fiに接続してください。端末名は自動で見つかります。",
                13f, Ui.subtext(this), false);
        content.addView(description, marginParams(-1, -2, 8, 15));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        TextView waiting = Ui.text(this, "同じWi‑Fi上を検索中…", 14f, Ui.subtext(this), false);
        waiting.setGravity(Gravity.CENTER);
        list.addView(waiting, marginParams(-1, -2, 18, 18));
        content.addView(list, new LinearLayout.LayoutParams(-1, -2));
        TextView close = Ui.text(this, "閉じる", 14f, Ui.text(this), true);
        Ui.styleButton(close, false);
        close.setOnClickListener(v -> dialog.dismiss());
        content.addView(close, marginParams(-1, Ui.dp(this, 50), 8, 0));
        dialog.setContentView(content);
        dialog.setOnDismissListener(ignored -> {
            if (browser != null) browser.stop();
            browser = null;
        });
        dialog.show();
        sizeDialog(dialog);

        browser = new NsdBrowser(this, new NsdBrowser.Listener() {
            @Override public void onPeersChanged(List<DiscoveredPeer> peers) {
                if (!dialog.isShowing()) return;
                remoteDevices.updateDiscoveredPeers(peers);
                String ownId = shareHost.getShareId();
                ArrayList<DiscoveredPeer> visible = new ArrayList<>();
                for (DiscoveredPeer peer : peers) {
                    if (ownId == null || !ownId.equals(peer.shareId)) visible.add(peer);
                }
                list.removeAllViews();
                if (visible.isEmpty()) {
                    TextView empty = Ui.text(MainActivity.this, "共有中の端末を探しています…", 14f,
                            Ui.subtext(MainActivity.this), false);
                    empty.setGravity(Gravity.CENTER);
                    list.addView(empty, marginParams(-1, -2, 18, 18));
                    return;
                }
                for (DiscoveredPeer peer : visible) {
                    boolean alreadyConnected = remoteDevices.contains(peer.stableKey());
                    String itemLabel = peer.serviceName + (alreadyConnected ? "（接続済み）" : "");
                    TextView item = Ui.text(MainActivity.this, itemLabel, 14f,
                            Ui.text(MainActivity.this), true);
                    Ui.styleButton(item, false);
                    item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    item.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_media_next, 0);
                    item.setOnClickListener(v -> {
                        dialog.dismiss();
                        if (alreadyConnected) selectRemote(peer.stableKey());
                        else showPairingDialog(peer);
                    });
                    list.addView(item, marginParams(-1, Ui.dp(MainActivity.this, 54), 0, 8));
                }
            }

            @Override public void onDiscoveryError(String message) {
                if (!dialog.isShowing()) return;
                list.removeAllViews();
                TextView error = Ui.text(MainActivity.this, message, 14f,
                        Ui.subtext(MainActivity.this), false);
                error.setGravity(Gravity.CENTER);
                list.addView(error, marginParams(-1, -2, 18, 18));
            }
        });
        browser.start();
    }

    private void showPairingDialog(DiscoveredPeer peer) {
        Dialog dialog = baseDialog();
        LinearLayout content = dialogContent();
        content.addView(Ui.text(this, "共有キーを入力", 21f, Ui.text(this), true));
        TextView device = Ui.text(this, peer.serviceName, 13f, Ui.subtext(this), false);
        content.addView(device, marginParams(-1, -2, 5, 14));
        EditText input = new EditText(this);
        input.setHint("26文字の共有キー");
        input.setTextSize(20f);
        input.setTextColor(Ui.text(this));
        input.setHintTextColor(Ui.subtext(this));
        input.setGravity(Gravity.CENTER);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setSaveEnabled(false);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        input.setBackground(Ui.outlined(this, Ui.card(this), Ui.border(this), 14));
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        content.addView(input, marginParams(-1, Ui.dp(this, 64), 0, 14));
        TextView connect = Ui.text(this, "暗号化して接続", 14f, Ui.canvas(this), true);
        Ui.styleButton(connect, true);
        connect.setOnClickListener(v -> {
            String code = input.getText().toString().toUpperCase(Locale.ROOT)
                    .replaceAll("[^2-9A-Z]", "");
            if (!code.matches("[2-9A-HJ-NP-Z]{26}")) {
                input.setError("表示された26文字を入力してください");
                return;
            }
            dialog.dismiss();
            connectRemote(peer, code);
        });
        content.addView(connect, marginParams(-1, Ui.dp(this, 52), 0, 8));
        TextView cancel = Ui.text(this, "キャンセル", 14f, Ui.text(this), true);
        Ui.styleButton(cancel, false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        content.addView(cancel, marginParams(-1, Ui.dp(this, 50), 0, 0));
        dialog.setContentView(content);
        dialog.setOnDismissListener(ignored -> input.getText().clear());
        dialog.show();
        sizeDialog(dialog);
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void connectRemote(DiscoveredPeer peer, String code) {
        Toast.makeText(this, "暗号化接続を確認しています…", Toast.LENGTH_SHORT).show();
        selectedRemoteKey = peer.stableKey();
        remoteDevices.connect(peer, code, turboMode);
        showRemote(selectedRemoteKey, null);
    }

    private static String groupSecret(String secret) {
        StringBuilder grouped = new StringBuilder(secret.length() + 6);
        for (int i = 0; i < secret.length(); i++) {
            if (i > 0 && i % 4 == 0) grouped.append(' ');
            grouped.append(secret.charAt(i));
        }
        return grouped.toString();
    }

    private static void clearSecretClipboard(ClipboardManager clipboard, String expected) {
        try {
            if (!clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null
                    || clipboard.getPrimaryClip().getItemCount() == 0) return;
            CharSequence current = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (expected.contentEquals(current)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip();
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } catch (RuntimeException ignored) {}
    }

    private Dialog baseDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    private LinearLayout dialogContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 20));
        content.setBackground(Ui.rounded(this, Ui.card(this), 24));
        return content;
    }

    private void sizeDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(window.getAttributes());
        params.width = Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32),
                Ui.dp(this, 520));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setAttributes(params);
        window.setBackgroundDrawableResource(android.R.color.transparent);
    }

    private void startMonitor() {
        Intent service = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_START);
        try {
            startForegroundService(service);
        } catch (RuntimeException error) {
            Toast.makeText(this, "継続計測を開始できません: " + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private static String thermalLabel(int status) {
        switch (status) {
            case 0: return "正常";
            case 1: return "軽度";
            case 2: return "中度";
            case 3: return "重度";
            case 4: return "危険";
            case 5: return "緊急";
            case 6: return "停止域";
            default: return "非対応";
        }
    }

    private boolean useStackedLayout() {
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        return widthDp < 360f || getResources().getConfiguration().fontScale >= 1.3f;
    }

    private LinearLayout.LayoutParams marginParams(int width, int height, int topDp, int bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(0, Ui.dp(this, topDp), 0, Ui.dp(this, bottomDp));
        return params;
    }

    private static String safeMessage(Throwable error) {
        String text = error.getMessage();
        return text == null || text.trim().isEmpty() ? error.getClass().getSimpleName() : text;
    }
}
