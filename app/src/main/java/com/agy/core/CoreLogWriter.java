package com.agy.core;

import android.util.Log;

import com.agy.util.SensitiveDataRedactor;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Buffered, in-session rotating log writer under {@code files/logs/}.
 * Enforces {@link #maxBytes} continuously during long sessions instead of
 * rotating only at process launch.
 */
public final class CoreLogWriter {

    private static final String TAG = "CoreLogWriter";
    public static final long DEFAULT_MAX_BYTES = 1_500_000L;
    public static final long STDERR_MAX_BYTES = 512_000L;
    private static final int BUFFER_CHARS = 8192;
    private static final int FLUSH_LINE_INTERVAL = 16;
    private static final long FLUSH_TIME_INTERVAL_MS = 1000L;

    private CoreLogWriter() {
    }

    public static final class RotatingWriter implements Closeable {
        private final File dir;
        private final String filename;
        private final long maxBytes;
        private final boolean capSingleSession;
        private BufferedWriter writer;
        private long currentFileBytes;
        private long sessionBytes;
        private int pendingLines;
        private long lastFlushMs;
        private boolean closed;

        RotatingWriter(File filesDir, String filename, long maxBytes, boolean capSingleSession) {
            this.dir = new File(filesDir, "logs");
            this.filename = filename;
            this.maxBytes = maxBytes;
            this.capSingleSession = capSingleSession;
            this.lastFlushMs = System.currentTimeMillis();
            openUnderlying();
        }

        private void openUnderlying() {
            try {
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File logFile = new File(dir, filename);
                if (logFile.exists() && logFile.length() >= maxBytes) {
                    rotateFile(logFile);
                }
                this.currentFileBytes = logFile.exists() ? logFile.length() : 0L;
                this.writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8),
                        BUFFER_CHARS);
            } catch (Exception e) {
                Log.w(TAG, "cannot open log " + filename, e);
                this.writer = null;
            }
        }

        private void rotateFile(File logFile) {
            File oldLog = new File(dir, filename + ".old");
            if (oldLog.exists()) {
                oldLog.delete();
            }
            logFile.renameTo(oldLog);
        }

        /** Writes an already-redacted line. */
        public synchronized void writeRedactedLine(String safeLine) {
            if (closed || writer == null || safeLine == null) {
                return;
            }
            if (capSingleSession && sessionBytes >= maxBytes) {
                return;
            }
            try {
                long lineLen = safeLine.length() + 1L;
                if (!capSingleSession && currentFileBytes + lineLen > maxBytes) {
                    writer.flush();
                    writer.close();
                    writer = null;
                    File logFile = new File(dir, filename);
                    rotateFile(logFile);
                    openUnderlying();
                    if (writer == null) {
                        return;
                    }
                }
                writer.write(safeLine);
                writer.write('\n');
                currentFileBytes += lineLen;
                sessionBytes += lineLen;
                pendingLines++;
                long now = System.currentTimeMillis();
                if (pendingLines >= FLUSH_LINE_INTERVAL || (now - lastFlushMs) >= FLUSH_TIME_INTERVAL_MS) {
                    writer.flush();
                    pendingLines = 0;
                    lastFlushMs = now;
                }
            } catch (Exception ignored) {
            }
        }

        /** Redacts and writes a line immediately (used for lifecycle/error lines). */
        public void writeLine(String rawLine) {
            if (rawLine == null) {
                return;
            }
            writeRedactedLine(SensitiveDataRedactor.redactLine(rawLine));
            flushQuietly();
        }

        public synchronized void flushQuietly() {
            if (writer != null && !closed) {
                try {
                    writer.flush();
                    pendingLines = 0;
                    lastFlushMs = System.currentTimeMillis();
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (writer != null) {
                try {
                    writer.flush();
                } catch (Exception ignored) {
                }
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
                writer = null;
            }
        }
    }

    public static RotatingWriter open(File filesDir) {
        return new RotatingWriter(filesDir, "core.log", DEFAULT_MAX_BYTES, false);
    }

    public static RotatingWriter open(File filesDir, String filename, long maxBytes) {
        boolean cap = "core_stderr.log".equals(filename);
        return new RotatingWriter(filesDir, filename, maxBytes, cap);
    }

    public static void writeLine(RotatingWriter log, String line) {
        if (log != null) {
            log.writeLine(line);
        }
    }

    public static void writeRedactedLine(RotatingWriter log, String safeLine) {
        if (log != null) {
            log.writeRedactedLine(safeLine);
        }
    }

    public static void closeQuietly(Closeable writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
