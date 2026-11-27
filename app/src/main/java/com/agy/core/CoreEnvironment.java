package com.agy.core;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import com.agy.runtime.RuntimeManager;

import java.io.File;
import java.util.Map;

/** Builds the environment for the glibc core child process. */
public final class CoreEnvironment {

    private static final String TAG = "CoreEnvironment";
    private static final long SIDECAR_MIN_RAM_BYTES = 5500L * 1024 * 1024;

    private CoreEnvironment() {
    }

    private static final class CpuFeatures {
        final boolean hasAes;
        final boolean hasPmull;
        final boolean hasSha1;
        final boolean hasSha2;
        final boolean hasAtomics;
        final boolean isMt676x;
        final boolean is64Bit;

        CpuFeatures(boolean hasAes, boolean hasPmull, boolean hasSha1,
                    boolean hasSha2, boolean hasAtomics, boolean isMt676x, boolean is64Bit) {
            this.hasAes = hasAes;
            this.hasPmull = hasPmull;
            this.hasSha1 = hasSha1;
            this.hasSha2 = hasSha2;
            this.hasAtomics = hasAtomics;
            this.isMt676x = isMt676x;
            this.is64Bit = is64Bit;
        }
    }

    private static CpuFeatures sCpuFeatures = null;

    public static void apply(RuntimeManager rm, ProcessBuilder pb) {
        Context appCtx = rm.appContext();
        File files = appCtx.getFilesDir();
        File bin = rm.getBinDir();
        Map<String, String> env = pb.environment();
        // HOME is the app files dir and never a temp dir; it is stable across
        // builds so the core always finds $HOME/.gemini (token, config, chats).
        env.put("HOME", files.getAbsolutePath());
        env.put("TMPDIR", appCtx.getCacheDir().getAbsolutePath());
        Log.i(TAG, "core env: HOME=" + files.getAbsolutePath()
                + " appDataDir=" + rm.getAppDataDir().getAbsolutePath()
                + " projectsDir=" + rm.getProjectsDir().getAbsolutePath()
                + " TMPDIR=" + appCtx.getCacheDir().getAbsolutePath());
        File prefix = new File(files, com.agy.install.EnvPaths.PREFIX_DIR);
        File prefixCert = new File(prefix, "etc/tls/cert.pem");
        File certFile = prefixCert.isFile() ? prefixCert : rm.getCertFile();
        env.put("SSL_CERT_FILE", certFile.getAbsolutePath());
        env.put("CURL_CA_BUNDLE", certFile.getAbsolutePath());
        env.put("GIT_SSL_CAINFO", certFile.getAbsolutePath());
        File opensslConf = new File(prefix, "etc/tls/openssl.cnf");
        if (opensslConf.isFile()) {
            env.put("OPENSSL_CONF", opensslConf.getAbsolutePath());
        }
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_ATTR_NOSYSTEM", "1");
        String inheritedPath = env.get("PATH");

        // The core's PTY spawner uses $SHELL (or falls back to /bin/bash, which
        // does not exist on Android). Prefer the bridge wrapper in runtime/bin
        // which configures LD_LIBRARY_PATH, PATH, and unsets LD_PRELOAD before
        // execing the bionic bash binary.
        File prefixBash = new File(prefix, com.agy.install.EnvPaths.BASH_REL);
        File runtimeBash = new File(bin, "bash");
        env.put("PREFIX", prefix.getAbsolutePath());
        File prefixBin = new File(prefix, "bin");
        String finalPath = bin.getAbsolutePath() + ":" + prefixBin.getAbsolutePath();
        if (inheritedPath != null && !inheritedPath.isEmpty()) {
            finalPath += ":" + inheritedPath;
        }
        env.put("PATH", finalPath);
        if (runtimeBash.isFile() && runtimeBash.canExecute()) {
            env.put("SHELL", runtimeBash.getAbsolutePath());
        } else if (prefixBash.isFile() && prefixBash.canExecute()) {
            env.put("SHELL", prefixBash.getAbsolutePath());
        } else {
            env.put("SHELL", "/system/bin/sh");
        }
        // PTY children need a terminal type, and the bootstrap prefix ships the
        // terminfo database that resolves it.
        env.put("TERM", "xterm-256color");
        File terminfo = new File(prefix, "share/terminfo");
        if (terminfo.isDirectory()) {
            env.put("TERMINFO", terminfo.getAbsolutePath());
        }
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");

        // Pure-Go resolver keeps DNS independent of Android's missing /etc.
        String godebug = env.get("GODEBUG");
        StringBuilder godebugBuilder = new StringBuilder();
        if (godebug != null && !godebug.isEmpty()) {
            godebugBuilder.append(godebug).append(",");
        }
        godebugBuilder.append("netdns=go,asyncpreemptoff=1");

        CpuFeatures cpu = getCpuFeatures();
        if (!cpu.is64Bit || cpu.isMt676x) {
            Log.w(TAG, "CPU lacks ARMv8 crypto/atomics extension; disabling Go hardware crypto acceleration");
            godebugBuilder.append(",cpu.all=off,arm64.aes=off,arm64.pmull=off,arm64.sha1=off,arm64.sha2=off,arm64.sha3=off,arm64.sha512=off,arm64.crc32=off,arm64.atomics=off,arm64.cpuid=off");
        } else {
            // Granular flags: keep hardware AES/SHA enabled on ARMv8.0-A mid-range SoCs
            // that implement crypto extensions even when LSE atomics are absent.
            if (!cpu.hasAes) {
                godebugBuilder.append(",arm64.aes=off");
            }
            if (!cpu.hasPmull) {
                godebugBuilder.append(",arm64.pmull=off");
            }
            if (!cpu.hasSha1) {
                godebugBuilder.append(",arm64.sha1=off");
            }
            if (!cpu.hasSha2) {
                godebugBuilder.append(",arm64.sha2=off");
            }
            if (!cpu.hasAtomics) {
                godebugBuilder.append(",arm64.atomics=off");
            }
        }
        env.put("GODEBUG", godebugBuilder.toString());

        applyGoMemoryTuning(appCtx, env);

        // Emits "ANTIGRAVITY_OPEN_URL: <url>" directly to stdout from language_server
        // so OpenUrlWatcher captures the auth URL and opens the external browser reliably.
        env.put("ANTIGRAVITY_VSCODE_HOST", "1");
        // Configure browser and desktop environment so core OAuth/open tools find the browser
        File xdgOpen = new File(bin, "xdg-open");
        env.put("BROWSER", xdgOpen.getAbsolutePath());
        env.put("DISPLAY", ":0");
        env.put("WAYLAND_DISPLAY", "wayland-0");
        env.put("XDG_CURRENT_DESKTOP", "X-Generic");
        env.put("XDG_DATA_DIRS", rm.getRuntimeDir().getAbsolutePath());
        env.put("XDG_DATA_HOME", new File(files, ".local/share").getAbsolutePath());
        env.put("XDG_CONFIG_HOME", new File(files, ".config").getAbsolutePath());
        // The glibc loader resolves libraries via --library-path; keep bionic's out of the way.
        env.remove("LD_LIBRARY_PATH");
        // Do NOT export LD_PRELOAD into the process environment!
        // liblse_compat.so is a GLIBC library; exporting it in env causes Bionic's
        // dynamic linker to crash when executing /system/bin/sh (xdg-open, bridges, termux).
        // RuntimeManager.coreCommand() already preloads it into language_server via the
        // glibc loader's CLI argument: --preload <path>/liblse_compat.so.
        env.remove("LD_PRELOAD");
    }

