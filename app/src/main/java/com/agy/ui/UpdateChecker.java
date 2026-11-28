package com.agy.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.agy.runtime.RuntimeConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight GitHub Release update checker targeting salmanbappi/antigravity-android.
 *
 * <p>Passively checks at most once every 24 hours after UI ready (respecting skipped
 * versions).
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String RELEASES_API_URL =
            "https://api.github.com/repos/salmanbappi/antigravity-android/releases/latest";
    private static final String RELEASES_PAGE_URL =
            "https://github.com/salmanbappi/antigravity-android/releases";
    private static final String PREFS_NAME = "agy_update_checker";
    private static final String KEY_LAST_CHECK_MS = "last_check_ms";
    private static final String KEY_SKIPPED_TAG = "skipped_tag";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L; // 24 hours
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static final AtomicBoolean LAUNCH_CHECKED = new AtomicBoolean(false);

    public static final class ReleaseInfo {
        public final String tagName;
        public final String htmlUrl;
        public final String standardApkUrl;
        public final String bypassApkUrl;
        public final String changelogPreview;

        public ReleaseInfo(String tagName, String htmlUrl, String standardApkUrl,
                           String bypassApkUrl, String changelogPreview) {
            this.tagName = tagName != null ? tagName : "";
            this.htmlUrl = htmlUrl != null && !htmlUrl.isEmpty() ? htmlUrl : RELEASES_PAGE_URL;
            this.standardApkUrl = standardApkUrl != null ? standardApkUrl : "";
            this.bypassApkUrl = bypassApkUrl != null ? bypassApkUrl : "";
            this.changelogPreview = changelogPreview != null ? changelogPreview : "";
        }
    }

    private UpdateChecker() {
    }

    /** Passive background check called once per app launch, throttled to once per 24h. */
    public static void checkOnLaunch(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (!LAUNCH_CHECKED.compareAndSet(false, true)) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_CHECK_MS, 0L);
        if (now - last < AUTO_CHECK_INTERVAL_MS) {
            return;
        }
        checkAsync(activity, false);
    }

    /** Manual check trigger that always probes GitHub and shows feedback. */
    public static void checkManually(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        Toast.makeText(activity, "Checking for updates...", Toast.LENGTH_SHORT).show();
        checkAsync(activity, true);
    }

    private static void checkAsync(final Activity activity, final boolean manual) {
        final Context appCtx = activity.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final ReleaseInfo info = fetchLatestRelease();
                    SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs.edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply();

                    if (info == null || info.tagName.isEmpty()) {
                        if (manual) {
                            postToast(main, appCtx, "No releases found on GitHub yet.");
                        }
                        return;
                    }

                    final String current = RuntimeConfig.APP_VERSION_NAME;
                    final boolean newer = compareVersions(info.tagName, current) > 0;
                    if (!newer) {
                        if (manual) {
                            postToast(main, appCtx, "You're up to date (v" + current + ")");
                        }
                        return;
                    }

                    String skipped = prefs.getString(KEY_SKIPPED_TAG, "");
                    if (!manual && info.tagName.equals(skipped)) {
                        return;
                    }

                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!activity.isFinishing()) {
                                showUpdateDialog(activity, info, current);
                            }
                        }
                    });
                } catch (final Exception e) {
                    Log.d(TAG, "update check skipped/failed: " + e.getMessage());
                    if (manual) {
                        postToast(main, appCtx, "Update check failed: " + e.getMessage());
                    }
                }
            }
        }, "AgyUpdateChecker");
        t.setDaemon(true);
        t.start();
    }

    private static ReleaseInfo fetchLatestRelease() throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(RELEASES_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "Antigravity-Android/" + RuntimeConfig.APP_VERSION_NAME);

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("GitHub HTTP " + code);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject obj = new JSONObject(sb.toString());
            String tag = obj.optString("tag_name", "").trim();
            String htmlUrl = obj.optString("html_url", RELEASES_PAGE_URL).trim();
            String body = obj.optString("body", "");

            String standardApk = "";
            String bypassApk = "";
            JSONArray assets = obj.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "").toLowerCase(Locale.US);
                    String dl = a.optString("browser_download_url", "");
                    if ("antigravity.apk".equals(name)) {
                        standardApk = dl;
                    } else if (name.contains("bypass") && name.endsWith(".apk")) {
                        bypassApk = dl;
                    }
                }
            }

            String preview = extractChangelogPreview(body);
            return new ReleaseInfo(tag, htmlUrl, standardApk, bypassApk, preview);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String extractChangelogPreview(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "A newer release is available on GitHub.";
        }
        StringBuilder out = new StringBuilder();
        String[] lines = body.split("\\r?\\n");
        int count = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("<") || line.startsWith("---")
                    || line.startsWith("|") || line.startsWith(">")) {
                continue;
            }
            if (line.startsWith("*") || line.startsWith("-") || line.startsWith("###")) {
                String clean = line.replace("**", "").replace("`", "");
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(clean);
                count++;
                if (count >= 6 || out.length() > 420) {
                    break;
                }
            }
        }
        return out.length() > 0 ? out.toString() : "A newer release is available on GitHub.";
    }

    private static void showUpdateDialog(final Activity activity, final ReleaseInfo info, String current) {
        String message = "Current: v" + current + " (Core " + RuntimeConfig.CORE_VERSION + ")\n"
                + "Latest:  " + info.tagName + "\n\n"
                + info.changelogPreview;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Update Available (" + info.tagName + ")")
                .setMessage(message)
                .setPositiveButton("Download APK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String target = !info.standardApkUrl.isEmpty() ? info.standardApkUrl : info.htmlUrl;
                        openUrl(activity, target);
                    }
                })
                .setNeutralButton("Release Page", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openUrl(activity, info.htmlUrl);
                    }
                })
                .setNegativeButton("Skip", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        prefs.edit().putString(KEY_SKIPPED_TAG, info.tagName).apply();
                    }
                });
        builder.show();
    }

    private static void openUrl(Activity activity, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(activity, "Cannot open URL: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Compares semantic version strings like "v2.34.15" vs "2.34.11". Returns >0 if a > b. */
    public static int compareVersions(String a, String b) {
        int[] pa = parseVersionParts(a);
        int[] pb = parseVersionParts(b);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int[] parseVersionParts(String raw) {
        if (raw == null) {
            return new int[]{0};
        }
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int dash = s.indexOf('-');
        if (dash >= 0) {
            s = s.substring(0, dash);
        }
        String[] tokens = s.split("\\.");
        int[] nums = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                nums[i] = Integer.parseInt(tokens[i].replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
                nums[i] = 0;
            }
        }
        return nums;
    }

    private static void postToast(Handler main, final Context ctx, final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
