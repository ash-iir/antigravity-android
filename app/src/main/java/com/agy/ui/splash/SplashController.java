package com.agy.ui.splash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.agy.theme.ThemeManager;
import com.agy.util.ViewParams;

/**
 * Owns the 3-dot splash WebView: shows the loading animation, transient
 * restart status, and fail-closed errors; dismisses and destroys it when UI paints.
 */
public final class SplashController {

    public static final int MODE_LOADING = 0;
    public static final int MODE_TRANSIENT = 1;
    public static final int MODE_ERROR = 2;

    private final Context context;
    private final ThemeManager theme;
    private final FrameLayout root;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebView view;
    private SplashRetryListener retryListener;
    private boolean dismissed = false;
    private boolean fatalShown = false;
    private int mode = MODE_LOADING;

    public SplashController(Context context, ThemeManager theme, FrameLayout root) {
        this.context = context;
        this.theme = theme;
        this.root = root;
        this.view = createSplashView();
    }

    private WebView createSplashView() {
        WebView wv = new WebView(context);
        wv.setBackgroundColor(Color.TRANSPARENT);
        android.webkit.WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        if (retryListener != null) {
            SplashRetryBridge.bind(wv, context, retryListener);
        }
        return wv;
    }

    private WebView ensureView() {
        if (view != null) {
            if (view.getParent() == null && root != null) {
                root.addView(view, ViewParams.full());
            }
            return view;
        }
        view = createSplashView();
        mode = -1;
        if (root != null) {
            root.addView(view, ViewParams.full());
        }
        return view;
    }

    public WebView view() {
        return view;
    }

    public int mode() {
        return mode;
    }

    public boolean isFatalShown() {
        return fatalShown;
    }

    public void setRetryListener(SplashRetryListener listener) {
        this.retryListener = listener;
        if (view != null) {
            SplashRetryBridge.bind(view, context, listener);
        }
    }

    /** First render of the loading splash (before any show* call). */
    public void loadInitial() {
        WebView wv = ensureView();
        mode = MODE_LOADING;
        SplashLoadingHtml.load(wv, foreground(), background());
    }

    /** Show the animated loading splash again (startup or post-restart ready). */
    public void showLoading() {
        WebView wv = setVisible();
        if (mode != MODE_LOADING) {
            mode = MODE_LOADING;
            SplashLoadingHtml.load(wv, foreground(), background());
        }
    }

    /** Non-fatal status (auto-restart running): overlay without a Retry button. */
    public void showTransient(String message) {
        if (fatalShown) {
            return;
        }
        WebView wv = setVisible();
        if (mode != MODE_TRANSIENT) {
            mode = MODE_TRANSIENT;
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
        WebView wv = setVisible();
        SplashRetryBridge.bind(wv, context, retryListener);
        SplashOverlayHtml.show(wv, "Antigravity failed to start", message,
                foreground(), background(), retryListener != null);
    }

    /** Called when a fresh ready generation begins; errors can be shown again. */
    public void clearFatal() {
        fatalShown = false;
    }

    public WebView setVisible() {
        dismissed = false;
        WebView wv = ensureView();
        wv.animate().cancel();
        wv.setAlpha(1f);
        if (wv.getVisibility() != View.VISIBLE) {
            wv.setVisibility(View.VISIBLE);
        }
        if (root != null) {
            root.bringChildToFront(wv);
        }
        return wv;
    }

    /** Idempotent; fades out then tears down the splash WebView on the main thread. */
    public void dismiss() {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (dismissed) {
                    return;
                }
                dismissed = true;
                final WebView target = view;
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
                                if (!dismissed || target != view) {
                                    return;
                                }
                                destroyWebView(target);
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

    /** Immediately destroys any active splash WebView (safe to call repeatedly). */
    public void destroy() {
        dismissed = true;
        WebView target = view;
        if (target != null) {
            target.animate().cancel();
            destroyWebView(target);
        }
    }

    private void destroyWebView(WebView wv) {
        if (wv == null) {
            return;
        }
        if (this.view == wv) {
            this.view = null;
            this.mode = -1;
        }
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

    /** Re-render the splash after a theme/configuration change. */
    public void refresh() {
        if (view == null) {
            return;
        }
        view.setBackgroundColor(Color.TRANSPARENT);
        if (mode == MODE_LOADING && !dismissed) {
            SplashLoadingHtml.load(view, foreground(), background());
        }
    }

    /** API31+: cross-fade the system splash into this overlay. */
    public void installExitAnimation(Activity activity) {
        if (view != null) {
            SplashExitInstaller.install(activity, view);
        }
    }

    private String foreground() {
        return theme.getForegroundHex();
    }

    private String background() {
        return theme.getBackgroundHex();
    }
}
