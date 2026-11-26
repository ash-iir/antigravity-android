package com.agy.oauth;

import android.content.Context;
import android.system.Os;
import android.util.Base64;
import android.util.Log;

import com.agy.util.IoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Persistent, owner-only (0600) vault of every Antigravity account the user has
 * logged into. Snapshots the full standalone OAuth token payload together with
 * display metadata so switching accounts is instantaneous and never exposes raw
 * tokens to WebView JavaScript.
 */
public final class AccountVaultStore {

    private static final String TAG = "AccountVaultStore";
    private static final String VAULT_FILE = "agy-account-vault.json";
    private static final int VAULT_VERSION = 1;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AccountVaultStore() {
    }

    public static File vaultFile(Context context) {
        if (context == null) {
            return null;
        }
        return new File(new File(context.getFilesDir(), OAuthConstants.TOKEN_DIR), VAULT_FILE);
    }

    /** Captures or refreshes the currently active token + optional UI profile metadata. */
    public static String syncCurrentAccount(Context context, String profileJson) {
        synchronized (LOCK) {
            syncCurrentAccountLocked(context, profileJson);
            return listAccountsLocked(context).toString();
        }
    }

    /** Returns sanitized account summaries (never containing access or refresh tokens). */
    public static String listAccountsJson(Context context) {
        synchronized (LOCK) {
            syncCurrentAccountLocked(context, null);
            return listAccountsLocked(context).toString();
        }
    }

