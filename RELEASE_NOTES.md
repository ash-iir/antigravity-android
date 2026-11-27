# Antigravity for Android v2.34.21

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.21)

### 1. Chat Composer Cleanup
* **Remove `/swarm` Quick Chip:** Cleanly removes the `/swarm` toggle chip patch (`web/generated/patches/swarm_chip.js`) from the chat composer toolbar, keeping the composer minimal and unmodified.

---

# Antigravity for Android v2.34.20

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.20)

### 1. Background Agent Notifications & Direct Deep-Link
* **Reliable Android 13+ Background Alerts:** Requests Android 13+ `POST_NOTIFICATIONS` permission, wires the upstream `window.nativeNotifications` bridge (`send`, `onClicked` -> direct conversation jump, `openSystemPreferences`), syncs `document.hasFocus()` with Android foreground state, and upgrades `CHANNEL_ALERTS` to `IMPORTANCE_HIGH` with sound, vibration, and lockscreen visibility.

### 2. Navigation & Swarm Composer UX
* **1-Step Header Back Button:** Makes the top-left on-screen `←` header button step back 1 level on nested pages (long-press or top-level still jumps to Home `/`).
* **Quick `/swarm` Composer Toggle Chip:** Adds a compact theme-matched `/swarm` quick toggle chip in the chat composer toolbar.

### 3. Swarm Graph / Hive GPU Ghosting Fix
* **Zero WebView Tile Ghosting:** Eliminates Swarm Graph/Hive Android WebView GPU tile ghosting via cached PNG bee `<img>`, integer `left`/`top` positioning, 3 persistent diffed SVG `<g>` layers, and removal of SMIL/infinite compositor animations.

---

# Antigravity for Android v2.34.19

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.19)

### 1. Upstream Merge (`salmanbappi/antigravity-android`)
* **Persistent Account Vault & Account Switcher:** Merge upstream `salmanbappi/antigravity-android` updates (Persistent Account Vault & Settings Account Switcher + scoped `GOb` Account screen hook) while preserving all Swarm, background-resume, OAuth redirect, Back navigation, GitHub Device Flow, and Plugins mobile UI fixes.

---

# Antigravity for Android v2.34.18

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.18)

### 1. Swarm Graph / Hive View WebView Rendering Fix
* **Eliminate Duplicate/Ghosting Bees & Text:** Fix duplicate/offset bees and text ghosting in the Swarm **Graph / Hive** tab on Android WebView: memoize Hive/Mentions SVG & bee transforms (`dataset.tf`), isolate `.hv-stage`/`.hv-svg`/`.hv-bees` compositor layers, skip `.hv` panes in `snapBees()`, prune duplicate `.hv-bee` nodes, and coalesce `ResizeObserver` callbacks into a single rAF.

---

# Antigravity for Android v2.34.17

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.17)

### 1. Direct Browser GitHub Login (OAuth 2.0 Device Flow)
* **Local Browser Sign-In & Multi-Account Support:** Add direct local browser sign-in (GitHub OAuth 2.0 Device Flow, RFC 8628 — zero client secret, zero third-party proxy) inside **Customizations → Installed → Plugins → GitHub (Local)** with full multi-account support (**Sign in with GitHub (Browser)** / **Add account with GitHub (Browser)**), while preserving manual PAT paste.

### 2. Mobile Plugins UI Layout & Readability Fix
* **Fix Vertical Narrow Text Collapse:** Fix mobile Plugins UI layout where `section[aria-label="Accounts"]` text, signed-in account rows, MCP banners, and warning callouts collapsed into a narrow vertical column beside action buttons; reclaim double horizontal padding on `plugin-detail` / `bundle-detail`.

---

# Antigravity for Android v2.34.16

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.16)

### 1. Local GitHub Plugin Discovery & Multi-Account PAT Support
* **User Plugin Seeding (`~/.gemini/config/plugins/`):** Seed bundled local plugins (`GitHub (Local)`) into `~/.gemini/config/plugins/` (where `language_server` discovers user plugins and `auth.json` providers) so **Customizations → Installed → Plugins → GitHub (Local)** shows up with multi-account PAT support, while keeping `swarm` in `builtin/plugins/swarm`.

### 2. Customizations & SPA Step-by-Step Back Navigation
* **Renderer-Initiated History & Overlay Back Handling:** Fix Android Back navigation inside **Customizations** (and all SPA routes, modals, bottom sheets, and mobile sidebar) via renderer-initiated `window.history.back()` (`__TSR_index > 0`), ancestor z-index overlay handling, and clean Home `/` exit.

---

# Antigravity for Android v2.34.15

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.15)

