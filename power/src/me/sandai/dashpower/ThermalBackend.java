/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import static me.sandai.dashpower.ThermalServiceContract.SERVICE_NAME;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.camera2.CameraManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.TelephonyManager;
import android.util.Slog;
import android.view.Display;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/** Configuration and request state are guarded by this; queries/sysfs use the thermal worker. */
final class ThermalBackend extends IDashThermalService.Stub {
    private static final String TAG = "DashThermal";
    private static final int WRITE_RETRY_MS = 1000;
    private static final File NODE = new File("/sys/class/thermal/thermal_message/sconfig");
    private static final File LOW_TEMP_CHARGE =
            new File("/sys/class/power_supply/battery/extreme_cold_chg");
    private static final File REVERSE_CHARGE =
            new File("/sys/class/power_supply/usb/otg_enable");
    private static final String POWER_KEEPER_PACKAGE = "com.miui.powerkeeper";
    private final Context context;
    private final Handler worker;
    private final MtkAppListClient appList;
    private final MtkThermalNotifier thermalNotifier;
    private final MtkPowerClient power;
    private final GamingPowerPolicy gamingPower;
    private final Runnable gamingRefresh = this::refreshGaming;
    private final AppModePolicy appMode;
    private final Runnable appModeRefresh = this::refreshAppMode;
    private final UserManager users;
    private final IntFunction<ThermalConfigStore> stores;
    private final Map<String, String> groups = new HashMap<>();
    private final Map<Integer, Config> configs = new HashMap<>();
    private final Map<Integer, String> persistenceErrors = new HashMap<>();
    private final ThermalRequestState requests = new ThermalRequestState();
    private final Runnable[] retries = new Runnable[ThermalRequestState.RETRY_COUNT];
    private final AtomicBoolean lifecycleCheckQueued = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final Runnable refresh = this::recompute;
    private final Runnable writeRetry = this::retryFailedWrite;
    private final Set<String> unavailableCameras = new HashSet<>();
    private final UEventObserver powerSupplyObserver = new UEventObserver() {
        @Override public void onUEvent(UEventObserver.UEvent event) { changed(); }
    };
    private final CameraManager.AvailabilityCallback cameraAvailability =
            new CameraManager.AvailabilityCallback() {
                @Override public void onCameraAvailable(String cameraId) {
                    synchronized (ThermalBackend.this) {
                        unavailableCameras.remove(cameraId);
                        if (unavailableCameras.isEmpty()) clearCameraRecordStateLocked();
                    }
                }

                @Override public void onCameraUnavailable(String cameraId) {
                    synchronized (ThermalBackend.this) {
                        unavailableCameras.add(cameraId);
                    }
                }
            };
    private volatile int currentUser;
    private volatile boolean ready;
    private boolean forceNextRequest;
    private boolean writeRetryConsumed;
    private PowerState powerState;
    private String lifecycleError = "not sampled";
    private String watcherError = "not started";
    private String cameraWatcherError = "not started";
    private int foregroundUser = -1;
    private String foregroundPackage = "";
    private String reason = "starting";
    private String error = "";
    private String eventError = "not sampled";
    private int scenario;
    private int cameraElement = ThermalScenarioPolicy.OFF;
    private int cameraUser = -1;
    private boolean offHook;
    private boolean lowTempCharge;
    private boolean reverseCharge;

    private static final class Config {
        boolean enabled = true;
        boolean performanceMode;
        final Map<String, Integer> overrides = new HashMap<>();

        Config copy() {
            Config copy = new Config();
            copy.enabled = enabled;
            copy.performanceMode = performanceMode;
            copy.overrides.putAll(overrides);
            return copy;
        }
    }

    // Policies belong to the worker. Binder diagnostics only see committed, immutable snapshots.
    private static final class PowerState {
        final long gpuUntil;
        final int gpuProfile;
        final boolean gpuRestorePending;
        final String gpuError;
        final long appUntil;
        final long appSettlingUntil;
        final int appHint;
        final int appPid;
        final String appError;

        PowerState(GamingPowerPolicy gpu, AppModePolicy app) {
            gpuUntil = gpu.activeUntil();
            gpuProfile = gpu.activeProfile();
            gpuRestorePending = gpu.restorePending();
            gpuError = gpu.error();
            appUntil = app.activeUntil();
            appSettlingUntil = app.settlingUntil();
            appHint = app.activeHint();
            appPid = app.activePid();
            appError = app.error();
        }
    }

    ThermalBackend(Context context, Handler worker) {
        this(context, worker, user -> new ThermalConfigStore(
                new File(Environment.getDataSystemDeDirectory(user), "dash-thermal.json").toPath()));
    }

