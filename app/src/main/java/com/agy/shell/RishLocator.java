package com.agy.shell;

import android.content.Context;

import java.io.File;

/** Locates the bundled rish binary. */
public final class RishLocator {

    private final Context appContext;

    public RishLocator(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** The bundled rish lives next to the core: getFilesDir()/runtime/bin/rish. */
    public File rishBinary() {
        File files = appContext.getFilesDir();
        File[] candidates = new File[]{
                new File(files, "runtime/bin/rish"),
                new File(files, "usr/bin/rish"),
                new File(files, "bin/rish")
        };
        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate;
            }
        }
        return candidates[0];
    }

    /** True if rish_shizuku.dex exists in any directory searched by the bundled rish script. */
    public boolean hasDex() {
        File files = appContext.getFilesDir();
        File rishDir = rishBinary().getParentFile();
        File[] candidates = new File[]{
                rishDir != null ? new File(rishDir, "rish_shizuku.dex") : null,
                rishDir != null ? new File(rishDir, "../share/rish/rish_shizuku.dex") : null,
                new File(files, "runtime/bin/rish_shizuku.dex"),
                new File(files, "usr/share/rish/rish_shizuku.dex"),
                new File(files, "usr/bin/rish_shizuku.dex"),
                new File(files, "bin/rish_shizuku.dex")
        };
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile() && candidate.length() > 0) {
                return true;
            }
        }
        return false;
    }
}