### 1. Plugin OAuth `redirect_uri_mismatch` Fix
* **Local `REDIRECT` Mode on Loopback:** Fixed Gmail / Google Workspace and MCP plugin OAuth `Error 400: redirect_uri_mismatch` by forcing local `REDIRECT` mode on localhost so OAuth callbacks match the registered local redirect URI.

### 2. Step-by-Step Settings Back Navigation
* **Android Back Button in Settings:** Updated Android Back navigation (`BackNavigator` + `back_navigation` web patch) to step back one screen at a time inside Settings instead of dismissing Settings immediately.

### 3. Built-in Multi-Account GitHub Plugin
* **Local GitHub Plugin (`plugins/github/`):** Bundled a built-in multi-account GitHub plugin under **Customizations → Plugins**, supporting labeled accounts with PAT authentication and `GH_TOKEN` / `GITHUB_TOKEN` environment variable delivery.

### 4. Settings → Account Screen Fix (Upstream)
* Fixed a `web/patch_main.py` hook scoping bug where `GOb` (`Settings → Account`) failed to initialize `_agStore`/`_agRefreshStatus` and received shadowed local state, causing a blank screen when tapping the profile row.

---

# Antigravity for Android v2.34.14

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.14)

### 1. Swarm Plugin & Node.js Runtime Reliability
* **Dynamic `node` Bridge & Auto-Seed:** Added `nodejs-lts` to `env/seed-packages.txt`, upgraded `ShellBridgeInstaller` to resolve `$PREFIX/bin/node` dynamically, ensured `pkg` overwrites stale fallback wrappers on install, and triggered post-startup builtin plugin sync so Swarm sidecars launch reliably without manual setup.

### 2. Background-Resume Stuck UI Fix
* **Stream Continuity & Host Resume Recovery:** Kept WebView/SSE connections alive across app backgrounding and added host-resume stalled state stream force-reset in `bridge.core.js` / `MainActivity` / `WebViewLifecycle`, eliminating frozen or stuck chat/agent UI when returning to the app.

### 3. Multi-Account Vault & Instant Switcher in Settings (Upstream)
* **Native Settings Integration (`Accounts` & `Account` → `Saved Accounts`):** Added an **Accounts** screen in Settings and embedded **Saved Accounts** inside the **Account** screen using the native Settings UI primitives (`LU`/`PU`/`NU`/`OU`/`BI`/`Dgb`), displaying every account ever signed into with avatar, name, email, tier badge, and active status.
* **Owner-Only Credential Vault (`AccountVaultStore`):** Automatically snapshots `jetski-standalone-oauth-token` and profile metadata on every login (`0600` permissions in `$HOME/.gemini/agy-account-vault.json`) without ever exposing raw tokens to WebView JavaScript.
* **Instant In-Place Switching:** Switches accounts in place by evicting core auth caches, atomically activating the saved token on disk, refreshing `checkCurrentAuthStatus()` and `userStatus` without leaving Settings, and cleanly falling back to core restart if needed.

---

# Antigravity for Android v2.34.13

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.13)

### 1. Clean UI & Diagnostics Refinements
* Removed the floating `AGY` pill and `QuickControlSheet` ("Antigravity Control") overlay from the UI, keeping the web workspace clean while retaining passive 24h `UpdateChecker` release notifications and background Doze/OOM `RootResilience`.

---

# Antigravity for Android v2.34.12

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.12)

### 1. Accelerated `pkg` Package Manager (`pkg_core.py`)
* Added unified Python engine `pkg_core.py` (invoked with `-B` and `sys.dont_write_bytecode = True`, keeping Bash/AWK fallbacks): mtime-cached `db_sync_dpkg` and `Packages` index parsing, full dependency planning for `install` and `upgrade` with free-space preflight.
* Parallel archive downloads with `curl -Z --http2`, per-file SHA-256 validation and per-file mirror fallback (`mirrors.tuna` moved to bottom of `repo.conf`), plus conditional `curl -z` updates.
* Single-pass staged `.deb` installation (`stage_install_deb`) with explicit `data/(data|user/<id>)/<pkg>/files/usr` prefix stripping, pre-write rejection of path traversal and escaping symlinks, byte-length-preserving ELF prefix rewriting, and auto cache cleanup (`--keep-cache` to retain `.deb`s).

### 2. IPC Security, Daemon Stability & Lifecycle
* **Gateway & AndroidBridge:** Per-boot `0600` bearer token in `SystemGatewayServer`, bounded `/exec` concurrency, exact loopback core TLS pinning in WebView, and `blobBridgeToken` verification across mutating `AndroidBridge` methods.
* **Background Daemon (`agy-daemon`):** Passes `LD_LIBRARY_PATH` cleanly from `CoreServerService`, uses persistent `fcntl.flock` on `daemon.lock` with PID verification, and eliminates duplicate `daemon.log` lines.
* **UI, Teardown & Log Bounds:** Idempotent UI-thread `SplashController` WebView `destroy()` on `dismiss()` (lazily rebuilt if re-shown), clickable core service notification resuming `MainActivity`, `CrashRecorder` capped to 10 reports, capped/rotated `core_stderr.log`, dead-PID `hub_*.json` pruning, and background WakeLock management.

