package com.agy.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.agy.core.CoreRuntimeState;
import com.agy.runtime.RuntimeConfig;
import com.agy.shell.RootBridge;
import com.agy.shell.RootManagerInfo;
import com.agy.shell.RootResilience;
import com.agy.shell.ShizukuBridge;
import com.agy.theme.ThemeManager;
import com.agy.ui.splash.LogExporter;

/**
 * Compact draggable edge pill + native Quick-Control & Diagnostics sheet.
 * Gives one-tap access to Root/Shizuku status & re-check, Anti-Kill protection,
 * Desktop Mode, Core Restart, GitHub Updates, and Log Export without leaving the workspace.
 * Supports a tucked edge-tab mode and session hiding so it never blocks UI controls.
 */
public final class QuickControlSheet {

    private static final String PREFS_NAME = "agy_quick_control";
    private static final String KEY_PILL_Y = "pill_top_margin_px";
    private static final String KEY_PILL_TUCKED = "pill_tucked";
    private static final float ALPHA_PILL_IDLE = 0.55f;
    private static final float ALPHA_TUCKED_IDLE = 0.34f;
    private static final float ALPHA_ACTIVE = 0.95f;

    private final MainActivity activity;
    private final FrameLayout rootLayout;
    private final ThemeManager themeManager;
    private final RootBridge rootBridge;
    private final ShizukuBridge shizukuBridge;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView pillView;
    private View sheetOverlay;
    private boolean pillAttached = false;

    public QuickControlSheet(MainActivity activity, FrameLayout rootLayout,
                             ThemeManager themeManager, RootBridge rootBridge,
                             ShizukuBridge shizukuBridge) {
        this.activity = activity;
        this.rootLayout = rootLayout;
        this.themeManager = themeManager;
        this.rootBridge = rootBridge;
        this.shizukuBridge = shizukuBridge;
    }

    /** Attaches the compact edge pill once the main UI is visible. */
    public void attachPill() {
        if (pillAttached || activity.isFinishing()) {
            return;
        }
        pillAttached = true;

        final TextView pill = new TextView(activity);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);

        int savedTop = loadPillTopMargin();
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = savedTop;

        this.pillView = pill;
        applyPillAppearance(pill, lp);

