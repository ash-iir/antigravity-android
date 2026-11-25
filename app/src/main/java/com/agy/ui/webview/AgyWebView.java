package com.agy.ui.webview;

import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.Locale;

/**
 * WebView subclass that advertises rich image MIME types on its InputConnection
 * so soft keyboards (Gboard, Samsung Keyboard, etc.) can paste images/stickers
 * directly into chat composers via {@code commitContent}.
 */
public class AgyWebView extends WebView {

    private static final String TAG = "AgyWebView";
    private static final String[] ACCEPTED_IMAGE_MIMES = new String[]{
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/*"
    };

    public AgyWebView(Context context) {
        super(context);
    }

    public AgyWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Dispatches an encoded image payload into the chat WebView on its UI thread. */
    public static void dispatchImageToWebView(final WebView webView,
                                              final ClipboardImageReader.ImageData img) {
        if (webView == null || img == null || img.base64.isEmpty()) {
            return;
        }
        webView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String js = "window.__agPasteImage && window.__agPasteImage("
                            + JSONObject.quote(img.base64) + ","
                            + JSONObject.quote(img.mimeType) + ","
                            + JSONObject.quote(img.filename) + ");";
                    webView.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        final InputConnection base = super.onCreateInputConnection(outAttrs);
        if (base == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1 || outAttrs == null) {
            return base;
        }
        outAttrs.contentMimeTypes = ACCEPTED_IMAGE_MIMES;
        return new InputConnectionWrapper(base, false) {
            @Override
            public boolean commitContent(final InputContentInfo inputContentInfo,
                                         final int flags, Bundle opts) {
                if (inputContentInfo == null) {
                    return false;
                }
                ClipDescription desc = inputContentInfo.getDescription();
                if (desc == null || !desc.hasMimeType("image/*")) {
                    return super.commitContent(inputContentInfo, flags, opts);
                }

                final Uri contentUri = inputContentInfo.getContentUri();
                if (contentUri == null) {
                    return false;
                }

                String preferredMime = null;
                for (int i = 0; i < desc.getMimeTypeCount(); i++) {
                    String m = desc.getMimeType(i);
                    if (m != null && m.toLowerCase(Locale.US).startsWith("image/")
                            && !"image/*".equalsIgnoreCase(m)) {
                        preferredMime = m;
                        break;
                    }
                }
                final String mimeHint = preferredMime;

                final boolean needsPermission =
                        (flags & InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0;
                if (needsPermission) {
                    try {
                        inputContentInfo.requestPermission();
                    } catch (Throwable t) {
                        Log.w(TAG, "commitContent requestPermission failed: " + t.getMessage());
                        return false;
                    }
                }

                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ClipboardImageReader.ImageData img =
                                    ClipboardImageReader.readFromUri(getContext(), contentUri, mimeHint);
                            dispatchImageToWebView(AgyWebView.this, img);
                        } finally {
                            if (needsPermission) {
                                try {
                                    inputContentInfo.releasePermission();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }, "AgyImeImagePaste");
                t.setDaemon(true);
                t.start();
                return true;
            }
        };
    }
}
