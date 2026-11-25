package com.agy.core;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.agy.runtime.RuntimeManager;
import com.agy.util.CoreUrl;
import com.agy.util.ErrorText;
import com.agy.util.ProcessUtils;
import com.agy.util.SensitiveDataRedactor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Writer;

/**
 * Supervises the core for its whole life. One pass = install, launch, wait for
 * ready, then block until exit. Intentional stops end the loop; an unexpected
 * exit is auto-restarted with exponential backoff up to MAX_RESTARTS, the
 * budget refilling after SUSTAINED_READY_MS of uptime.
 */
public final class CoreProcessSupervisor {

    private static final String TAG = "CoreProcessSupervisor";

    private final Context appContext;
    private final RuntimeManager runtime;
    private final OpenUrlWatcher openUrlWatcher;
    private final com.agy.log.LogRingBuffer logBuffer;
    private final Runnable terminalListener;

    private volatile Process process;
    private volatile boolean stopping = false;
    private Thread worker;

    public CoreProcessSupervisor(Context context, RuntimeManager runtime,
                                 OpenUrlWatcher openUrlWatcher,
                                 com.agy.log.LogRingBuffer logBuffer,
                                 Runnable terminalListener) {
        this.appContext = context.getApplicationContext();
        this.runtime = runtime;
        this.openUrlWatcher = openUrlWatcher;
        this.logBuffer = logBuffer;
        this.terminalListener = terminalListener;
    }

    public void start() {
        stopping = false;
        worker = new Thread(this::runCore, "CoreServerThread");
        worker.start();
    }