        pill.setOnTouchListener(new View.OnTouchListener() {
            private float downRawY;
            private int startTop;
            private boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = event.getRawY();
                        startTop = lp.topMargin;
                        dragged = false;
                        v.setAlpha(ALPHA_ACTIVE);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dy) > dp(6)) {
                            dragged = true;
                        }
                        if (dragged) {
                            int maxTop = Math.max(dp(48), rootLayout.getHeight() - dp(96));
                            int nextTop = Math.max(dp(36), Math.min(maxTop, startTop + Math.round(dy)));
                            lp.topMargin = nextTop;
                            v.setLayoutParams(lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setAlpha(isPillTucked() ? ALPHA_TUCKED_IDLE : ALPHA_PILL_IDLE);
                        if (dragged) {
                            savePillTopMargin(lp.topMargin);
                        } else {
                            openSheet();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(isPillTucked() ? ALPHA_TUCKED_IDLE : ALPHA_PILL_IDLE);
                        return true;
                    default:
                        return false;
                }
            }
        });

        rootLayout.addView(pill, lp);
    }

    public void refreshTheme() {
        if (pillView != null) {
            ViewGroup.LayoutParams rawLp = pillView.getLayoutParams();
            FrameLayout.LayoutParams lp = rawLp instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) rawLp : null;
            applyPillAppearance(pillView, lp);
        }
    }

    public boolean isSheetOpen() {
        return sheetOverlay != null;
    }

    public void closeSheet() {
        if (sheetOverlay != null) {
            try {
                rootLayout.removeView(sheetOverlay);
            } catch (Throwable ignored) {
            }
            sheetOverlay = null;
        }
    }

    public void destroy() {
        closeSheet();
        if (pillView != null) {
            try {
                rootLayout.removeView(pillView);
            } catch (Throwable ignored) {
            }
            pillView = null;
        }
        pillAttached = false;
    }

    public void openSheet() {
        if (activity.isFinishing()) {
            return;
        }
        closeSheet();

        boolean dark = themeManager != null && "dark".equals(themeManager.getTheme());
        int scrimCol = Color.parseColor("#88000000");
        int cardBg = dark ? Color.parseColor("#FA121722") : Color.parseColor("#FAFFFFFF");
        int borderCol = dark ? Color.parseColor("#334155") : Color.parseColor("#CBD5E1");
        int titleCol = dark ? Color.parseColor("#F8FAFC") : Color.parseColor("#0F172A");
        int bodyCol = dark ? Color.parseColor("#CBD5E1") : Color.parseColor("#334155");
        int mutedCol = dark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B");
        int btnBg = dark ? Color.parseColor("#1E293B") : Color.parseColor("#F1F5F9");
        int accentCol = dark ? Color.parseColor("#38BDF8") : Color.parseColor("#0284C7");

        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(scrimCol);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
            }
        });

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable cardDrawable = new GradientDrawable();
        cardDrawable.setColor(cardBg);
        cardDrawable.setCornerRadius(dp(18));
        cardDrawable.setStroke(dp(1), borderCol);
        card.setBackground(cardDrawable);

        // Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText("Antigravity Control  v" + RuntimeConfig.APP_VERSION_NAME);
        title.setTextColor(titleCol);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        TextView closeBtn = new TextView(activity);
        closeBtn.setText("Close");
        closeBtn.setTextColor(accentCol);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
            }
        });
        header.addView(closeBtn);
        card.addView(header);

        // Status info block
        final TextView statusView = new TextView(activity);
        statusView.setTextColor(bodyCol);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusView.setText(formatStatusText(rootBridge != null ? rootBridge.getCachedOrFastInfo() : null));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(10);
        stLp.bottomMargin = dp(12);
        card.addView(statusView, stLp);

        // Action buttons
        card.addView(makeSheetButton("Re-check Root Access", btnBg, accentCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rootBridge == null) return;
                statusView.setText("Probing root access...");
                rootBridge.probeAsync(true, new RootBridge.ProbeCallback() {
                    @Override
                    public void onProbeComplete(final RootManagerInfo info) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (sheetOverlay != null) {
                                    statusView.setText(formatStatusText(info));
                                }
                                String msg = info.granted
                                        ? "Root active (" + info.managerType + ", uid=0)"
                                        : "Not granted — grant in KernelSU/bakaSU/Magisk or allow in Sui";
                                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        }));

        card.addView(makeSheetButton("Apply Anti-Kill Protection (Doze + OOM)", btnBg, titleCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(activity, "Applying Doze whitelist & OOM protection...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final RootResilience.ProtectionResult res = RootResilience.applyProtection(activity, rootBridge);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(activity,
                                        res.rootGranted ? ("Protected: " + res.summary) : "Root required for Anti-Kill protection",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }, "QuickProtect").start();
            }
        }));

        final boolean desktopOn = activity.isDesktopMode();
        card.addView(makeSheetButton("Desktop Mode: " + (desktopOn ? "ON (tap to disable)" : "OFF (tap to enable)"),
                btnBg, titleCol, borderCol, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean next = !activity.isDesktopMode();
                        activity.setDesktopMode(next);
                        closeSheet();
                        Toast.makeText(activity, "Desktop Mode " + (next ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
                    }
                }));

        card.addView(makeSheetButton("Restart Core Server", btnBg, titleCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
                activity.restartCoreFromUser();
            }
        }));

        final boolean wakeLockOn = com.agy.core.CoreServerService.isKeepWakeLockEnabled(activity);
        card.addView(makeSheetButton(
                "Background WakeLock: " + (wakeLockOn ? "ON (tap to save battery)" : "OFF (tap to keep CPU awake)"),
                btnBg, titleCol, borderCol, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean next = !com.agy.core.CoreServerService.isKeepWakeLockEnabled(activity);
                        com.agy.core.CoreServerService.setKeepWakeLockEnabled(activity, next);
                        closeSheet();
                        Toast.makeText(activity,
                                "Background WakeLock " + (next ? "enabled" : "disabled"),
                                Toast.LENGTH_SHORT).show();
                    }
                }));

        card.addView(makeSheetButton("Check for Updates (GitHub)", btnBg, accentCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
                UpdateChecker.checkManually(activity);
            }
        }));

        card.addView(makeSheetButton("Copy Diagnostics Logs", btnBg, titleCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogExporter.copyToClipboard(activity);
            }
        }));

        card.addView(makeSheetButton("Save & Share Logs", btnBg, titleCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
                LogExporter.saveAndShare(activity);
            }
        }));

        final boolean tuckedNow = isPillTucked();
        card.addView(makeSheetButton(
                tuckedNow ? "Floating Pill: Tucked Edge Tab (tap for Full AGY)"
                          : "Floating Pill: Full AGY (tap to Tuck to Edge)",
                btnBg, mutedCol, borderCol, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean next = !isPillTucked();
                        setPillTucked(next);
                        refreshTheme();
                        closeSheet();
                        Toast.makeText(activity,
                                next ? "AGY pill tucked to screen edge" : "AGY pill restored",
                                Toast.LENGTH_SHORT).show();
                    }
                }));

        TextView hideBtn = new TextView(activity);
        hideBtn.setText("Hide AGY pill until next launch");
        hideBtn.setTextColor(mutedCol);
        hideBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hideBtn.setGravity(Gravity.CENTER);
        hideBtn.setPadding(dp(8), dp(8), dp(8), dp(2));
        hideBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
                if (pillView != null) {
                    pillView.setVisibility(View.GONE);
                }
            }
        });
        card.addView(hideBtn);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(card);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.BOTTOM;
        int m = dp(12);
        cardLp.setMargins(m, dp(48), m, dp(16));
        scrim.addView(scroll, cardLp);

        this.sheetOverlay = scrim;
        rootLayout.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private String formatStatusText(RootManagerInfo info) {
        StringBuilder sb = new StringBuilder();
        boolean coreUp = CoreRuntimeState.serverUrl != null;
        sb.append("Core: ").append(coreUp ? ("Running (:" + CoreRuntimeState.httpsPort + ")") : "Starting / Stopped");
        sb.append(com.agy.core.CoreServerService.isKeepWakeLockEnabled(activity) ? " [WakeLock]" : "");
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024L * 1024L);
        sb.append("  •  JVM Heap: ").append(usedMb).append(" MB\n");

        if (info != null && info.granted) {
            sb.append("Root: GRANTED (").append(info.managerType);
            if (info.mountMasterSupported) {
                sb.append(", -mm");
            }
            sb.append(")");
            if (!info.suVersion.isEmpty()) {
                sb.append("\nsu: ").append(info.suVersion);
            }
        } else {
            sb.append("Root: Not granted (optional)");
        }
        if (shizukuBridge != null) {
            sb.append("\nShizuku (rish): ").append(shizukuBridge.isAvailable()
                    ? "Ready"
                    : (shizukuBridge.isDexPresent() ? "Unavailable" : "Missing DEX"));
        }
        return sb.toString();
    }

    private View makeSheetButton(String label, int bgColor, int textColor, int borderColor, View.OnClickListener onClick) {
        TextView btn = new TextView(activity);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btn.setPadding(dp(14), dp(11), dp(14), dp(11));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(1), borderColor);
        btn.setBackground(d);
        btn.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void applyPillAppearance(TextView pill, FrameLayout.LayoutParams lp) {
        boolean dark = themeManager != null && "dark".equals(themeManager.getTheme());
        boolean tucked = isPillTucked();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? Color.parseColor("#CC1E293B") : Color.parseColor("#CCF1F5F9"));
        bg.setStroke(dp(1), dark ? Color.parseColor("#475569") : Color.parseColor("#94A3B8"));

        if (tucked) {
            pill.setText("");
            pill.setAlpha(ALPHA_TUCKED_IDLE);
            pill.setPadding(0, 0, 0, 0);
            float r = dp(8);
            bg.setCornerRadii(new float[]{r, r, 0f, 0f, 0f, 0f, r, r});
            if (lp != null) {
                lp.width = dp(10);
                lp.height = dp(40);
                lp.rightMargin = 0;
                pill.setLayoutParams(lp);
            }
        } else {
            pill.setText("AGY");
            pill.setAlpha(ALPHA_PILL_IDLE);
            pill.setPadding(dp(9), dp(6), dp(9), dp(6));
            bg.setCornerRadius(dp(16));
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.rightMargin = dp(6);
                pill.setLayoutParams(lp);
            }
        }
        pill.setBackground(bg);
        pill.setTextColor(dark ? Color.parseColor("#38BDF8") : Color.parseColor("#0284C7"));
    }

    private boolean isPillTucked() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_PILL_TUCKED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void setPillTucked(boolean tucked) {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_PILL_TUCKED, tucked).apply();
        } catch (Throwable ignored) {
        }
    }

    private int loadPillTopMargin() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getInt(KEY_PILL_Y, dp(96));
        } catch (Throwable ignored) {
            return dp(96);
        }
    }

    private void savePillTopMargin(int px) {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putInt(KEY_PILL_Y, px).apply();
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
    }
}
