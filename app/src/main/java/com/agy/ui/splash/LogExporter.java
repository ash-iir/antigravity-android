package com.agy.ui.splash;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import com.agy.core.CoreRuntimeState;
import com.agy.runtime.RuntimeConfig;
import com.agy.util.SensitiveDataRedactor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Collects diagnostics and core logs for user export and debugging. */
public final class LogExporter {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_LOG_BYTES = 300_000;

    private LogExporter() {
    }

    public static String collectDiagnostics(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Antigravity Diagnostics ===\n");
        sb.append("Timestamp: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        String verName = RuntimeConfig.APP_VERSION_NAME;
        long verCode = RuntimeConfig.APP_VERSION_CODE;
        if (context != null) {
            try {
                android.content.pm.PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                verName = pi.versionName;
                verCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception ignored) {
            }
        }
        sb.append("App Version: ").append(verName).append(" (versionCode ").append(verCode).append(")\n");
        sb.append("Core Version: ").append(RuntimeConfig.CORE_VERSION).append("\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android OS: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Supported ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n");
        sb.append("Last Error: ").append(CoreRuntimeState.lastError != null
                ? SensitiveDataRedactor.redactLine(CoreRuntimeState.lastError) : "none").append("\n");
        sb.append("Server URL: ").append(CoreRuntimeState.serverUrl != null
                ? SensitiveDataRedactor.safeUrl(CoreRuntimeState.serverUrl) : "none").append("\n");
        sb.append("\n=== Core Log (files/logs/core.log) ===\n");

        if (context == null) {
            sb.append("(context is null; cannot locate log file)\n");
            return sb.toString();
        }

        File logFile = new File(context.getFilesDir(), "logs/core.log");
        if (!logFile.exists() || logFile.length() == 0) {
            sb.append("(logs/core.log is empty or does not exist)\n");
        } else {
            try {
                long len = logFile.length();
                long skipBytes = Math.max(0, len - MAX_LOG_BYTES);
                try (FileInputStream fis = new FileInputStream(logFile)) {
                    if (skipBytes > 0) {
                        long skipped = fis.skip(skipBytes);
                        sb.append("... [truncated ").append(skipped).append(" bytes; showing last ")
                                .append(len - skipped).append(" bytes of ").append(len).append(" total] ...\n");
                    }
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(fis, UTF8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(SensitiveDataRedactor.redactLine(line)).append("\n");
                        }
                    }
                }
            } catch (Exception e) {
                sb.append("Error reading log file: ").append(e.getMessage()).append("\n");
            }
        }

        File errFile = new File(context.getFilesDir(), "logs/core_stderr.log");
        if (errFile.exists() && errFile.length() > 0) {
            sb.append("\n=== Core Stderr Head (files/logs/core_stderr.log) ===\n");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(errFile), UTF8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count++ < 15) {
                    sb.append(SensitiveDataRedactor.redactLine(line)).append("\n");
                }
            } catch (Exception ignored) {
            }
        }

        File sigillLog = new File(context.getFilesDir(), "logs/sigill.log");
        if (!sigillLog.exists() || sigillLog.length() == 0) {
            sigillLog = new File(context.getFilesDir(), "sigill.log");
        }
        if ((!sigillLog.exists() || sigillLog.length() == 0) && new File("/data/data/com.agy/files/logs/sigill.log").exists()) {
            sigillLog = new File("/data/data/com.agy/files/logs/sigill.log");
        }
        if (sigillLog.exists() && sigillLog.length() > 0) {
            sb.append("\n=== SIGILL Log (files/logs/sigill.log) ===\n");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(sigillLog), UTF8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (Exception e) {
                sb.append("Error reading sigill log: ").append(e.getMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    public static void copyToClipboard(Context context) {
        if (context == null) {
            return;
        }
        try {
            String report = collectDiagnostics(context);
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("Antigravity Logs", report);
                cm.setPrimaryClip(clip);
                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "Failed to copy logs: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static void saveAndShare(Context context) {
        if (context == null) {
            return;
        }
        try {
            String report = collectDiagnostics(context);
            File savedFile = null;

            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
                File target = new File(downloadsDir, "antigravity-core.log");
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(target), UTF8)) {
                    writer.write(report);
                    savedFile = target;
                } catch (Exception ignored) {
                }
            }

            if (savedFile == null) {
                File direct = new File(Environment.getExternalStorageDirectory(), "Download/antigravity-core.log");
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(direct), UTF8)) {
                    writer.write(report);
                    savedFile = direct;
                } catch (Exception ignored) {
                }
            }

            if (savedFile == null) {
                File appExternal = context.getExternalFilesDir(null);
                if (appExternal != null && (appExternal.exists() || appExternal.mkdirs())) {
                    File target = new File(appExternal, "antigravity-core.log");
                    try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(target), UTF8)) {
                        writer.write(report);
                        savedFile = target;
                    } catch (Exception ignored) {
                    }
                }
            }

            if (savedFile != null) {
                Toast.makeText(context, "Logs saved to " + savedFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "Could not write logs to Downloads", Toast.LENGTH_SHORT).show();
            }

            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Antigravity Logs");
            if (report.length() < MAX_LOG_BYTES) {
                sendIntent.putExtra(Intent.EXTRA_TEXT, report);
            }
            sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(sendIntent, "Share logs");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(context, "Failed to export logs: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Exports and archives the user's projects directory to external storage so data is never lost. */
    public static void exportProjects(Context context) {
        if (context == null) {
            return;
        }
        File projectsDir = new File(context.getFilesDir(), "projects");
        if (!projectsDir.exists() || !projectsDir.isDirectory()) {
            Toast.makeText(context, "No projects directory found to backup", Toast.LENGTH_SHORT).show();
            return;
        }
        File[] children = projectsDir.listFiles();
        if (children == null || children.length == 0) {
            Toast.makeText(context, "Projects directory is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File savedZip = null;
            String zipName = "antigravity-projects-backup-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";

            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
                File target = new File(downloadsDir, zipName);
                if (writeZip(projectsDir, target)) {
                    savedZip = target;
                }
            }

            if (savedZip == null) {
                File appExternal = context.getExternalFilesDir(null);
                if (appExternal != null && (appExternal.exists() || appExternal.mkdirs())) {
                    File target = new File(appExternal, zipName);
                    if (writeZip(projectsDir, target)) {
                        savedZip = target;
                    }
                }
            }

            if (savedZip != null) {
                Toast.makeText(context, "Projects backed up to " + savedZip.getAbsolutePath(), Toast.LENGTH_LONG).show();
                try {
                    android.net.Uri uri = com.agy.util.AppFileProvider.getUriForFile(context, savedZip);
                    Intent sendIntent = new Intent(Intent.ACTION_SEND);
                    sendIntent.setType("application/zip");
                    sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Antigravity Projects Backup");
                    sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    Intent chooser = Intent.createChooser(sendIntent, "Backup Antigravity Projects");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(chooser);
                } catch (Exception ignored) {
                }
            } else {
                Toast.makeText(context, "Could not write backup to storage", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "Failed to backup projects: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean writeZip(File sourceDir, File zipFile) {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new FileOutputStream(zipFile))) {
            addDirToZip(sourceDir, "", zos);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void addDirToZip(File folder, String parentPath, java.util.zip.ZipOutputStream zos) throws java.io.IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                addDirToZip(file, (parentPath.isEmpty() ? "" : parentPath + "/") + file.getName(), zos);
            } else {
                String entryName = (parentPath.isEmpty() ? "" : parentPath + "/") + file.getName();
                zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        zos.write(buf, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }
}