    public void stop() {
        stopping = true;
        destroy(process);
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void runCore() {
        try {
            runCoreLoop();
        } finally {
            if (!stopping && terminalListener != null) {
                try {
                    terminalListener.run();
                } catch (Exception e) {
                    Log.w(TAG, "terminal callback failed", e);
                }
            }
        }
    }

    private void runCoreLoop() {
        int attempt = 0;
        while (!stopping) {
            Writer log = CoreLogWriter.open(appContext.getFilesDir());
            Writer errLog = CoreLogWriter.open(appContext.getFilesDir(), "core_stderr.log", CoreLogWriter.STDERR_MAX_BYTES);
            try {
                CoreRuntimeState.lastError = null;
                CoreRuntimeState.restarting = false;
                CoreLogWriter.writeLine(log, "\n=== Antigravity Core Launch (attempt " + (attempt + 1) + ") ===");
                Process proc = CoreLauncher.launch(runtime, appContext.getFilesDir());
                process = proc;

                final Writer streamWriter = log;
                final Writer errWriter = errLog;
                Thread streamer = new Thread(() -> streamOutput(proc, streamWriter), "CoreStreamThread");
                Thread errStreamer = new Thread(() -> streamStderr(proc, errWriter), "CoreStderrThread");
                streamer.setDaemon(true);
                errStreamer.setDaemon(true);
                streamer.start();
                errStreamer.start();

                if (!waitForReady(proc, log)) {
                    if (stopping) {
                        break;
                    }
                    try {
                        streamer.join(1000);
                        errStreamer.join(500);
                    } catch (InterruptedException ignored) {
                    }
                    CoreLogWriter.closeQuietly(errLog);
                    errLog = null;
                    int code = ProcessUtils.exitCode(proc);
                    if (CoreRuntimeState.lastError == null) {
                        CoreRuntimeState.lastError =
                                "core did not become ready within " + CoreReadyProbe.READY_TIMEOUT_MS + "ms"
                                + (code != -1 ? " (exit code " + code + describeExitCode(code, appContext.getFilesDir()) + ")" : "");
                        CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    }
                    destroy(proc);
                    attempt++;
                    String tail = getLastLinesFromLog(appContext.getFilesDir(), 15);
                    if (attempt > CoreRestartPolicy.MAX_RESTARTS) {
                        CoreRuntimeState.restarting = false;
                        CoreRuntimeState.lastError = "core failed to start after " + CoreRestartPolicy.MAX_RESTARTS
                                + " attempts (last exit code " + code + describeExitCode(code, appContext.getFilesDir()) + ")"
                                + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                        CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                        com.agy.log.CrashRecorder.recordCrash(appContext, code, 0, CoreRuntimeState.lastError, logBuffer);
                        break;
                    }
                    long delay = CoreRestartPolicy.delayFor(attempt);
                    CoreRuntimeState.restarting = true;
                    CoreRuntimeState.lastError = "core startup failed (code " + code + describeExitCode(code, appContext.getFilesDir())
                            + "); retrying in " + (delay / 1000) + "s (attempt "
                            + attempt + "/" + CoreRestartPolicy.MAX_RESTARTS + ")"
                            + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                    Log.w(TAG, CoreRuntimeState.lastError);
                    CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    com.agy.log.CrashRecorder.recordCrash(appContext, code, 0, CoreRuntimeState.lastError, logBuffer);
                    CoreLogWriter.closeQuietly(log);
                    sleepInterruptible(delay);
                    continue;
                }

                long readyAt = System.currentTimeMillis();
                try {
                    proc.waitFor();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (stopping) {
                    break;
                }
                try {
                    streamer.join(1000);
                    errStreamer.join(500);
                } catch (InterruptedException ignored) {
                }
                CoreLogWriter.closeQuietly(errLog);
                errLog = null;

                int code = ProcessUtils.exitCode(proc);
                if (System.currentTimeMillis() - readyAt >= CoreRestartPolicy.SUSTAINED_READY_MS) {
                    attempt = 0;
                }
                CoreRuntimeState.serverUrl = null;
                attempt++;
                String exitDesc = describeExitCode(code, appContext.getFilesDir());
                String tail = getLastLinesFromLog(appContext.getFilesDir(), 15);
                if (attempt > CoreRestartPolicy.MAX_RESTARTS) {
                    CoreRuntimeState.restarting = false;
                    CoreRuntimeState.lastError = "core crashed " + CoreRestartPolicy.MAX_RESTARTS
                            + " times; giving up (last exit code " + code + exitDesc + ")"
                            + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                    CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    com.agy.log.CrashRecorder.recordCrash(appContext, code, System.currentTimeMillis() - readyAt, CoreRuntimeState.lastError, logBuffer);
                    break;
                }
                long delay = CoreRestartPolicy.delayFor(attempt);
                CoreRuntimeState.restarting = true;
                CoreRuntimeState.lastError = "core exited unexpectedly (code " + code + exitDesc
                        + "); restarting in " + (delay / 1000) + "s (attempt "
                        + attempt + "/" + CoreRestartPolicy.MAX_RESTARTS + ")"
                        + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                Log.w(TAG, CoreRuntimeState.lastError);
                CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                com.agy.log.CrashRecorder.recordCrash(appContext, code, System.currentTimeMillis() - readyAt, CoreRuntimeState.lastError, logBuffer);
                CoreLogWriter.closeQuietly(log);
                log = null;
                sleepInterruptible(delay);
            } catch (Exception e) {
                if (stopping) {
                    break;
                }
                Log.e(TAG, "core start failed", e);
                CoreRuntimeState.lastError = ErrorText.describe(e);
                CoreLogWriter.writeLine(log, "core start failed: " + CoreRuntimeState.lastError);
                // install/launch failure: fail-closed, user retries from the splash.
                break;
            } finally {
                CoreLogWriter.closeQuietly(errLog);
                CoreLogWriter.closeQuietly(log);
            }
        }
    }

    private boolean waitForReady(Process proc, Writer log) {
        long start = System.currentTimeMillis();
        String csrf = runtime.getCsrfToken();
        int port = CoreRuntimeState.httpsPort;
        while (System.currentTimeMillis() - start < CoreReadyProbe.READY_TIMEOUT_MS) {
            if (stopping) {
                return false;
            }
            if (!ProcessUtils.isAlive(proc)) {
                int code = ProcessUtils.exitCode(proc);
                ProcessUtils.sleep(200);
                String lastLogs = getLastLinesFromLog(appContext.getFilesDir(), 10);
                CoreRuntimeState.lastError = "core process exited before it became ready (exit code " + code + ")"
                        + (lastLogs.isEmpty() ? "" : "\n--- core.log tail ---\n" + lastLogs);
                CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                return false;
            }
            if (CoreReadyProbe.probeOnce(appContext, port)) {
                CoreRuntimeState.serverUrl = CoreUrl.startUrl(port, csrf);
                CoreRuntimeState.lastError = null;
                CoreRuntimeState.restarting = false;
                CoreRuntimeState.readyGeneration++;
                String readyStatus = "core ready on 127.0.0.1:" + port;
                Log.i(TAG, readyStatus);
                CoreLogWriter.writeLine(log, readyStatus);
                com.agy.shell.RootResilience.applyProtectionAsync(appContext, null, 500L);
                return true;
            }
        }
        return false;
    }

    private void streamOutput(Process proc, Writer log) {
        pumpStream(proc.getInputStream(), log, true, false);
    }

    private void streamStderr(Process proc, Writer errLog) {
        pumpStream(proc.getErrorStream(), errLog, false, true);
    }

    private void pumpStream(java.io.InputStream in, Writer target, boolean watchOpenUrl, boolean stderr) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            long writtenBytes = 0;
            while ((line = reader.readLine()) != null) {
                String safeLine = SensitiveDataRedactor.redactLine(line);
                if (stderr) {
                    Log.w(TAG, "[stderr] " + safeLine);
                    // Cap single-session writes to avoid unbounded growth during long runs
                    if (writtenBytes < CoreLogWriter.STDERR_MAX_BYTES) {
                        CoreLogWriter.writeLine(target, safeLine);
                        writtenBytes += safeLine.length() + 1;
                    }
                } else {
                    Log.d(TAG, safeLine);
                    if (logBuffer != null) {
                        logBuffer.appendLine(safeLine);
                    }
                    CoreLogWriter.writeLine(target, safeLine);
                }
                if (watchOpenUrl) {
                    openUrlWatcher.handleStreamLine(line);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, (stderr ? "stderr" : "log") + " stream ended", e);
        }
    }

    private void sleepInterruptible(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stopping) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            ProcessUtils.sleep(Math.min(left, 250));
        }
    }

    private static void destroy(Process proc) {
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Exception ignored) {
            }
            try {
                proc.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        CoreLauncher.killOrphanCoreProcesses();
    }

    private static String getLastLinesFromLog(java.io.File filesDir, int maxLines) {
        java.io.File logFile = new java.io.File(filesDir, "logs/core.log");
        if (!logFile.isFile()) {
            return "";
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(logFile))) {
            String l;
            while ((l = br.readLine()) != null) {
                lines.add(SensitiveDataRedactor.redactLine(l));
                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }
        } catch (Exception ignored) {
        }
        return TextUtils.join("\n", lines);
    }

    private static String getSigillLogTail(java.io.File filesDir) {
        java.io.File sigillFile = new java.io.File(filesDir, "logs/sigill.log");
        if (!sigillFile.isFile() || sigillFile.length() == 0) {
            sigillFile = new java.io.File(filesDir, "sigill.log");
        }
        if (!sigillFile.isFile() || sigillFile.length() == 0) {
            return "";
        }
        try {
            return com.agy.util.IoUtil.readAll(new java.io.FileInputStream(sigillFile), "UTF-8").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String getStderrLogTail(java.io.File filesDir, int maxLines) {
        java.io.File errFile = new java.io.File(filesDir, "logs/core_stderr.log");
        if (!errFile.isFile() || errFile.length() == 0) {
            return "";
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(errFile))) {
            String l;
            while ((l = br.readLine()) != null) {
                lines.add(SensitiveDataRedactor.redactLine(l));
                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }
        } catch (Exception ignored) {
        }
        return TextUtils.join("\n", lines);
    }

    private static String describeExitCode(int code, java.io.File filesDir) {
        String base;
        switch (code) {
            case 2:
                base = " [fatal error / runtime.throw]";
                break;
            case 137:
                base = " [SIGKILL / system Out-Of-Memory LMK]";
                break;
            case 139:
                base = " [SIGSEGV / segmentation fault]";
                break;
            case 134:
                base = " [SIGABRT / abort]";
                break;
            case 132:
                base = " [SIGILL / illegal instruction]";
                break;
            case 143:
                base = " [SIGTERM / terminated]";
                break;
            default:
                base = "";
                break;
        }
        if (code != 0) {
            String sigill = getSigillLogTail(filesDir);
            if (!sigill.isEmpty()) {
                base += " - " + sigill;
            }
            String stderrTail = getStderrLogTail(filesDir, 50);
            if (!stderrTail.isEmpty()) {
                base += " | stderr: " + stderrTail;
            }
        }
        return base;
    }
}