### 3. Features (UI, Root & Core)
* Passive 24h GitHub Release `UpdateChecker`, chat clipboard and IME (`commitContent`) image paste with background resizing, clickable background agent completion notifications, native root support (`RootBridge`, `RootResilience`, `agy-root`, `su`, `sudo`, `agy-sys`), and hardened Go TLS/DNS + `FileDownloader` / stream recovery.

---

# Antigravity for Android v2.33.0

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass (requires network tunnel/VPN where applicable). |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for binary verification. |

---

## Changes & Fixes (v2.33.0)

### 1. Sync with Official Google Antigravity v2.33.0 Core
* Synchronized app version (`APP_VERSION_NAME=2.33.0`) and core version (`CORE_VERSION=2.33.0`).
* Updated binary patch pipeline (`modules/patches`): unified signatures for `sigill_handler` and `resolv_redirect` for newer Go runtime builds.
* Fixed `crash_suppressor` (`failfast_cpu_and_atomic`) signatures for Google Antigravity v2.33.0, eliminating `FATAL ERROR: This binary was compiled with lse enabled` and `SIGILL` (exit 132) on ARMv8.0-A CPUs (Cortex-A53, Helio G25/A22, Snapdragon 685).
* Completed full modular test verification across all subsystems (`core`, `patches`, `io`, `runner`, `env`, `builder`).

---

# Antigravity for Android v2.4.1

## Changes & Fixes (v2.4.1)

### 1. Built-in Web Terminal & PTY Support (PR #19)
* **Length-Preserving PTY Shell Binary Patch:** Fixed terminal crashes caused by missing `/bin/bash` on Android by patching `/bin/bash` to `/bin///sh` (exact 9 bytes) without shifting ELF section offsets.
* **Prioritized `$SHELL` Resolution in `CoreEnvironment`:**
  1. `$PREFIX/bin/bash` (full interactive bash with `.bashrc`, completion, and history)
  2. `runtime/bin/bash` (bundled bridge runtime)
  3. `/system/bin/sh` (system fallback shell)
* **Terminal Emulation & `terminfo`:** Added `TERM=xterm-256color` and `TERMINFO` pointing to `$PREFIX/share/terminfo` for proper colors and ANSI sequences in interactive TUI apps (`nano`, `htop`, `mc`).

### 2. Local APK Build Stability (AAPT)
* Exported `ANDROID_DATA="${ANDROID_DATA:-/data}"` in `modules/builder/02_r_java.sh` and `06_package.sh` to prevent `aapt` `SIGABRT (134)` crashes when building APKs directly inside Android ARM64 userspace.

---

## Earlier Key Capabilities (v2.4.0)

### 1. Security & Data Isolation (Issue #6)
* Removed `android:debuggable="true"` from production builds (isolated solely to `--dev` / `com.agy.dev`), blocking `run-as com.agy` credential extraction.

### 2. Native Android File & Media Picker (Issue #8)
* Replaced SAF-only document picker with the universal Android chooser supporting photos, videos, and files from Gallery, Photos, and third-party managers with persistent URI permissions (`Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION`).

### 3. ARMv8.0-A CPU Compatibility
* Added `liblse_compat.so` atomic/SIMD instruction emulation and `sigill_handler` to prevent `SIGILL: illegal instruction` on Snapdragon 680/685 / Cortex-A53 devices.

### 4. Streamlined OAuth Authentication
* Removed 80 ms disk polling and temp files; captures auth URLs directly from core stdout and launches the system browser cleanly.

### 5. Hardware Flashlight Bridge
* Added camera flash control via `torch on` / `torch off`.

### 6. Background Micro-Daemon (`agy-daemon`)
* Added low-footprint (<10 MB RAM, 0% idle CPU) scheduler for delayed/interval/cron tasks, file watchers, and Android notifications (`agy-notify`).

### 7. Core Startup Diagnostics
* Added **Copy Logs** and **Save Logs** actions directly on the startup failure overlay.

### 8. Modular Gradle-Free Build Pipeline
* Split architecture into `modules/patches`, `modules/runner`, and `modules/builder` using compiler `@argfile` lists to avoid Linux `ARG_MAX` limits.

---

## Upgrade & Compatibility

All official CI releases are signed with the same persistent signing keystore. Updates install in-place over prior v2.x releases without uninstalling or losing workspace files, sessions, or `Documents`.
