package com.agy.core;

import android.content.Context;
import android.net.http.SslCertificate;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.agy.util.ProcessUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/** One-shot loopback readiness probe and bundled core certificate pinning helper. */
public final class CoreReadyProbe {

    private static final String TAG = "CoreReadyProbe";
    public static final long READY_TIMEOUT_MS = 60000;
    /** Re-probe gap while the core boots. */
    private static final long RETRY_SLEEP_MS = 60;
    private static volatile byte[] expectedCertDigest;

    private CoreReadyProbe() {
    }

    public static boolean probeOnce(Context context, int port) {
        // Fast loopback TCP socket connection check: verifies that language_server
        // has successfully bound port and is actively listening for connections.
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 350);
            return true;
        } catch (Exception notReady) {
            ProcessUtils.sleep(RETRY_SLEEP_MS);
            return false;
        }
    }

    /** Returns true iff the peer SslCertificate SHA-256 matches the bundled core certificate. */
    public static boolean matchesCert(Context context, SslCertificate cert) {
        if (context == null || cert == null) {
            return false;
        }
        try {
            byte[] expected = expectedCertDigest(context);
            if (expected == null || expected.length == 0) {
                return false;
            }
            X509Certificate x509 = extractX509(cert);
            if (x509 == null) {
                return false;
            }
            byte[] actual = sha256(x509.getEncoded());
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            Log.w(TAG, "certificate pin check failed: " + e.getMessage());
            return false;
        }
    }

    private static X509Certificate extractX509(SslCertificate cert) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            X509Certificate x509 = cert.getX509Certificate();
            if (x509 != null) {
                return x509;
            }
        }
        Bundle state = SslCertificate.saveState(cert);
        byte[] der = state != null ? state.getByteArray("x509-certificate") : null;
        if (der == null || der.length == 0) {
            return null;
        }
        Certificate parsed = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return (parsed instanceof X509Certificate) ? (X509Certificate) parsed : null;
    }

    public static byte[] expectedCertDigest(Context context) throws Exception {
        byte[] cached = expectedCertDigest;
        if (cached != null) {
            return cached;
        }
        synchronized (CoreReadyProbe.class) {
            cached = expectedCertDigest;
            if (cached != null) {
                return cached;
            }
            int resourceId = context.getResources().getIdentifier(
                    "agy_core_cert", "raw", context.getPackageName());
            if (resourceId == 0) {
                Log.e(TAG, "bundled core certificate resource is missing");
                return null;
            }
            try (InputStream input = context.getResources().openRawResource(resourceId)) {
                Certificate certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(input);
                if (!(certificate instanceof X509Certificate)) {
                    return null;
                }
                cached = sha256(certificate.getEncoded());
                expectedCertDigest = cached;
                return cached;
            }
        }
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }
}