    /**
     * Saves the outgoing account's freshest token/profile, writes the selected
     * account's token payload atomically to {@code jetski-standalone-oauth-token},
     * and returns {@code {"ok":true,"account":{...},"accounts":[...]}}.
     */
    public static String activateAccount(Context context, String accountId, String outgoingProfileJson) {
        JSONObject result = new JSONObject();
        synchronized (LOCK) {
            try {
                if (context == null || accountId == null || accountId.trim().isEmpty()) {
                    result.put("ok", false);
                    result.put("error", "Invalid account selection.");
                    return result.toString();
                }

                syncCurrentAccountLocked(context, outgoingProfileJson);
                JSONObject vault = readVaultLocked(context);
                JSONArray accounts = vault.optJSONArray("accounts");
                if (accounts == null) {
                    result.put("ok", false);
                    result.put("error", "No saved accounts found.");
                    return result.toString();
                }

                JSONObject target = null;
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject item = accounts.optJSONObject(i);
                    if (item != null && accountId.equals(item.optString("id", ""))) {
                        target = item;
                        break;
                    }
                }
                if (target == null) {
                    result.put("ok", false);
                    result.put("error", "Saved account not found.");
                    return result.toString();
                }

                JSONObject tokenPayload = target.optJSONObject("tokenPayload");
                if (!hasValidTokenObject(tokenPayload)) {
                    result.put("ok", false);
                    result.put("error", "Saved credentials for this account are incomplete.");
                    return result.toString();
                }

                JSONObject clonedToken = new JSONObject(tokenPayload.toString());
                clonedToken.put("writer_id", generateWriterId());

                File activeFile = OAuthTokenStore.tokenFile(context);
                if (activeFile == null || !writeSecureText(activeFile, clonedToken.toString(2) + "\n")) {
                    result.put("ok", false);
                    result.put("error", "Failed to write credentials to disk.");
                    return result.toString();
                }

                target.put("lastUsedAt", System.currentTimeMillis());
                writeVaultLocked(context, vault);

                result.put("ok", true);
                result.put("account", sanitizeAccount(target, accountId));
                result.put("accounts", listAccountsLocked(context));
                return result.toString();
            } catch (Exception e) {
                Log.w(TAG, "activateAccount failed: " + e.getMessage());
                try {
                    result.put("ok", false);
                    result.put("error", "Failed to switch account.");
                } catch (Exception ignored) {
                }
                return result.toString();
            }
        }
    }

    /** Removes a saved account from the vault and returns the updated sanitized list. */
    public static String removeAccount(Context context, String accountId) {
        synchronized (LOCK) {
            if (context != null && accountId != null && !accountId.trim().isEmpty()) {
                try {
                    JSONObject vault = readVaultLocked(context);
                    JSONArray existing = vault.optJSONArray("accounts");
                    if (existing != null) {
                        JSONArray filtered = new JSONArray();
                        for (int i = 0; i < existing.length(); i++) {
                            JSONObject item = existing.optJSONObject(i);
                            if (item != null && !accountId.equals(item.optString("id", ""))) {
                                filtered.put(item);
                            }
                        }
                        vault.put("accounts", filtered);
                        writeVaultLocked(context, vault);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "removeAccount failed: " + e.getMessage());
                }
            }
            return listAccountsLocked(context).toString();
        }
    }

    private static void syncCurrentAccountLocked(Context context, String profileJson) {
        if (context == null) {
            return;
        }
        try {
            JSONObject activeToken = readActiveTokenLocked(context);
            if (!hasValidTokenObject(activeToken)) {
                return;
            }
            JSONObject profile = parseJsonObjectOrEmpty(profileJson);

            String email = firstNonEmpty(
                    profile.optString("email", ""),
                    activeToken.optString("id_token_email", "")
            ).trim();
            String authMethod = firstNonEmpty(
                    activeToken.optString("auth_method", ""),
                    profile.optString("authMethod", ""),
                    "consumer"
            ).trim();
            String projectId = firstNonEmpty(
                    profile.optString("projectId", ""),
                    activeToken.optString("project_id", "")
            ).trim();
            String location = firstNonEmpty(
                    profile.optString("location", ""),
                    activeToken.optString("location", "")
            ).trim();
            boolean isGcpTos = profile.optBoolean("isGcpTos", false) || "enterprise".equalsIgnoreCase(authMethod);

            String accountId = buildAccountId(email, authMethod, projectId, activeToken);
            if (accountId.isEmpty()) {
                return;
            }

            // Ensure tokenPayload always preserves id_token_email if known
            JSONObject tokenSnapshot = new JSONObject(activeToken.toString());
            if (!email.isEmpty() && tokenSnapshot.optString("id_token_email", "").isEmpty()) {
                tokenSnapshot.put("id_token_email", email);
            }

            JSONObject vault = readVaultLocked(context);
            JSONArray accounts = vault.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                vault.put("accounts", accounts);
            }

            String activeRefresh = extractRefreshToken(activeToken);
            String baseEmailKey = email.isEmpty() ? "" : (email.toLowerCase(Locale.US) + "|" + authMethod);

            int matchIdx = -1;
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject item = accounts.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String itemId = item.optString("id", "");
                if (accountId.equals(itemId)) {
                    matchIdx = i;
                    break;
                }
                // Merge seamless transition when projectId wasn't known at initial native capture
                if (!baseEmailKey.isEmpty()
                        && (itemId.equals(baseEmailKey) || (projectId.isEmpty() && itemId.startsWith(baseEmailKey + "|")))) {
                    matchIdx = i;
                    break;
                }
                if (!activeRefresh.isEmpty() && activeRefresh.equals(extractRefreshToken(item.optJSONObject("tokenPayload")))) {
                    matchIdx = i;
                    break;
                }
            }

            long now = System.currentTimeMillis();
            JSONObject entry = matchIdx >= 0 ? accounts.optJSONObject(matchIdx) : new JSONObject();
            if (entry == null) {
                entry = new JSONObject();
            }

            long addedAt = entry.optLong("addedAt", 0L);
            if (addedAt <= 0L) {
                addedAt = now;
            }

            // Preserve project-qualified ID if the existing entry already had one and profileJson is empty
            String resolvedId = accountId;
            String resolvedProjectId = projectId;
            if (projectId.isEmpty() && !entry.optString("projectId", "").isEmpty()) {
                resolvedProjectId = entry.optString("projectId", "");
                resolvedId = entry.optString("id", accountId);
            }

            entry.put("id", resolvedId);
            entry.put("email", !email.isEmpty() ? email : entry.optString("email", ""));
            entry.put("name", firstNonEmpty(profile.optString("name", ""), entry.optString("name", "")));
            entry.put("avatarUrl", firstNonEmpty(profile.optString("avatarUrl", ""), entry.optString("avatarUrl", "")));
            entry.put("tier", firstNonEmpty(profile.optString("tier", ""), entry.optString("tier", "")));
            entry.put("authMethod", authMethod);
            entry.put("projectId", resolvedProjectId);
            entry.put("location", firstNonEmpty(location, entry.optString("location", "")));
            entry.put("isGcpTos", isGcpTos || entry.optBoolean("isGcpTos", false));
            entry.put("addedAt", addedAt);
            entry.put("lastUsedAt", now);
            entry.put("tokenPayload", tokenSnapshot);

            if (matchIdx < 0) {
                accounts.put(entry);
            }
            writeVaultLocked(context, vault);
        } catch (Exception e) {
            Log.w(TAG, "syncCurrentAccountLocked failed: " + e.getMessage());
        }
    }

    private static JSONArray listAccountsLocked(Context context) {
        JSONArray out = new JSONArray();
        if (context == null) {
            return out;
        }
        try {
            JSONObject vault = readVaultLocked(context);
            JSONArray accounts = vault.optJSONArray("accounts");
            if (accounts == null || accounts.length() == 0) {
                return out;
            }

            JSONObject activeToken = readActiveTokenLocked(context);
            String activeId = resolveActiveAccountId(accounts, activeToken);

            List<JSONObject> sorted = new ArrayList<>();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject item = accounts.optJSONObject(i);
                if (item != null && hasValidTokenObject(item.optJSONObject("tokenPayload"))) {
                    sorted.add(sanitizeAccount(item, activeId));
                }
            }

            Collections.sort(sorted, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    boolean aActive = a.optBoolean("isActive", false);
                    boolean bActive = b.optBoolean("isActive", false);
                    if (aActive != bActive) {
                        return aActive ? -1 : 1;
                    }
                    long aLast = a.optLong("lastUsedAt", 0L);
                    long bLast = b.optLong("lastUsedAt", 0L);
                    if (aLast != bLast) {
                        return Long.compare(bLast, aLast);
                    }
                    return a.optString("email", "").compareToIgnoreCase(b.optString("email", ""));
                }
            });

            for (JSONObject item : sorted) {
                out.put(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "listAccountsLocked failed: " + e.getMessage());
        }
        return out;
    }

    private static String resolveActiveAccountId(JSONArray accounts, JSONObject activeToken) {
        if (!hasValidTokenObject(activeToken)) {
            return "";
        }
        String activeRefresh = extractRefreshToken(activeToken);
        String activeAccess = extractAccessToken(activeToken);
        String activeEmail = activeToken.optString("id_token_email", "").trim().toLowerCase(Locale.US);
        String activeMethod = activeToken.optString("auth_method", "consumer").trim();

        if (!activeRefresh.isEmpty()) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject item = accounts.optJSONObject(i);
                if (item == null) continue;
                if (activeRefresh.equals(extractRefreshToken(item.optJSONObject("tokenPayload")))) {
                    return item.optString("id", "");
                }
            }
        }
        if (!activeAccess.isEmpty()) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject item = accounts.optJSONObject(i);
                if (item == null) continue;
                if (activeAccess.equals(extractAccessToken(item.optJSONObject("tokenPayload")))) {
                    return item.optString("id", "");
                }
            }
        }
        if (!activeEmail.isEmpty()) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject item = accounts.optJSONObject(i);
                if (item == null) continue;
                if (activeEmail.equals(item.optString("email", "").trim().toLowerCase(Locale.US))
                        && activeMethod.equals(item.optString("authMethod", "consumer").trim())) {
                    return item.optString("id", "");
                }
            }
        }
        return "";
    }

    private static JSONObject sanitizeAccount(JSONObject entry, String activeId) {
        JSONObject safe = new JSONObject();
        try {
            String id = entry.optString("id", "");
            JSONObject payload = entry.optJSONObject("tokenPayload");
            safe.put("id", id);
            safe.put("email", entry.optString("email", ""));
            safe.put("name", entry.optString("name", ""));
            safe.put("avatarUrl", entry.optString("avatarUrl", ""));
            safe.put("tier", entry.optString("tier", ""));
            safe.put("authMethod", entry.optString("authMethod", "consumer"));
            safe.put("projectId", entry.optString("projectId", ""));
            safe.put("location", entry.optString("location", ""));
            safe.put("isGcpTos", entry.optBoolean("isGcpTos", false));
            safe.put("addedAt", entry.optLong("addedAt", 0L));
            safe.put("lastUsedAt", entry.optLong("lastUsedAt", 0L));
            safe.put("isActive", !activeId.isEmpty() && activeId.equals(id));
            safe.put("hasRefreshToken", !extractRefreshToken(payload).isEmpty());
        } catch (Exception ignored) {
        }
        return safe;
    }

    private static JSONObject readActiveTokenLocked(Context context) {
        File file = OAuthTokenStore.tokenFile(context);
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > MAX_FILE_BYTES) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            String raw = IoUtil.readAll(in, "UTF-8");
            if (raw.trim().isEmpty()) {
                return null;
            }
            return new JSONObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject readVaultLocked(Context context) {
        File file = vaultFile(context);
        if (file != null && file.isFile() && file.length() > 0 && file.length() <= MAX_FILE_BYTES) {
            try (FileInputStream in = new FileInputStream(file)) {
                String raw = IoUtil.readAll(in, "UTF-8");
                if (!raw.trim().isEmpty()) {
                    JSONObject parsed = new JSONObject(raw);
                    if (parsed.optJSONArray("accounts") != null) {
                        return parsed;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "failed reading account vault; starting fresh: " + e.getMessage());
            }
        }
        JSONObject empty = new JSONObject();
        try {
            empty.put("version", VAULT_VERSION);
            empty.put("accounts", new JSONArray());
        } catch (Exception ignored) {
        }
        return empty;
    }

    private static void writeVaultLocked(Context context, JSONObject vault) {
        File file = vaultFile(context);
        if (file == null) {
            return;
        }
        try {
            vault.put("version", VAULT_VERSION);
            writeSecureText(file, vault.toString(2) + "\n");
        } catch (Exception e) {
            Log.w(TAG, "failed writing account vault: " + e.getMessage());
        }
    }

    private static boolean writeSecureText(File target, String content) {
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        try {
            IoUtil.deleteQuietly(tmp);
            if (!tmp.createNewFile()) {
                IoUtil.deleteQuietly(tmp);
            }
            enforceOwnerOnlyPermissions(tmp);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            enforceOwnerOnlyPermissions(tmp);
            if (!tmp.renameTo(target)) {
                if (!target.delete() || !tmp.renameTo(target)) {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    IoUtil.deleteQuietly(tmp);
                }
            }
            enforceOwnerOnlyPermissions(target);
            return true;
        } catch (Exception e) {
            IoUtil.deleteQuietly(tmp);
            Log.w(TAG, "writeSecureText failed for " + target.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private static void enforceOwnerOnlyPermissions(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            Os.chmod(file.getAbsolutePath(), 0600);
        } catch (Throwable ignored) {
        }
        file.setReadable(false, false);
        file.setReadable(true, true);
        file.setWritable(false, false);
        file.setWritable(true, true);
    }

    private static boolean hasValidTokenObject(JSONObject root) {
        if (root == null) {
            return false;
        }
        JSONObject token = root.optJSONObject("token");
        if (token == null) {
            return false;
        }
        return !token.optString("access_token", "").trim().isEmpty()
                || !token.optString("refresh_token", "").trim().isEmpty();
    }

    private static String extractRefreshToken(JSONObject root) {
        if (root == null) return "";
        JSONObject token = root.optJSONObject("token");
        return token != null ? token.optString("refresh_token", "").trim() : "";
    }

    private static String extractAccessToken(JSONObject root) {
        if (root == null) return "";
        JSONObject token = root.optJSONObject("token");
        return token != null ? token.optString("access_token", "").trim() : "";
    }

    private static String buildAccountId(String email, String authMethod, String projectId, JSONObject tokenRoot) {
        if (!email.isEmpty()) {
            String base = email.toLowerCase(Locale.US) + "|" + (authMethod.isEmpty() ? "consumer" : authMethod);
            return projectId.isEmpty() ? base : (base + "|" + projectId);
        }
        String seed = firstNonEmpty(extractRefreshToken(tokenRoot), extractAccessToken(tokenRoot));
        if (seed.isEmpty()) {
            return "";
        }
        return "tok|" + sha256Prefix(seed);
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < hash.length; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String generateWriterId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static JSONObject parseJsonObjectOrEmpty(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String firstNonEmpty(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return "";
    }
}
