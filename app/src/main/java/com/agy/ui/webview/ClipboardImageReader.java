package com.agy.ui.webview;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Reads pasted image URIs from Android's clipboard or soft-keyboard InputContentInfo,
 * downscales/compresses oversized images to keep WebView IPC lean, and encodes Base64.
 */
public final class ClipboardImageReader {

    private static final String TAG = "ClipboardImageReader";
    private static final int MAX_RAW_READ_BYTES = 16 * 1024 * 1024;
    private static final int TARGET_MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    private static final int HARD_MAX_PAYLOAD_BYTES = 6 * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 2048;

    public static final class ImageData {
        public final String base64;
        public final String mimeType;
        public final String filename;

        public ImageData(String base64, String mimeType, String filename) {
            this.base64 = base64 != null ? base64 : "";
            this.mimeType = mimeType != null ? mimeType : "image/png";
            this.filename = filename != null ? filename : "pasted_image.png";
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("base64", base64);
                o.put("mimeType", mimeType);
                o.put("filename", filename);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private ClipboardImageReader() {
    }

    /**
     * Checks clipboard MIME metadata without reading clip content, so Android 12+
     * does not trigger a "pasted from clipboard" notification unless an image exists.
     */
    public static boolean hasClipboardImage(Context context) {
        if (context == null) {
            return false;
        }
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return false;
            }
            ClipDescription desc = cm.getPrimaryClipDescription();
            return findImageMime(desc) != null || (desc != null && desc.hasMimeType("image/*"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static ImageData readFromClipboard(Context context) {
        if (context == null) {
            return null;
        }
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return null;
            }
            // Inspect ClipDescription before calling getPrimaryClip() to avoid
            // triggering Android 12+ clipboard access toasts on non-image clips.
            ClipDescription desc = cm.getPrimaryClipDescription();
            String preferredMime = findImageMime(desc);
            if (preferredMime == null && (desc == null || !desc.hasMimeType("image/*"))) {
                return null;
            }

            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            ClipData.Item item = clip.getItemAt(0);
            Uri uri = item != null ? item.getUri() : null;
            if (uri == null) {
                return null;
            }
            if (preferredMime == null) {
                preferredMime = findImageMime(clip.getDescription());
            }
            String resolved = resolveMimeType(context, uri, preferredMime);
            if (!resolved.toLowerCase(Locale.US).startsWith("image/")) {
                return null;
            }
            return readFromUri(context, uri, resolved);
        } catch (Throwable t) {
            Log.w(TAG, "readFromClipboard failed: " + t.getMessage());
            return null;
        }
    }

    public static ImageData readFromUri(Context context, Uri uri, String preferredMime) {
        if (context == null || uri == null) {
            return null;
        }
        ContentResolver cr = context.getContentResolver();
        if (cr == null) {
            return null;
        }
        String mime = resolveMimeType(context, uri, preferredMime);
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_RAW_READ_BYTES) {
                    Log.w(TAG, "pasted image exceeds 16 MiB read limit; ignoring");
                    return null;
                }
                out.write(buf, 0, n);
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) {
                return null;
            }

            if (bytes.length > TARGET_MAX_PAYLOAD_BYTES) {
                if ("image/gif".equalsIgnoreCase(mime)) {
                    if (bytes.length > HARD_MAX_PAYLOAD_BYTES) {
                        Log.w(TAG, "GIF exceeds 6 MiB payload limit; ignoring");
                        return null;
                    }
                } else {
                    ScaledResult scaled = downscaleAndCompress(bytes, mime);
                    if (scaled == null || scaled.bytes.length > HARD_MAX_PAYLOAD_BYTES) {
                        Log.w(TAG, "oversized image could not be compressed under 6 MiB; ignoring");
                        return null;
                    }
                    bytes = scaled.bytes;
                    mime = scaled.mimeType;
                }
            }

            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            String filename = "pasted_image_" + System.currentTimeMillis() + extensionForMime(mime);
            return new ImageData(base64, mime, filename);
        } catch (Throwable t) {
            Log.w(TAG, "readFromUri failed: " + t.getMessage());
            return null;
        }
    }

    private static final class ScaledResult {
        final byte[] bytes;
        final String mimeType;

        ScaledResult(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }
    }

    private static ScaledResult downscaleAndCompress(byte[] raw, String origMime) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
        while ((maxDim / sample) > MAX_IMAGE_DIMENSION) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        if (bmp == null) {
            return null;
        }

        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int longest = Math.max(w, h);
            if (longest > MAX_IMAGE_DIMENSION) {
                float scale = (float) MAX_IMAGE_DIMENSION / (float) longest;
                int nw = Math.max(1, Math.round(w * scale));
                int nh = Math.max(1, Math.round(h * scale));
                Bitmap resized = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                if (resized != bmp) {
                    bmp.recycle();
                    bmp = resized;
                }
            }

            boolean keepAlpha = bmp.hasAlpha()
                    && ("image/png".equalsIgnoreCase(origMime) || "image/webp".equalsIgnoreCase(origMime));
            if (keepAlpha) {
                ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, pngOut);
                byte[] pngBytes = pngOut.toByteArray();
                if (pngBytes.length <= TARGET_MAX_PAYLOAD_BYTES) {
                    return new ScaledResult(pngBytes, "image/png");
                }
            }

            int[] qualities = new int[]{88, 76, 64, 52};
            for (int q : qualities) {
                ByteArrayOutputStream jpgOut = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, q, jpgOut);
                byte[] jpgBytes = jpgOut.toByteArray();
                if (jpgBytes.length <= TARGET_MAX_PAYLOAD_BYTES || q == qualities[qualities.length - 1]) {
                    return new ScaledResult(jpgBytes, "image/jpeg");
                }
            }
            return null;
        } finally {
            bmp.recycle();
        }
    }

    private static String findImageMime(ClipDescription desc) {
        if (desc == null) {
            return null;
        }
        for (int i = 0; i < desc.getMimeTypeCount(); i++) {
            String m = desc.getMimeType(i);
            if (m != null) {
                String low = m.trim().toLowerCase(Locale.US);
                if (low.startsWith("image/") && !"image/*".equals(low)) {
                    return low;
                }
            }
        }
        return null;
    }

    private static String resolveMimeType(Context context, Uri uri, String preferredMime) {
        if (preferredMime != null) {
            String low = preferredMime.trim().toLowerCase(Locale.US);
            if (low.startsWith("image/") && !"image/*".equals(low)) {
                return low;
            }
        }
        try {
            ContentResolver cr = context.getContentResolver();
            if (cr != null) {
                String type = cr.getType(uri);
                if (type != null) {
                    String low = type.trim().toLowerCase(Locale.US);
                    if (low.startsWith("image/") && !"image/*".equals(low)) {
                        return low;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "image/png";
    }

    private static String extensionForMime(String mime) {
        if (mime == null) {
            return ".png";
        }
        String low = mime.toLowerCase(Locale.US);
        if (low.contains("jpeg") || low.contains("jpg")) {
            return ".jpg";
        }
        if (low.contains("webp")) {
            return ".webp";
        }
        if (low.contains("gif")) {
            return ".gif";
        }
        return ".png";
    }
}
