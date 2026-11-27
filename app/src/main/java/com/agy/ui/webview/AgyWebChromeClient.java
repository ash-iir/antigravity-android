package com.agy.ui.webview;

import android.net.Uri;
import android.os.Message;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.agy.util.SensitiveDataRedactor;

/** Routes console output to logcat, popups to PopupWindowHandler, and file chooser to callback. */
public final class AgyWebChromeClient extends WebChromeClient {

    private static final String TAG = "WebViewHost";

    public interface FileChooserCallback {
        boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                  FileChooserParams fileChooserParams);
    }

    private final PopupWindowHandler popupHandler;
    private final FileChooserCallback fileChooserCallback;

    public AgyWebChromeClient(PopupWindowHandler popupHandler, FileChooserCallback fileChooserCallback) {
        this.popupHandler = popupHandler;
        this.fileChooserCallback = fileChooserCallback;
    }

    public AgyWebChromeClient(PopupWindowHandler popupHandler) {
        this(popupHandler, null);
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage cm) {
        if (cm != null) {
            ConsoleMessage.MessageLevel lvl = cm.messageLevel();
            if (!com.agy.BuildConfig.DEBUG
                    && lvl != ConsoleMessage.MessageLevel.ERROR
                    && lvl != ConsoleMessage.MessageLevel.WARNING) {
                return true;
            }
            String msg = SensitiveDataRedactor.redactLine(cm.message());
            String src = SensitiveDataRedactor.safeUrl(cm.sourceId());
            int line = cm.lineNumber();
            String tag = "AGY_JS";
            if (lvl == ConsoleMessage.MessageLevel.ERROR) {
                Log.e(tag, "[" + src + ":" + line + "] " + msg);
            } else if (lvl == ConsoleMessage.MessageLevel.WARNING) {
                Log.w(tag, "[" + src + ":" + line + "] " + msg);
            } else {
                Log.i(tag, "[" + src + ":" + line + "] " + msg);
            }
        }
        return true;
    }

    @Override
    public boolean onCreateWindow(WebView parent, boolean isDialog, boolean isUserGesture,
                                  Message resultMsg) {
        return popupHandler.handle(parent, isDialog, isUserGesture, resultMsg);
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                      FileChooserParams fileChooserParams) {
        if (fileChooserCallback != null) {
            return fileChooserCallback.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }
        return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
    }

    @Override
    public void onPermissionRequest(final android.webkit.PermissionRequest request) {
        if (request == null) return;
        android.app.Activity activity = com.agy.ui.MainActivity.activeActivity;
        if (activity == null) {
            try {
                request.deny();
            } catch (Throwable ignored) {}
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                java.util.List<String> neededPermissions = new java.util.ArrayList<>();
                for (String res : request.getResources()) {
                    if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                neededPermissions.add(android.Manifest.permission.RECORD_AUDIO);
                            }
                        }
                    } else if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            if (activity.checkSelfPermission(android.Manifest.permission.CAMERA)
                                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                neededPermissions.add(android.Manifest.permission.CAMERA);
                            }
                        }
                    }
                }
                if (neededPermissions.isEmpty()) {
                    request.grant(request.getResources());
                } else {
                    if (activity instanceof com.agy.ui.MainActivity) {
                        ((com.agy.ui.MainActivity) activity).requestMediaPermissions(request, neededPermissions);
                    } else {
                        request.deny();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "onPermissionRequest error: " + t.getMessage());
            }
        });
    }

    @Override
    public void onPermissionRequestCanceled(android.webkit.PermissionRequest request) {
        if (request != null) {
            android.app.Activity activity = com.agy.ui.MainActivity.activeActivity;
            if (activity instanceof com.agy.ui.MainActivity) {
                ((com.agy.ui.MainActivity) activity).onMediaPermissionCanceled(request);
            }
        }
    }
}