    private static void applyGoMemoryTuning(Context context, Map<String, String> env) {
        if (context == null) {
            return;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return;
            }
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long totalMb = mi.totalMem / (1024L * 1024L);
            String memLimit;
            String gogc;
            if (totalMb < 3500L) {
                memLimit = "448MiB";
                gogc = "75";
            } else if (totalMb < 5500L) {
                memLimit = "640MiB";
                gogc = "85";
            } else if (totalMb < 7500L) {
                memLimit = "1024MiB";
                gogc = "100";
            } else {
                memLimit = "1536MiB";
                gogc = "100";
            }
            if (!env.containsKey("GOMEMLIMIT")) {
                env.put("GOMEMLIMIT", memLimit);
            }
            if (!env.containsKey("GOGC")) {
                env.put("GOGC", gogc);
            }
            Log.i(TAG, "Go runtime tuning (device RAM=" + totalMb + "MB): GOMEMLIMIT="
                    + env.get("GOMEMLIMIT") + " GOGC=" + env.get("GOGC"));
        } catch (Throwable t) {
            Log.w(TAG, "failed to configure Go memory tuning: " + t.getMessage());
        }
    }

    /**
     * Determines whether the current device is capable of running background sidecars
     * (Automations / Scheduled Tasks) without triggering OOM (LMKD) or execution loops.
     *
     * Returns false on:
     * - Budget SoCs lacking hardware crypto/atomics (e.g. MediaTek MT676x / Cortex-A53).
     * - Devices flagged as isLowRamDevice or having < 5.5GB RAM (protects 4GB mid-range devices).
     */
    public static boolean isSidecarsSupported(Context context) {
        if (!hasArmHardwareCrypto()) {
            Log.i(TAG, "sidecars disabled: CPU lacks ARMv8 crypto/atomics or is low-end MT676x SoC");
            return false;
        }
        if (context != null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    if (am.isLowRamDevice()) {
                        Log.i(TAG, "sidecars disabled: isLowRamDevice is true");
                        return false;
                    }
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    if (mi.totalMem < SIDECAR_MIN_RAM_BYTES) {
                        Log.i(TAG, "sidecars disabled: device RAM < 5.5GB (" + (mi.totalMem / (1024 * 1024)) + "MB)");
                        return false;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "could not query memory info for sidecars probe: " + t.getMessage());
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether the current CPU supports ARMv8 Cryptography Extensions (AES & PMULL)
     * and LSE atomics. On ARMv8.0 cores without hardware crypto (e.g. MediaTek Helio A22 / G25 /
     * Cortex-A53), executing hardware crypto instructions raises SIGILL (exit code 132).
     */
    public static synchronized boolean hasArmHardwareCrypto() {
        CpuFeatures cpu = getCpuFeatures();
        return cpu.is64Bit && !cpu.isMt676x && cpu.hasAes && cpu.hasPmull && cpu.hasAtomics;
    }

    private static synchronized CpuFeatures getCpuFeatures() {
        if (sCpuFeatures != null) {
            return sCpuFeatures;
        }
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.US);
        if (!arch.contains("aarch64") && !arch.contains("arm64") && !arch.contains("arm")) {
            sCpuFeatures = new CpuFeatures(true, true, true, true, true, false, true);
            return sCpuFeatures;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            String[] abis64 = android.os.Build.SUPPORTED_64_BIT_ABIS;
            if (abis64 == null || abis64.length == 0) {
                Log.i(TAG, "device reports no 64-bit ABIs; disabling hardware acceleration");
                sCpuFeatures = new CpuFeatures(false, false, false, false, false, false, false);
                return sCpuFeatures;
            }
        }

        String hardware = android.os.Build.HARDWARE != null ? android.os.Build.HARDWARE.toLowerCase(java.util.Locale.US) : "";
        String board = android.os.Build.BOARD != null ? android.os.Build.BOARD.toLowerCase(java.util.Locale.US) : "";
        if (hardware.startsWith("mt676") || board.startsWith("mt676")
                || hardware.contains("mt6761") || hardware.contains("mt6762")) {
            Log.i(TAG, "detected MediaTek Helio MT676x SoC (hardware=" + hardware + ", board=" + board + ") without crypto extension");
            sCpuFeatures = new CpuFeatures(false, false, false, false, false, true, true);
            return sCpuFeatures;
        }

        File cpuinfo = new File("/proc/cpuinfo");
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(cpuinfo))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim().toLowerCase(java.util.Locale.US);
                if (line.startsWith("features") || line.startsWith("flags")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0) {
                        String flags = " " + line.substring(colon + 1).trim() + " ";
                        boolean hasAes = flags.contains(" aes ");
                        boolean hasPmull = flags.contains(" pmull ");
                        boolean hasSha1 = flags.contains(" sha1 ");
                        boolean hasSha2 = flags.contains(" sha2 ");
                        boolean hasAtomics = flags.contains(" atomics ");
                        sCpuFeatures = new CpuFeatures(hasAes, hasPmull, hasSha1, hasSha2, hasAtomics, false, true);
                        return sCpuFeatures;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not read /proc/cpuinfo for crypto probe: " + t.getMessage());
        }
        sCpuFeatures = new CpuFeatures(false, false, false, false, false, false, true);
        return sCpuFeatures;
    }
}
