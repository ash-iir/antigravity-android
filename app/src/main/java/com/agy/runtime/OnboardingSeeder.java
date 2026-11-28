package com.agy.runtime;

import android.content.Context;
import android.util.Log;

import com.agy.util.AtomicFileWriter;

import java.io.File;
import java.io.InputStream;

/**
 * Materializes a fully-onboarded state before the core starts, so the Welcome
 * wizard is skipped. Idempotent and non-destructive: only writes when the
 * target is absent (or a zero-byte stub).
 */
public final class OnboardingSeeder {

    private static final String TAG = "OnboardingSeeder";

    private OnboardingSeeder() {
    }

    public static void seed(Context appContext, File appData, File projects) {
        try {
            if (!appData.exists() && !appData.mkdirs()) {
                Log.w(TAG, "cannot create " + appData);
                return;
            }
            seedStateFile(appContext, "jetski_state.pbtxt", new File(appData, "jetski_state.pbtxt"));
            seedStateFile(appContext, "antigravity_state.pbtxt",
                    new File(appData, "antigravity_state.pbtxt"));

            // Seed default agent rules (AGENTS.md) into ~/.gemini/config/AGENTS.md,
            // refreshing whenever APP_VERSION_CODE changes so app updates ship updated rules.
            File configDir = projects.getParentFile();
            if (configDir != null && (configDir.isDirectory() || configDir.mkdirs())) {
                seedVersionedFile(appContext, "AGENTS.md",
                        new File(configDir, "AGENTS.md"),
                        new File(configDir, ".agents_seed_version"));
            }

            // The project store resolves its projects here; without the
            // pseudo-project file it logs project_store_get_file_missing.
            if (!projects.exists() && !projects.mkdirs()) {
                Log.w(TAG, "cannot create " + projects);
                return;
            }
            File outside = new File(projects, "outside-of-project.json");
            if (!outside.isFile()) {
                AtomicFileWriter.writeText(outside, "{}\n");
            }

            // Seed ~/.profile and ~/.bashrc into $HOME (getFilesDir()) if missing or empty
            File homeDir = appContext.getFilesDir();
            File profile = new File(homeDir, ".profile");
            if (!profile.isFile() || profile.length() == 0) {
                String profileContent = "# Antigravity Shell Profile\n"
                        + "if [ -n \"$PREFIX\" ] && [ -f \"$PREFIX/etc/profile\" ]; then\n"
                        + "    . \"$PREFIX/etc/profile\"\n"
                        + "elif [ -f \"$HOME/usr/etc/profile\" ]; then\n"
                        + "    . \"$HOME/usr/etc/profile\"\n"
                        + "fi\n"
                        + "if [ -f \"$HOME/.bashrc\" ]; then\n"
                        + "    . \"$HOME/.bashrc\"\n"
                        + "fi\n";
                AtomicFileWriter.writeText(profile, profileContent);
            }

            File bashrc = new File(homeDir, ".bashrc");
            if (!bashrc.isFile() || bashrc.length() == 0) {
                String bashrcContent = "# Antigravity Interactive Bash Config\n"
                        + "if [ -n \"$PREFIX\" ] && [ -f \"$PREFIX/etc/bash.bashrc\" ]; then\n"
                        + "    . \"$PREFIX/etc/bash.bashrc\"\n"
                        + "elif [ -f \"$HOME/usr/etc/bash.bashrc\" ]; then\n"
                        + "    . \"$HOME/usr/etc/bash.bashrc\"\n"
                        + "fi\n";
                AtomicFileWriter.writeText(bashrc, bashrcContent);
            }
        } catch (Exception e) {
            Log.w(TAG, "onboarding seed failed (continuing): " + e.getMessage());
        }
    }

    private static void seedVersionedFile(Context appContext, String assetName, File target, File stamp)
            throws Exception {
        String currentVersion = String.valueOf(com.agy.BuildConfig.APP_VERSION_CODE);
        String stampedVersion = "";
        if (stamp.isFile()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(stamp)) {
                stampedVersion = com.agy.util.IoUtil.readAll(fis, "UTF-8").trim();
            } catch (Exception ignored) {
            }
        }
        if (target.isFile() && target.length() > 0 && currentVersion.equals(stampedVersion)) {
            Log.i(TAG, "versioned seed up to date (" + currentVersion + "): " + target);
            return;
        }
        try (InputStream in = appContext.getAssets().open(
                RuntimePaths.SEED_ASSET + "/" + assetName)) {
            String content = com.agy.util.IoUtil.readAll(in, "UTF-8");
            AtomicFileWriter.writeText(target, content);
        }
        AtomicFileWriter.writeText(stamp, currentVersion + "\n");
        Log.i(TAG, "updated versioned seed (" + currentVersion + "): " + target);
    }

    private static void seedStateFile(Context appContext, String assetName, File target)
            throws Exception {
        if (target.isFile() && target.length() > 0) {
            Log.i(TAG, "state present; left untouched: " + target);
            return;
        }
        try (InputStream in = appContext.getAssets().open(
                RuntimePaths.SEED_ASSET + "/" + assetName)) {
            String content = com.agy.util.IoUtil.readAll(in, "UTF-8");
            AtomicFileWriter.writeText(target, content);
        }
        Log.i(TAG, "seeded onboarding state (was missing): " + target);
    }
}
