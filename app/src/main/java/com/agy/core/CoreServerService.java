package com.agy.core;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.agy.runtime.RuntimeManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Foreground host for the patched core (language_server). Optionally holds a partial wake lock,
 * streams the process log to files/logs/core.log, and publishes serverUrl once ready.
 */
public final class CoreServerService extends Service {

    private static final String TAG = "CoreServer";

    private RuntimeManager runtime;
    private WakeLockHolder wakeLock;
    private OpenUrlWatcher openUrlWatcher;
    private CoreProcessSupervisor supervisor;
    private com.agy.log.LogRingBuffer logBuffer;
    private com.agy.gateway.SystemGatewayServer gatewayServer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = new RuntimeManager(this);
        logBuffer = new com.agy.log.LogRingBuffer();
        gatewayServer = new com.agy.gateway.SystemGatewayServer(this, logBuffer, com.agy.BuildConfig.GATEWAY_PORT);
        gatewayServer.start();

        Log.i(TAG, "state paths: HOME=" + getFilesDir().getAbsolutePath()
                + " appData=" + runtime.getAppDataDir().getAbsolutePath()
                + " projects=" + runtime.getProjectsDir().getAbsolutePath()
                + " runtime=" + runtime.getRuntimeDir().getAbsolutePath());
        CoreRuntimeState.httpsPort = runtime.getHttpsPort();
        CoreRuntimeState.csrfToken = runtime.getCsrfToken();
        CoreRuntimeState.restarting = false;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.serverUrl = null;

        ForegroundNotification.start(this);
        wakeLock = new WakeLockHolder();
        wakeLock.acquire(this);

        openUrlWatcher = new OpenUrlWatcher(this);
        openUrlWatcher.start();

        pruneStaleHubFilesAsync();

        supervisor = new CoreProcessSupervisor(this, runtime, openUrlWatcher, logBuffer,
                this::onSupervisorTerminated);
        supervisor.start();

        startBackgroundDaemon();
        com.agy.shell.RootResilience.applyProtectionAsync(this, null, 3000L);
    }

    private void startBackgroundDaemon() {
        new Thread(() -> {
            try {
                // Give runtime environment a brief moment to stabilize
                Thread.sleep(1500);
                runDaemonCommand("start", 3000L);
            } catch (Exception e) {
                Log.w(TAG, "Failed to start agy-daemon: " + e.getMessage());
            }
        }, "AgyDaemonStarter").start();
    }

    private void stopBackgroundDaemon() {
        runDaemonCommand("stop", 2000L);
    }

    private void runDaemonCommand(String action, long timeoutMs) {
        try {
            File files = getFilesDir();
            File wrapper = new File(files, "runtime/bin/agy-daemon");
            File direct = new File(files, "usr/bin/agy-daemon");
            File target = wrapper.canExecute() ? wrapper : (direct.canExecute() ? direct : null);
            if (target == null) {
                return;
            }
            Log.i(TAG, "Running agy-daemon " + action + " via " + target.getName());
            String home = files.getAbsolutePath();
            String prefix = home + "/usr";
            ProcessBuilder pb = new ProcessBuilder(target.getAbsolutePath(), action);
            pb.directory(files);
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", home);
            pb.environment().put("PREFIX", prefix);
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            pb.environment().put("AGY_APPLICATION_ID", getPackageName());
            pb.environment().put("LD_LIBRARY_PATH", prefix + "/lib");
            pb.environment().put("PATH", home + "/runtime/bin:" + prefix + "/bin:/system/bin:" + System.getenv("PATH"));

            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null && out.length() < 2048) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
            } catch (Exception ignored) {
            }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "agy-daemon " + action + " timed out after " + timeoutMs + "ms; destroying forcibly");
                p.destroyForcibly();
                return;
            }
            int code = p.exitValue();
            if (code != 0) {
                String msg = "agy-daemon " + action + " failed (exit " + code + "): " + out;
                Log.w(TAG, msg);
                if (logBuffer != null) {
                    logBuffer.append("daemon", "WARN", msg);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to " + action + " agy-daemon: " + e.getMessage());
        }
    }

    private void pruneStaleHubFilesAsync() {
        final File daemonDir = new File(runtime.getAppDataDir(), "daemon");
        Thread t = new Thread(() -> pruneStaleHubFiles(daemonDir), "AgyHubPruner");
        t.setDaemon(true);
        t.start();
    }

    private static void pruneStaleHubFiles(File daemonDir) {
        if (daemonDir == null || !daemonDir.isDirectory()) {
            return;
        }
        File[] files = daemonDir.listFiles();
        if (files == null) {
            return;
        }
        int removed = 0;
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || !name.startsWith("hub_") || !name.endsWith(".json")) {
                continue;
            }
            if (!isHubFilePidAlive(f)) {
                if (f.delete()) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            Log.i(TAG, "Pruned " + removed + " stale hub discovery file(s)");
        }
    }

    private static boolean isHubFilePidAlive(File hubFile) {
        try {
            byte[] buf = new byte[4096];
            int n;
            try (FileInputStream fis = new FileInputStream(hubFile)) {
                n = fis.read(buf);
            }
            if (n <= 0) {
                return false;
            }
            JSONObject obj = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            int pid = obj.optInt("pid", -1);
            if (pid <= 0) {
                return false;
            }
            File cmdFile = new File("/proc/" + pid + "/cmdline");
            if (!cmdFile.isFile() || !cmdFile.canRead()) {
                return false;
            }
            byte[] cmdBuf = new byte[512];
            int cn;
            try (FileInputStream cfis = new FileInputStream(cmdFile)) {
                cn = cfis.read(cmdBuf);
            }
            if (cn <= 0) {
                return false;
            }
            String cmd = new String(cmdBuf, 0, cn, StandardCharsets.UTF_8);
            return cmd.contains("language_server") || cmd.contains("libloader");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void onSupervisorTerminated() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                CoreRuntimeState.serverUrl = null;
                CoreRuntimeState.restarting = false;
                if (CoreRuntimeState.lastError == null) {
                    CoreRuntimeState.lastError = "core supervisor stopped unexpectedly";
                }
                if (wakeLock != null) {
                    wakeLock.release();
                }
                if (runtime != null) {
                    runtime.stopNetworkMonitor();
                }
                if (openUrlWatcher != null) {
                    openUrlWatcher.stop();
                }
                ForegroundNotification.update(CoreServerService.this,
                        "Core stopped — open app to retry");
            }
        });
    }

    @Override
    public void onDestroy() {
        stopBackgroundDaemon();
        if (gatewayServer != null) {
            gatewayServer.stop();
        }
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        // Intentional stop: the watchdog must not treat the exit as a crash.
        if (supervisor != null) {
            supervisor.stop();
        }
        if (openUrlWatcher != null) {
            openUrlWatcher.stop();
        }
        if (wakeLock != null) {
            wakeLock.release();
        }
        if (runtime != null) {
            runtime.stopNetworkMonitor();
        }
        CoreRuntimeState.serverUrl = null;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.restarting = false;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
