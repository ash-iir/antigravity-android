package com.agy.core;

import android.util.Log;

import com.agy.util.SensitiveDataRedactor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/** Appends core logs to rotating files under files/logs/. */
public final class CoreLogWriter {

    private static final String TAG = "CoreLogWriter";
    public static final long DEFAULT_MAX_BYTES = 1_500_000L;
    public static final long STDERR_MAX_BYTES = 512_000L;

    private CoreLogWriter() {
    }

    public static Writer open(File filesDir) {
        return open(filesDir, "core.log", DEFAULT_MAX_BYTES);
    }

    public static Writer open(File filesDir, String filename, long maxBytes) {
        try {
            File dir = new File(filesDir, "logs");
            dir.mkdirs();
            File logFile = new File(dir, filename);
            if (logFile.exists() && logFile.length() > maxBytes) {
                File oldLog = new File(dir, filename + ".old");
                if (oldLog.exists()) {
                    oldLog.delete();
                }
                logFile.renameTo(oldLog);
            }
            return new OutputStreamWriter(new FileOutputStream(logFile, true),
                    Charset.forName("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "cannot open log " + filename, e);
            return null;
        }
    }

    public static void writeLine(Writer log, String line) {
        if (log == null || line == null) {
            return;
        }
        try {
            log.write(SensitiveDataRedactor.redactLine(line));
            log.write('\n');
            log.flush();
        } catch (Exception ignored) {
        }
    }

    public static void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
