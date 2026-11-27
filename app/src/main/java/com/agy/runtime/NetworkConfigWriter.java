package com.agy.runtime;

import android.content.Context;
import android.util.Log;

import com.agy.net.DnsCollector;
import com.agy.util.AtomicFileWriter;

import java.io.File;

/**
 * The core reads "etc//resolv.conf" (cwd = getFilesDir()). Android has no
 * /etc/resolv.conf, so materialize one plus a minimal hosts file. Both are
 * written atomically so the core never sees a half-written resolv.conf.
 */
public final class NetworkConfigWriter {

    private static final String TAG = "NetworkConfigWriter";
    private static final String HOSTS_CONTENT = "127.0.0.1 localhost\n::1 localhost\n";
    private static volatile String lastResolvContent = null;
    private static volatile int lastProjectCount = -1;

    private NetworkConfigWriter() {
    }

    public static synchronized void write(Context appContext) {
        File filesDir = appContext.getFilesDir();
        File etc = new File(filesDir, "etc");
        if (!etc.exists() && !etc.mkdirs()) {
            Log.w(TAG, "cannot create " + etc);
            return;
        }

        StringBuilder resolv = new StringBuilder();
        resolv.append("options timeout:2 attempts:2\n");
        for (String ip : DnsCollector.collectDnsServers(appContext)) {
            resolv.append("nameserver ").append(ip).append('\n');
        }
        String resolvContent = resolv.toString();

        File geminiProjects = new File(filesDir, ".gemini/config/projects");
        File[] geminiDirs = geminiProjects.listFiles();
        File legacyProjects = new File(filesDir, "projects");
        File[] legacyDirs = legacyProjects.listFiles();
        int projectCount = (geminiDirs != null ? geminiDirs.length : 0)
                + (legacyDirs != null ? legacyDirs.length : 0);

        // Skip redundant fsync storms across all project directories when DNS servers
        // and project directory count have not changed since the previous write.
        if (resolvContent.equals(lastResolvContent)
                && projectCount == lastProjectCount
                && new File(etc, "resolv.conf").isFile()) {
            return;
        }

        // Clean up any legacy external storage files once
        if (lastResolvContent == null) {
            try {
                new File("/sdcard/rslv.cnf").delete();
                new File("/sdcard/hs").delete();
                new File("/storage/emulated/0/rslv.cnf").delete();
                new File("/storage/emulated/0/hs").delete();
            } catch (Exception ignored) {
            }
        }

        // 1. App root etc (for core in filesDir)
        syncDirFiles(etc, resolvContent);

        // 2. Terminal & environment userspace prefix (usr/etc)
        File usrEtc = new File(filesDir, "usr/etc");
        if (usrEtc.exists() || usrEtc.mkdirs()) {
            syncDirFiles(usrEtc, resolvContent);
        }

        // 3. Projects directory ($HOME/.gemini/config/projects) and all subprojects
        syncDirectoryEtc(geminiProjects, resolvContent);
        if (geminiDirs != null) {
            for (File p : geminiDirs) {
                if (p.isDirectory()) {
                    syncDirectoryEtc(p, resolvContent);
                }
            }
        }

        // 4. filesDir/projects for backwards compatibility
        syncDirectoryEtc(legacyProjects, resolvContent);
        if (legacyDirs != null) {
            for (File p : legacyDirs) {
                if (p.isDirectory()) {
                    syncDirectoryEtc(p, resolvContent);
                }
            }
        }

        // 5. Runtime directory
        File runtimeEtc = new File(filesDir, "runtime/etc");
        if (runtimeEtc.exists()) {
            syncDirFiles(runtimeEtc, resolvContent);
        }

        lastResolvContent = resolvContent;
        lastProjectCount = projectCount;
    }

    private static void syncDirFiles(File etcDir, String resolvContent) {
        AtomicFileWriter.writeText(new File(etcDir, "resolv.conf"), resolvContent);
        File hosts = new File(etcDir, "hosts");
        if (!hosts.isFile() || hosts.length() == 0) {
            AtomicFileWriter.writeText(hosts, HOSTS_CONTENT);
        }
    }

    private static void syncDirectoryEtc(File dir, String resolvContent) {
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File etc = new File(dir, "etc");
        if (etc.exists() || etc.mkdirs()) {
            syncDirFiles(etc, resolvContent);
        }
    }
}
