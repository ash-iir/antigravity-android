package com.agy.shell;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.agy.util.ShellQuote;

/**
 * Root-assisted crash & anti-kill resilience for Antigravity.
 *
 * <p>When root (uid=0) is granted, protects Antigravity and its
 * {@code language_server} / {@code libloader} / {@code agy-daemon} processes
 * from aggressive Android Doze/LMKD kills:
 * <ul>
 *   <li>Adds the app package to Android's Doze battery whitelist.</li>
 *   <li>Sets {@code oom_score_adj} to {@code -100} on the app PID and child
 *       processes owned by this UID (re-applied after core restarts).</li>
 * </ul>
 * Completely non-fatal no-op when root is not granted.
 */
public final class RootResilience {

    private static final String TAG = "RootResilience";
    private static final int TARGET_OOM_SCORE_ADJ = -100;

    public static final class ProtectionResult {
        public final boolean rootGranted;
        public final boolean dozeWhitelisted;
        public final int protectedPids;
        public final String summary;

        public ProtectionResult(boolean rootGranted, boolean dozeWhitelisted,
                                int protectedPids, String summary) {
            this.rootGranted = rootGranted;
            this.dozeWhitelisted = dozeWhitelisted;
            this.protectedPids = protectedPids;
            this.summary = summary != null ? summary : "";
        }
    }

    private RootResilience() {
    }

    /** Schedules anti-kill protection on a low-priority daemon thread using the shared RootBridge. */
    public static void applyProtectionAsync(final Context context, final RootBridge rootBridge,
                                            final long delayMs) {
        if (context == null) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final RootBridge bridge = rootBridge != null ? rootBridge : RootBridge.getShared(appCtx);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                    ProtectionResult res = applyProtection(appCtx, bridge);
                    if (res.rootGranted) {
                        Log.i(TAG, "anti-kill resilience: " + res.summary);
                    }
                } catch (Throwable tr) {
                    Log.w(TAG, "anti-kill resilience skipped: " + tr.getMessage());
                }
            }
        }, "RootResilience");
        t.setDaemon(true);
        t.start();
    }

    /** Applies Doze whitelist + gentle OOM score adjustment via RootBridge. */
    public static ProtectionResult applyProtection(Context context, RootBridge rootBridge) {
        Context appCtx = context.getApplicationContext();
        RootBridge bridge = rootBridge != null ? rootBridge : RootBridge.getShared(appCtx);
        RootManagerInfo info = bridge.getCachedOrProbe();
        if (!info.granted) {
            return new ProtectionResult(false, false, 0, "root not granted");
        }

        String pkg = appCtx.getPackageName();
        int myPid = Process.myPid();
        int myUid = Process.myUid();

        String cmd = "PKG=" + ShellQuote.quote(pkg) + "; "
                + "MY_PID=" + myPid + "; "
                + "MY_UID=" + myUid + "; "
                + "DOZE=0; "
                + "if /system/bin/cmd deviceidle whitelist +\"$PKG\" >/dev/null 2>&1; then DOZE=1; "
                + "elif /system/bin/dumpsys deviceidle whitelist +\"$PKG\" >/dev/null 2>&1; then DOZE=1; fi; "
                + "COUNT=0; "
                + "if [ -w \"/proc/$MY_PID/oom_score_adj\" ]; then "
                + "  echo " + TARGET_OOM_SCORE_ADJ + " > \"/proc/$MY_PID/oom_score_adj\" 2>/dev/null && COUNT=$((COUNT+1)); "
                + "fi; "
                + "for d in /proc/[0-9]*; do "
                + "  p=\"${d#/proc/}\"; "
                + "  [ \"$p\" = \"$MY_PID\" ] && continue; "
                + "  grep -q \"^Uid:[[:space:]]*$MY_UID[[:space:]]\" \"$d/status\" 2>/dev/null || continue; "
                + "  cl=\"$(tr '\\0' ' ' < \"$d/cmdline\" 2>/dev/null || true)\"; "
                + "  case \"$cl\" in *language_server*|*libloader*|*agy-daemon*) "
                + "    echo " + TARGET_OOM_SCORE_ADJ + " > \"$d/oom_score_adj\" 2>/dev/null && COUNT=$((COUNT+1)); "
                + "  ;; esac; "
                + "done; "
                + "echo \"DOZE=$DOZE COUNT=$COUNT\"";

        RootBridge.ExecResult r = bridge.exec(cmd, false, 8000L, appCtx.getFilesDir());
        boolean dozeOk = r.stdout.contains("DOZE=1");
        int count = parseCount(r.stdout);
        String summary = "doze=" + (dozeOk ? "whitelisted" : "unchanged")
                + ", oom_protected_pids=" + count
                + " (adj=" + TARGET_OOM_SCORE_ADJ + ")";
        return new ProtectionResult(true, dozeOk, count, summary);
    }

    private static int parseCount(String out) {
        if (out == null) return 0;
        int idx = out.indexOf("COUNT=");
        if (idx < 0) return 0;
        String tail = out.substring(idx + 6).trim();
        int sp = tail.indexOf(' ');
        if (sp >= 0) tail = tail.substring(0, sp);
        int nl = tail.indexOf('\n');
        if (nl >= 0) tail = tail.substring(0, nl);
        try {
            return Integer.parseInt(tail.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }
}