    ThermalBackend(Context context, Handler worker, IntFunction<ThermalConfigStore> stores) {
        this.context = context;
        this.worker = worker;
        appList = new MtkAppListClient(worker, this::changed);
        thermalNotifier = new MtkThermalNotifier(context.getSystemService(PowerManager.class));
        power = new MtkPowerClient(worker, this::powerDisconnected);
        gamingPower = new GamingPowerPolicy(new GamingPowerPolicy.Transport() {
            @Override public int acquire(int durationMs, int[] resources) throws Exception {
                return power.acquireResources(durationMs, resources);
            }
            @Override public boolean release(int token) throws Exception {
                return power.releaseResources(token);
            }
        }, new GptStateIo(new File(Environment.getDataSystemDirectory(),
                "dash-gpt-state").toPath()), SystemClock::uptimeMillis);
        appMode = new AppModePolicy(new AppModePolicy.Transport() {
            @Override public int acquire(int hint, int pid, int durationMs) throws Exception {
                return power.acquireAppMode(hint, pid, durationMs);
            }
            @Override public boolean release(int token) throws Exception {
                return power.releaseResources(token);
            }
        }, SystemClock::uptimeMillis);
        powerState = new PowerState(gamingPower, appMode);
        this.stores = stores;
        users = context.getSystemService(UserManager.class);
    }

    void start() {
        worker.post(() -> {
            updatePower(0, 0, 0, () -> true);
            appList.start();
            thermalNotifier.start();
            LocalServices.getService(ActivityTaskManagerInternal.class)
                    .registerResumedActivityListener(appList::changed);
            startLifecycleObserver();
            synchronized (this) { sampleLifecycle(); }
            try {
                JSONObject root = new JSONObject(Files.readString(
                        new File("/system_ext/etc/dash-power/thermal-packages.json").toPath()));
                if (root.getInt("version") != 1) throw new IllegalStateException("Default schema");
                JSONObject entries = root.getJSONObject("packages");
                synchronized (this) {
                    for (Iterator<String> it = entries.keys(); it.hasNext();) {
                        String pkg = it.next();
                        groups.put(pkg, entries.getString(pkg));
                    }
                }
                currentUser = ActivityManager.getCurrentUser();
                ActivityTaskManager.getInstance().registerTaskStackListener(new TaskStackListener() {
                    @Override public void onTaskStackChanged() { changed(); }
                    @Override public void onTaskMovedToFront(ActivityManager.RunningTaskInfo t) {
                        changed();
                    }
                    @Override public void onTaskFocusChanged(int taskId, boolean focused) { changed(); }
                    @Override public void onTaskRemoved(int taskId) { changed(); }
                    @Override public void onTaskCreated(int taskId, android.content.ComponentName c) {
                        changed();
                    }
                    @Override public void onActivityPinned(String pkg, int userId, int taskId,
                            int stackId) { changed(); }
                    @Override public void onActivityUnpinned() { changed(); }
                });
                IntentFilter states = new IntentFilter();
                states.addAction(Intent.ACTION_SCREEN_ON);
                states.addAction(Intent.ACTION_SCREEN_OFF);
                states.addAction(Intent.ACTION_USER_PRESENT);
                states.addAction(Intent.ACTION_USER_UNLOCKED);
                states.addAction(Intent.ACTION_USER_STOPPED);
                states.addAction(Intent.ACTION_USER_REMOVED);
                states.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
                context.registerReceiverAsUser(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (Intent.ACTION_USER_REMOVED.equals(i.getAction())
                                || Intent.ACTION_USER_STOPPED.equals(i.getAction())) {
                            synchronized (ThermalBackend.this) {
                                int affectedUser = i.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                                if (Intent.ACTION_USER_REMOVED.equals(i.getAction())) {
                                    configs.remove(affectedUser);
                                    persistenceErrors.remove(affectedUser);
                                }
                                if (cameraUser == affectedUser) clearCameraRecordStateLocked();
                            }
                        }
                        changed();
                    }
                }, UserHandle.ALL, states, null, worker);
                IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
                packages.addDataScheme("package");
                context.registerReceiverAsUser(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (!i.getBooleanExtra(Intent.EXTRA_REPLACING, false) && i.getData() != null) {
                            int uid = i.getIntExtra(Intent.EXTRA_UID, -1);
                            if (uid >= 0) removePackage(UserHandle.getUserId(uid),
                                    i.getData().getSchemeSpecificPart());
                        }
                    }
                }, UserHandle.ALL, packages, null, worker);
                powerSupplyObserver.startObserving("SUBSYSTEM=power_supply");
                CameraManager cameras = context.getSystemService(CameraManager.class);
                if (cameras == null) {
                    synchronized (this) { cameraWatcherError = "camera service absent"; }
                } else {
                    try {
                        cameras.registerAvailabilityCallback(cameraAvailability, worker);
                        synchronized (this) { cameraWatcherError = ""; }
                    } catch (RuntimeException e) {
                        synchronized (this) { cameraWatcherError = e.toString(); }
                    }
                }
                context.getSystemService(KeyguardManager.class)
                        .addKeyguardLockedStateListener(worker::post, locked -> changed());
                synchronized (this) {
                    // Request normal before tracking tasks, if the daemon is running.
                    ready = true;
                    requestProfile(0, true);
                }
                changed();
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    synchronized void switching(int userId) {
        currentUser = userId;
        if (cameraUser >= 0 && cameraUser != userId) {
            UserInfo parent = users.getProfileParent(cameraUser);
            if (parent == null || parent.id != userId) clearCameraRecordStateLocked();
        }
        changed();
    }

