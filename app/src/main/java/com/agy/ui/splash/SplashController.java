package com.agy.ui.splash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.agy.theme.ThemeManager;
import com.agy.util.ViewParams;

/**
 * Owns the splash overlay: uses a lightweight native Canvas view
 * ({@link NativeSplashDotsView}) for the normal loading state to avoid spawning
 * a second Chromium WebView (~40 MB RAM saved), and lazily creates a splash
 * WebView only when transient restart or fatal error overlays are needed.
 */
public final class SplashController {

    public static final int MODE_LOADING = 0;
    public static final int MODE_TRANSIENT = 1;
    public static final int MODE_ERROR = 2;

    private final Context context;
    private final ThemeManager theme;
    private final FrameLayout root;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout container;
    private NativeSplashDotsView nativeDotsView;
    private WebView overlayWebView;
    private SplashRetryListener retryListener;
    private boolean dismissed = false;
    private boolean fatalShown = false;
    private int mode = -1;

    public SplashController(Context context, ThemeManager theme, FrameLayout root) {
        this.context = context;
        this.theme = theme;
        this.root = root;
        ensureContainer();
    }

    private FrameLayout ensureContainer() {
        if (container != null) {
            if (container.getParent() == null && root != null) {
                root.addView(container, ViewParams.full());
            }
            return container;
        }
        container = new FrameLayout(context);
        container.setBackgroundColor(theme.getBackgroundColor());
        nativeDotsView = new NativeSplashDotsView(context);
        nativeDotsView.setDark(theme.isDark());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        container.addView(nativeDotsView, lp);
        if (root != null) {
            root.addView(container, ViewParams.full());
        }
        return container;
    }

    private WebView ensureOverlayWebView() {
        ensureContainer();
        if (overlayWebView != null) {
            return overlayWebView;
        }
        WebView wv = new WebView(context);
        wv.setBackgroundColor(Color.TRANSPARENT);
        android.webkit.WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        if (retryListener != null) {
            SplashRetryBridge.bind(wv, context, retryListener);
        }
        container.addView(wv, ViewParams.full());
        overlayWebView = wv;
        return wv;
    }

    private void destroyOverlayWebView() {
        WebView wv = overlayWebView;
        if (wv == null) {
            return;
        }
        overlayWebView = null;
        try {
            wv.setVisibility(View.GONE);
            wv.stopLoading();
            wv.loadUrl("about:blank");
            wv.onPause();
        } catch (Throwable ignored) {
        }
        try {
            ViewParent parent = wv.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(wv);
            }
        } catch (Throwable ignored) {
        }
        try {
            wv.removeAllViews();
            wv.destroy();
        } catch (Throwable ignored) {
        }
    }

    public View view() {
        return ensureContainer();
    }

    public int mode() {
        return mode;
    }

    public boolean isFatalShown() {
        return fatalShown;
    }

    public void setRetryListener(SplashRetryListener listener) {
        this.retryListener = listener;
        if (overlayWebView != null) {
            SplashRetryBridge.bind(overlayWebView, context, listener);
        }
    }

    /** First render of the loading splash (pure native Canvas, zero extra WebView). */
    public void loadInitial() {
        FrameLayout c = ensureContainer();
        mode = MODE_LOADING;
        c.setBackgroundColor(theme.getBackgroundColor());
        if (nativeDotsView != null) {
            nativeDotsView.setDark(theme.isDark());
            nativeDotsView.setVisibility(View.VISIBLE);
            nativeDotsView.startAnimation();
        }
        destroyOverlayWebView();
    }

    /** Show the animated loading splash again (startup or post-restart ready). */
    public void showLoading() {
        FrameLayout c = setVisible();
        if (mode != MODE_LOADING) {
            mode = MODE_LOADING;
            c.setBackgroundColor(theme.getBackgroundColor());
            if (nativeDotsView != null) {
                nativeDotsView.setDark(theme.isDark());
                nativeDotsView.setVisibility(View.VISIBLE);
                nativeDotsView.startAnimation();
            }
            destroyOverlayWebView();
        }
    }

    /** Non-fatal status (auto-restart running): overlay without a Retry button. */
    public void showTransient(String message) {
        if (fatalShown) {
            return;
        }
        setVisible();
        if (mode != MODE_TRANSIENT) {
            mode = MODE_TRANSIENT;
            if (nativeDotsView != null) {
                nativeDotsView.stopAnimation();
                nativeDotsView.setVisibility(View.GONE);
            }
            WebView wv = ensureOverlayWebView();
            SplashOverlayHtml.show(wv, "Restarting Antigravity", message,
                    foreground(), background(), false);
        }
    }

    /** Fail-closed startup failure: readable message with a Retry action. */
    public void showFatalError(String message) {
        if (fatalShown) {
            return;
        }
        fatalShown = true;
        mode = MODE_ERROR;
        setVisible();
        if (nativeDotsView != null) {
            nativeDotsView.stopAnimation();
            nativeDotsView.setVisibility(View.GONE);
        }
        WebView wv = ensureOverlayWebView();
        SplashRetryBridge.bind(wv, context, retryListener);
        SplashOverlayHtml.show(wv, "Antigravity failed to start", message,
                foreground(), background(), retryListener != null);
    }

    /** Called when a fresh ready generation begins; errors can be shown again. */
    public void clearFatal() {
        fatalShown = false;
    }

    public FrameLayout setVisible() {
        dismissed = false;
        FrameLayout c = ensureContainer();
        c.animate().cancel();
        c.setAlpha(1f);
        if (c.getVisibility() != View.VISIBLE) {
            c.setVisibility(View.VISIBLE);
        }
        if (root != null) {
            root.bringChildToFront(c);
        }
        return c;
    }

    /** Idempotent; fades out then tears down splash resources on the main thread. */
    public void dismiss() {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (dismissed) {
                    return;
                }
                dismissed = true;
                final FrameLayout target = container;
                if (target == null) {
                    return;
                }
                target.animate().cancel();
                target.animate()
                        .alpha(0f)
                        .setDuration(280)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (!dismissed || target != container) {
                                    return;
                                }
                                teardownSplash(target);
                            }
                        })
                        .start();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            mainHandler.post(task);
        }
    }

    /** Immediately destroys any active splash views (safe to call repeatedly). */
    public void destroy() {
        dismissed = true;
        mainHandler.removeCallbacksAndMessages(null);
        FrameLayout target = container;
        if (target != null) {
            target.animate().cancel();
            teardownSplash(target);
        }
    }

    private void teardownSplash(FrameLayout target) {
        if (this.container == target) {
            this.container = null;
            this.mode = -1;
        }
        if (nativeDotsView != null) {
            nativeDotsView.releaseResources();
            nativeDotsView = null;
        }
        destroyOverlayWebView();
        if (target != null) {
            try {
                target.setVisibility(View.GONE);
                target.removeAllViews();
                ViewParent parent = target.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(target);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Re-render the splash after a theme/configuration change. */
    public void refresh() {
        if (container == null || dismissed) {
            return;
        }
        container.setBackgroundColor(theme.getBackgroundColor());
        if (nativeDotsView != null && mode == MODE_LOADING) {
            nativeDotsView.setDark(theme.isDark());
        }
    }

    /** API31+: cross-fade the system splash into this overlay. */
    public void installExitAnimation(Activity activity) {
        if (container != null) {
            SplashExitInstaller.install(activity, container);
        }
    }

    private String foreground() {
        return theme.getForegroundHex();
    }

    private String background() {
        return theme.getBackgroundHex();
    }
}
