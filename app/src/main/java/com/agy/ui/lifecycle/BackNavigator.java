package com.agy.ui.lifecycle;

import android.app.Activity;
import android.os.Build;
import android.webkit.ValueCallback;
import android.webkit.WebView;

/**
 * Back handling: let the web UI step back inside open overlays (e.g. Settings
 * sub-views) before walking WebView history or finishing the activity.
 */
public final class BackNavigator {

    private static final String JS_HANDLE_BACK =
            "(typeof window.__agHandleBack==='function'&&window.__agHandleBack())?'1':'0'";

    private BackNavigator() {
    }

    private static boolean isActivityAlive(Activity activity) {
        if (activity == null) {
            return true;
        }
        if (activity.isFinishing()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            return false;
        }
        return true;
    }

    /**
     * Dispatches Back to {@code window.__agHandleBack()}; if not consumed,
     * walks WebView history or invokes {@code fallback}.
     */
    public static void handle(final Activity activity, final WebView webView, final Runnable fallback) {
        if (!isActivityAlive(activity)) {
            return;
        }
        if (webView == null) {
            if (fallback != null) {
                fallback.run();
            }
            return;
        }
        try {
            webView.evaluateJavascript(JS_HANDLE_BACK, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if ("\"1\"".equals(value)) {
                        return;
                    }
                    if (!isActivityAlive(activity)) {
                        return;
                    }
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else if (fallback != null) {
                        fallback.run();
                    }
                }
            });
        } catch (Throwable ignored) {
            if (!isActivityAlive(activity)) {
                return;
            }
            if (webView.canGoBack()) {
                webView.goBack();
            } else if (fallback != null) {
                fallback.run();
            }
        }
    }
}