    void changed() {
        generation.incrementAndGet();
        worker.removeCallbacks(refresh);
        worker.post(refresh);
    }

    private void recompute() {
        if (!ready) return;
        long observed = generation.get();
        BooleanSupplier current = () -> observed == generation.get();
        int gamingProfile = 0;
        int appPid = 0;
        int appHint = 0;
        try {
            int user = currentUser;
            int appUser = -1;
            String pkg = "";
            ComponentName focused = null;
            String why = "no-focused-app";
            boolean interactive = context.getSystemService(PowerManager.class).isInteractive();
            if (!interactive) {
                why = "screen-off";
            } else if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                why = "locked";
            } else if (!users.isUserUnlocked(user)) {
                why = "user-locked";
            } else {
                // RunningTasks puts the focused, visibility-requested leaf first on this display.
                for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getInstance()
                        .getTasks(1, false, false, Display.DEFAULT_DISPLAY)) {
                    if (!isForegroundTask(task)) continue;
                    UserInfo parent = users.getProfileParent(task.userId);
                    if (task.userId != user && (parent == null || parent.id != user)) continue;
                    if (!users.isUserUnlocked(task.userId)) continue;
                    pkg = task.topActivity.getPackageName();
                    focused = task.topActivity;
                    appUser = task.userId;
                    why = "automatic";
                }
            }
            StringBuilder eventFailures = new StringBuilder();
            boolean sampledOffHook = sampleOffHook(eventFailures);
            boolean sampledLowTemp = readEventFlag(LOW_TEMP_CHARGE, eventFailures);
            boolean sampledReverse = readEventFlag(REVERSE_CHARGE, eventFailures);
            ApplicationInfo metadata = null;
            if (appUser >= 0) {
                try {
                    metadata = context.getPackageManager().getApplicationInfoAsUser(pkg, 0, appUser);
                } catch (PackageManager.NameNotFoundException e) {
                    appendEventFailure(eventFailures, "app removed", e);
                    appUser = -1;
                    pkg = "";
                    why = "no-focused-app";
                } catch (RuntimeException e) {
                    appendEventFailure(eventFailures, "app metadata", e);
                    // Classification is only needed for frame mode. Keep normal thermal policy
                    // for the app if focus still matches; never act on a superseded foreground.
                    if (!stillFocused(appUser, focused)) {
                        appUser = -1;
                        pkg = "";
                        why = "no-focused-app";
                    }
                }
            }
            synchronized (this) {
                if (!current.getAsBoolean()) return;
                int target = 0;
                int selectedScenario = 0;
                boolean userEnabled = config(user).enabled;
                Config appConfig = userEnabled && appUser >= 0 ? config(appUser) : null;
                if (!userEnabled) {
                    why = "disabled";
                } else if (appConfig != null) {
                    if (!appConfig.enabled) {
                        why = "profile-disabled";
                    } else {
                        Integer override = appConfig.overrides.get(pkg);
                        if (override != null) {
                            target = override;
                            selectedScenario = -1;
                            why = "override";
                        } else {
                            String group = groups.getOrDefault(pkg, "unclassified");
                            boolean performance = !"unclassified".equals(group)
                                    && config(modeUser(appUser)).performanceMode;
                            int camera = cameraUser == appUser
                                    ? cameraElement : ThermalScenarioPolicy.OFF;
                            ThermalScenarioPolicy.Result selected = ThermalScenarioPolicy.select(
                                    group, pkg, interactive, sampledOffHook, camera,
                                    performance, sampledLowTemp, sampledReverse);
                            selectedScenario = selected.scenario;
                            target = selected.profile;
                        }
                    }
                } else {
                    // Preserve normal when idle while allowing global stock event scenarios.
                    ThermalScenarioPolicy.Result selected = ThermalScenarioPolicy.select(
                            "unclassified", "", interactive, sampledOffHook,
                            ThermalScenarioPolicy.OFF,
                            false, sampledLowTemp, sampledReverse);
                    selectedScenario = selected.scenario;
                    target = selected.profile;
                }
                foregroundPackage = pkg;
                foregroundUser = appUser;
                reason = why;
                scenario = selectedScenario;
                offHook = sampledOffHook;
                lowTempCharge = sampledLowTemp;
                reverseCharge = sampledReverse;
                if (!cameraWatcherError.isEmpty()) {
                    appendEventFailure(eventFailures, "camera", cameraWatcherError);
                }
                eventError = eventFailures.toString();
                boolean force = forceNextRequest;
                forceNextRequest = false;
                if (!requestProfile(target, force)) return;
                if (appConfig != null && appConfig.enabled
                        && requests.requested() == target && requests.running()) {
                    gamingProfile = GamingPowerPolicy.level(target);
                    if (metadata != null) {
                        appHint = AppModePolicy.select(
                                metadata.category == ApplicationInfo.CATEGORY_GAME,
                                defaultProfile(pkg, false),
                                appConfig.overrides.getOrDefault(pkg, ThermalProfiles.AUTO));
                        if (appHint != 0) appPid = appList.resumedPid(focused, metadata.uid);
                    }
                }
                error = "";
            }
        } catch (Exception e) {
            gamingProfile = 0;
            appPid = 0;
            appHint = 0;
            synchronized (this) {
                if (current.getAsBoolean()) fail(e);
            }
        } finally {
            updatePower(gamingProfile, appHint, appPid, current);
        }
    }

    private boolean stillFocused(int user, ComponentName component) {
        try {
            for (ActivityManager.RunningTaskInfo task : ActivityTaskManager.getInstance()
                    .getTasks(1, false, false, Display.DEFAULT_DISPLAY)) {
                if (isForegroundTask(task) && task.userId == user
                        && component.equals(task.topActivity)) return true;
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Cannot verify current thermal foreground", e);
        }
        return false;
    }

    private void updatePower(int profile, int hint, int pid, BooleanSupplier current) {
        // Serialize resource releases/acquires on the worker, never under the configuration lock.
        // Each policy rechecks generation before acquire if release or preparation took time.
        if (current.getAsBoolean()) {
            worker.removeCallbacks(gamingRefresh);
            long delay = gamingPower.update(profile, current);
            if (delay > 0) worker.postDelayed(gamingRefresh, delay);
        }
        if (current.getAsBoolean()) {
            worker.removeCallbacks(appModeRefresh);
            long delay = appMode.update(hint, pid, current);
            if (delay > 0) worker.postDelayed(appModeRefresh, delay);
        }
        publishPowerState();
    }

    private synchronized void publishPowerState() {
        powerState = new PowerState(gamingPower, appMode);
    }

    private void refreshGaming() {
        if (ready) recompute();
        else updatePower(0, 0, 0, () -> true);
    }

    private void refreshAppMode() {
        if (ready) recompute();
        else updatePower(0, 0, 0, () -> true);
    }

    private void powerDisconnected() {
        Slog.w(TAG, "MTK power disconnected");
        appMode.disconnected();
        gamingPower.disconnected();
        publishPowerState();
        // connect() can discover a dead owner during an update: re-query without re-entering it.
        changed();
    }

    // Only a failed sconfig write consumes this one-shot retry. Normal fallback does not rearm it.
    private boolean requestProfile(int target, boolean force) {
        try {
            writeProfile(target, force);
        } catch (IOException e) {
            fail(e);
            if (requests.running() && !writeRetryConsumed) {
                writeRetryConsumed = true;
                worker.postDelayed(writeRetry, WRITE_RETRY_MS);
            }
            return false;
        }
        if (requests.running() && requests.requested() == target) resetWriteRetry();
        return true;
    }

    private void retryFailedWrite() {
        synchronized (this) {
            sampleLifecycle();
            if (!requests.running() || !writeRetryConsumed) return;
            forceNextRequest = true;
        }
        recompute();
    }

    private void resetWriteRetry() {
        worker.removeCallbacks(writeRetry);
        writeRetryConsumed = false;
    }

    private synchronized void fail(Exception e) {
        error = e.toString();
        reason = "error";
        Slog.e(TAG, "Thermal request failed; requesting normal", e);
        try {
            writeProfile(0, true);
        } catch (Exception recovery) {
            error += "; normal request failed: " + recovery;
        }
    }

    static boolean isForegroundTask(ActivityManager.RunningTaskInfo task) {
        return task.isFocused && task.isVisibleRequested && task.topActivity != null;
    }

    private boolean sampleOffHook(StringBuilder failures) {
        try {
            TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
            if (telephony == null) throw new IllegalStateException("telephony service absent");
            return telephony.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK;
        } catch (RuntimeException e) {
            appendEventFailure(failures, "call", e);
            return false;
        }
    }

    private static boolean readEventFlag(File node, StringBuilder failures) {
        try {
            String value = Files.readString(node.toPath()).trim();
            if ("0".equals(value)) return false;
            if ("1".equals(value)) return true;
            throw new IllegalStateException("unexpected value " + value);
        } catch (Exception e) {
            appendEventFailure(failures, node.getName(), e);
            return false;
        }
    }

    private static void appendEventFailure(StringBuilder failures, String source, Exception e) {
        appendEventFailure(failures, source, e.toString());
    }

    private static void appendEventFailure(StringBuilder failures, String source, String detail) {
        if (failures.length() != 0) failures.append("; ");
        failures.append(source).append(": ").append(detail);
    }

    // Called with this held. Camera availability is the authoritative crash/close fallback.
    private void clearCameraRecordStateLocked() {
        if (cameraElement == ThermalScenarioPolicy.OFF && cameraUser == -1) return;
        cameraElement = ThermalScenarioPolicy.OFF;
        cameraUser = -1;
        changed();
    }

    private void startLifecycleObserver() {
        synchronized (this) { watcherError = ""; }
        Thread observer = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                long sample = ThermalLifecycle.sample();
                while (true) {
                    if (lifecycleCheckQueued.compareAndSet(false, true)) {
                        worker.post(() -> {
                            lifecycleCheckQueued.set(false);
                            synchronized (ThermalBackend.this) { sampleLifecycle(); }
                        });
                    }
                    // No backend lock is held across the native futex wait.
                    ThermalLifecycle.awaitChange(sample);
                    sample = ThermalLifecycle.sample();
                }
            } catch (Exception e) {
                worker.post(() -> {
                    synchronized (ThermalBackend.this) {
                        watcherError = "Lifecycle observer stopped: " + e;
                        sampleLifecycle();
                        Slog.e(TAG, watcherError, e);
                    }
                });
                // Stop on failure: unavailable native access must not become a retry spin.
            }
        }, "DashThermalLifecycle");
        observer.setDaemon(true);
        observer.start();
    }

    // Called only on the thermal worker, with this held.
    private long sampleLifecycle() {
        long sample = -1;
        try {
            sample = ThermalLifecycle.sample();
            lifecycleError = !ThermalLifecycle.isPresent(sample) ? "daemon property absent/inaccessible"
                    : !ThermalLifecycle.isRunning(sample) ? "daemon not running" : "";
        } catch (Exception e) {
            lifecycleError = e.toString();
        }
        if (requests.observe(sample)) lifecycleChanged();
        return sample;
    }

    private void lifecycleChanged() {
        resetWriteRetry();
        forceNextRequest = false;
        for (Runnable retry : retries) {
            if (retry != null) worker.removeCallbacks(retry);
        }
        if (requests.running()) {
            long epoch = requests.epoch();
            for (int i = 0; i < retries.length; i++) {
                final int index = i;
                retries[i] = () -> {
                    synchronized (ThermalBackend.this) {
                        sampleLifecycle();
                        if (!requests.claimRetry(epoch, index)) return;
                        // Retain the force if a concurrent policy event invalidates the query.
                        forceNextRequest = true;
                    }
                    recompute();
                };
                worker.postDelayed(retries[i], ThermalRequestState.retryDelayMillis(i));
            }
        }
        changed();
    }

    private void resync() {
        synchronized (this) {
            sampleLifecycle();
            requests.resync();
            lifecycleChanged();
        }
    }

    private void writeProfile(int target, boolean force) throws IOException {
        sampleLifecycle();
        if (!requests.shouldSubmit(target, force)) return;
        long epoch = requests.epoch();
        long afterSample;
        try (FileOutputStream out = new FileOutputStream(NODE)) {
            out.write((target + "\n").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            requests.failed(epoch);
            throw e;
        } finally {
            afterSample = sampleLifecycle();
        }
        if (requests.submitted(epoch, target, afterSample)) {
            Slog.i(TAG, "request=" + target + " scenario=" + scenario + " reason=" + reason
                    + " user=" + foregroundUser + " package=" + foregroundPackage);
        }
    }

    private int defaultProfile(String pkg, boolean performance) {
        String group = groups.getOrDefault(pkg, "unclassified");
        return ThermalScenarioPolicy.select(group, pkg, true, false, ThermalScenarioPolicy.OFF,
                performance && !"unclassified".equals(group), false, false).profile;
    }

    private Config config(int user) throws Exception {
        Config config = configs.get(user);
        if (config != null) return config;
        config = new Config();
        byte[] bytes = stores.apply(user).read();
        if (bytes != null) {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.getInt("version") != 1) throw new IllegalStateException("User schema");
            config.enabled = root.getBoolean("enabled");
            config.performanceMode = root.optBoolean("performanceMode", false);
            JSONObject overrides = root.getJSONObject("overrides");
            for (Iterator<String> it = overrides.keys(); it.hasNext();) {
                String pkg = it.next();
                int id = overrides.getInt(pkg);
                if (!ThermalProfiles.selectable(id) || id == ThermalProfiles.AUTO) {
                    throw new IllegalStateException("Invalid saved profile " + id);
                }
                config.overrides.put(pkg, id);
            }
        }
        configs.put(user, config);
        return config;
    }

    private void save(int user, Config config) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("enabled", config.enabled);
        root.put("performanceMode", config.performanceMode);
        root.put("overrides", new JSONObject(config.overrides));
        try {
            stores.apply(user).write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Rename may already have published the new bytes. Reconcile, never promise rollback.
            configs.remove(user);
            persistenceErrors.put(user, e.toString());
            changed();
            notifyConfigurationChanged(user);
            throw e;
        }
        configs.put(user, config);
        persistenceErrors.remove(user);
        notifyConfigurationChanged(user);
    }

    private void notifyConfigurationChanged(int user) {
        if (ready) worker.post(() -> {
            try {
                context.getContentResolver().notifyChange(
                        android.net.Uri.parse(PERFORMANCE_STATE_URI), null, 0, user);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Cannot notify configuration observers", e);
            }
        });
    }

    private void enforce(int user) {
        int uid = Binder.getCallingUid();
        if (!(Build.IS_DEBUGGABLE && (uid == Process.ROOT_UID || uid == Process.SHELL_UID))) {
            context.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS, SERVICE_NAME);
            if (context.getPackageManager().checkSignatures(Process.SYSTEM_UID, uid)
                    != PackageManager.SIGNATURE_MATCH) {
                throw new SecurityException("Platform signature required");
            }
            if (UserHandle.getUserId(uid) != user) {
                context.enforceCallingOrSelfPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS_FULL, SERVICE_NAME);
            }
        }
        if (user < 0) throw new IllegalArgumentException("Concrete userId required");
    }

    private void enforcePowerKeeperCompat() {
        int uid = Binder.getCallingUid();
        PackageManager packages = context.getPackageManager();
        if (packages.checkSignatures(Process.SYSTEM_UID, uid) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Platform signature required");
        }
        String[] names = packages.getPackagesForUid(uid);
        if (names != null) {
            for (String name : names) {
                if (POWER_KEEPER_PACKAGE.equals(name)) return;
            }
        }
        throw new SecurityException("PowerKeeper compatibility package required");
    }

    private void installed(int user, String pkg) throws Exception {
        if (users.getUserInfo(user) == null) throw new IllegalArgumentException("Unknown user");
        if (pkg == null || pkg.isEmpty()) throw new IllegalArgumentException("Package required");
        context.getPackageManager().getApplicationInfoAsUser(pkg, 0, user);
    }

    @Override public int getApiVersion() {
        enforce(UserHandle.getCallingUserId());
        return API_VERSION;
    }

    // The compact reads share permissions, config and effective mode with full diagnostics.
    @Override public synchronized Bundle getSchedulingState(int user) {
        return getSettingsState(user, true);
    }

    @Override public synchronized Bundle getPerformanceModeState(int user) {
        return getSettingsState(user, false);
    }

    private Bundle getSettingsState(int user, boolean scheduling) {
        enforce(user);
        long identity = Binder.clearCallingIdentity();
        try {
            if (users.getUserInfo(user) == null) throw new IllegalArgumentException("Unknown user");
            Config config = config(user);
            Bundle out = new Bundle();
            putModeState(out, user, config);
            if (scheduling) {
                out.putString(ThermalServiceContract.KEY_ERROR, error);
                out.putString("persistenceError", persistenceErrors.getOrDefault(user, ""));
                putOverrides(out, config);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void putModeState(Bundle out, int user, Config config) throws Exception {
        out.putBoolean(ThermalServiceContract.KEY_READY, ready);
        out.putBoolean(ThermalServiceContract.KEY_ENABLED, config.enabled);
        out.putBoolean(ThermalServiceContract.KEY_PERFORMANCE_MODE,
                config(modeUser(user)).performanceMode);
    }

    private static void putOverrides(Bundle out, Config config) {
        Bundle overrides = new Bundle();
        config.overrides.forEach(overrides::putInt);
        out.putBundle(ThermalServiceContract.KEY_OVERRIDES, overrides);
    }

    @Override public synchronized Bundle getState(int user) {
        enforce(user);
        long identity = Binder.clearCallingIdentity();
        try {
            if (users.getUserInfo(user) == null) throw new IllegalArgumentException("Unknown user");
            Config config = config(user);
            Bundle out = new Bundle();
            out.putInt("version", API_VERSION);
            putModeState(out, user, config);
            out.putBoolean("daemonRunning", requests.running());
            out.putLong("requestEpoch", requests.epoch());
            out.putString("lifecycleError", lifecycleError);
            out.putString("watcherError", watcherError);
            out.putString("cameraWatcherError", cameraWatcherError);
            out.putBoolean("appStateConnected", appList.isConnected());
            out.putInt("resumedActivities", appList.resumedCount());
            out.putString("appStateError", appList.error());
            out.putLong("thermalNotifySentCycles", thermalNotifier.sentCycles());
            out.putString("thermalNotifyError", thermalNotifier.error());
            long now = SystemClock.uptimeMillis();
            boolean gpuRequested = now < powerState.gpuUntil;
            int gpuProfile = gpuRequested ? powerState.gpuProfile : 0;
            boolean appRequested = now < powerState.appUntil;
            int appHint = appRequested ? powerState.appHint : 0;
            int appPid = appRequested ? powerState.appPid : 0;
            boolean appSettling = now < powerState.appSettlingUntil;
            out.putBoolean("gptRequested", gpuRequested);
            out.putBoolean("gptRestorePending", powerState.gpuRestorePending);
            out.putString("gptError", powerState.gpuError);
            out.putInt("gamingPowerProfile", gpuProfile);
            out.putInt("powerProfile", gpuProfile);
            out.putInt("dramFloorOpp", ThermalProfiles.dramFloorOpp(gpuProfile));
            out.putInt("appModeHint", appHint);
            out.putInt("appModePid", appPid);
            out.putInt("gameModePid", appHint == AppModePolicy.GAME_HINT ? appPid : 0);
            out.putBoolean("appModeSettling", appSettling);
            out.putString("appModeError", powerState.appError);
            out.putBoolean("gameModeSettling", appSettling);
            out.putString("gameModeError", powerState.appError);
            out.putString("persistenceError", persistenceErrors.getOrDefault(user, ""));
            out.putInt("currentUserId", currentUser);
            out.putInt("foregroundUserId", foregroundUser == user ? user : -1);
            out.putString("foregroundPackage", foregroundUser == user ? foregroundPackage : "");
            out.putInt("requestedProfile", requests.requested());
            out.putBoolean("appliedConfirmed", false);
            out.putString("reason", reason);
            out.putString(ThermalServiceContract.KEY_ERROR, error);
            out.putString("eventError", eventError);
            out.putInt("scenarioId", scenario);
            out.putInt("cameraElement", cameraElement);
            out.putInt("cameraUserId", cameraUser);
            out.putBoolean("offHook", offHook);
            out.putBoolean("lowTempCharge", lowTempCharge);
            out.putBoolean("reverseCharge", reverseCharge);
            out.putIntArray("selectableProfiles", ThermalProfiles.selectableIds());
            putOverrides(out, config);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public synchronized Bundle getAppPolicy(int user, String pkg) {
        enforce(user);
        long identity = Binder.clearCallingIdentity();
        try {
            installed(user, pkg);
            Config config = config(user);
            int override = config.overrides.getOrDefault(pkg, ThermalProfiles.AUTO);
            Bundle out = new Bundle();
            out.putInt("defaultProfile", defaultProfile(pkg, config(modeUser(user)).performanceMode));
            out.putInt(ThermalServiceContract.KEY_OVERRIDE_PROFILE, override);
            out.putInt(ThermalServiceContract.KEY_SELECTED_PROFILE, override == ThermalProfiles.AUTO
                    ? defaultProfile(pkg, config(modeUser(user)).performanceMode) : override);
            out.putString(ThermalServiceContract.KEY_STOCK_GROUP,
                    groups.getOrDefault(pkg, "unclassified"));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // Managed-profile apps follow their parent user's mode; their app overrides stay separate.
    private int modeUser(int user) {
        UserInfo parent = users.getProfileParent(user);
        return parent == null ? user : parent.id;
    }

    @Override public synchronized void setPerformanceMode(int user, boolean enabled) {
        enforce(user);
        long identity = Binder.clearCallingIdentity();
        try {
            if (users.getUserInfo(user) == null) throw new IllegalArgumentException("Unknown user");
            if (modeUser(user) != user) throw new IllegalArgumentException("Use parent user mode");
            Config updated = config(user).copy();
            updated.performanceMode = enabled;
            save(user, updated);
            changed();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public synchronized void notifyCameraRecordState(
            boolean recording, int quality, int fps) {
        enforcePowerKeeperCompat();
        cameraElement = ThermalScenarioPolicy.cameraRecordElement(recording, quality, fps);
        cameraUser = cameraElement == ThermalScenarioPolicy.OFF
                ? -1 : UserHandle.getUserId(Binder.getCallingUid());
        changed();
    }

    @Override public synchronized void setAppProfile(int user, String pkg, int id) {
        enforce(user);
        if (!ThermalProfiles.selectable(id)) throw new IllegalArgumentException("Unsupported profile");
        long identity = Binder.clearCallingIdentity();
        try {
            installed(user, pkg);
            Config updated = config(user).copy();
            if (id == ThermalProfiles.AUTO) updated.overrides.remove(pkg);
            else updated.overrides.put(pkg, id);
            save(user, updated);
            changed();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public synchronized void setEnabled(int user, boolean enabled) {
        enforce(user);
        long identity = Binder.clearCallingIdentity();
        try {
            if (users.getUserInfo(user) == null) throw new IllegalArgumentException("Unknown user");
            Config updated = config(user).copy();
            updated.enabled = enabled;
            save(user, updated);
            changed();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private synchronized void removePackage(int user, String pkg) {
        try {
            Config previous = config(user);
            if (!previous.overrides.containsKey(pkg)) return;
            Config updated = previous.copy();
            updated.overrides.remove(pkg);
            save(user, updated);
            changed();
        } catch (Exception e) {
            Slog.e(TAG, "Cannot remove package override", e);
        }
    }

    @Override protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        context.enforceCallingOrSelfPermission(Manifest.permission.DUMP, SERVICE_NAME);
        pw.println(getState(currentUser));
    }

    private int powerCommand(Callable<Integer> command, boolean acquire) throws Exception {
        final AtomicInteger token = new AtomicInteger();
        final FutureTask<Integer> task = new FutureTask<>(() -> {
            int result = command.call();
            token.set(result);
            return result;
        });
        if (!worker.post(task)) throw new IllegalStateException("Thermal worker unavailable");
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            task.cancel(false);
            // A timed-out Binder call may still finish. Cleanup runs after it on the same worker.
            if (acquire) worker.post(() -> {
                try { power.release(token.get()); }
                catch (Exception failure) { Slog.w(TAG, "Power request cleanup failed", failure); }
            });
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        }
    }

    @Override public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver receiver) {
        int uid = Binder.getCallingUid();
        if (!Build.IS_DEBUGGABLE || (uid != Process.ROOT_UID && uid != Process.SHELL_UID)) {
            throw new SecurityException("Debug shell only");
        }
        new ShellCommand() {
            @Override public int onCommand(String cmd) {
                if (cmd == null) return handleDefaultCommands(cmd);
                try {
                    switch (cmd) {
                        case "status": getOutPrintWriter().println(getState(currentUser)); return 0;
                        case "get": {
                            int user = Integer.parseInt(getNextArgRequired());
                            getOutPrintWriter().println(getAppPolicy(user, getNextArgRequired()));
                            return 0;
                        }
                        case "set": {
                            int user = Integer.parseInt(getNextArgRequired());
                            String pkg = getNextArgRequired();
                            setAppProfile(user, pkg, Integer.parseInt(getNextArgRequired()));
                            return 0;
                        }
                        case "enable": {
                            int user = Integer.parseInt(getNextArgRequired());
                            String value = getNextArgRequired();
                            if (!value.equals("true") && !value.equals("false")) {
                                throw new IllegalArgumentException("true or false required");
                            }
                            setEnabled(user, Boolean.parseBoolean(value));
                            return 0;
                        }
                        case "resync":
                            worker.post(ThermalBackend.this::resync);
                            return 0;
                        case "perf-acquire": {
                            int duration = Integer.parseInt(getNextArgRequired());
                            String[] args = peekRemainingArgs();
                            int[] resources = new int[args.length];
                            for (int i = 0; i < args.length; ++i) {
                                resources[i] = Integer.decode(args[i]);
                            }
                            int token = powerCommand(() -> power.acquireResources(
                                    duration, resources), true);
                            if (token <= 0) throw new IllegalStateException(
                                    "MTK PowerHAL rejected request: " + token);
                            getOutPrintWriter().println(token);
                            return 0;
                        }
                        case "perf-release": {
                            int token = Integer.parseInt(getNextArgRequired());
                            if (token <= 0) throw new IllegalArgumentException("Positive token required");
                            powerCommand(() -> { power.release(token); return 0; }, false);
                            return 0;
                        }
                        default: return handleDefaultCommands(cmd);
                    }
                } catch (Exception e) {
                    getErrPrintWriter().println(e);
                    return -1;
                }
            }
            @Override public void onHelp() {
                getOutPrintWriter().println("status | get USER PACKAGE | set USER PACKAGE ID\n"
                        + "enable USER true|false | resync\n"
                        + "perf-acquire DURATION_MS RESOURCE VALUE [RESOURCE VALUE...]\n"
                        + "perf-release TOKEN (debug only; requests expire automatically)\n"
                        + "ID: " + ThermalProfiles.help()
                        + ". Writes acknowledge saved config, not actuation.");
            }
        }.exec(this, in, out, err, args, callback, receiver);
    }
}
