# Antigravity for Android v2.35.3

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.35.3)

### 1. Startup Crash Fix
* Fixed an `IllegalStateException` crash on launch in `MainActivity.onCreate` caused by attaching `SplashController`'s container to `rootLayout` twice after the `NativeSplashDotsView` refactor in `v2.35.2`.

---

# Antigravity for Android v2.35.2

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.35.2)

### 1. Upstream Antigravity 2.35.2 Core & Web UI
* Updated core `language_server` (`2.35.2-4585983447007232`) and web UI assets (`main.js`, `compiled_tailwind.css`, `shiki_worker.js`, `page_agent_bundle.js`, `page_facade_bundle.js`, `prism/`, `voices/`).
* Updated `pidfd`, `crash_suppressor`, and `resolv_redirect` ELF patches for `2.35.2`, adding dynamic ELF `.rela.dyn` fallback resolution for `net.defaultNS`.

### 2. Mid-Range Device & Long-Session Performance Optimizations
* **WebView Resilience & Native Splash:** Implemented `onRenderProcessGone` auto-recovery and `RENDERER_PRIORITY_BOUND` in `AgyWebViewClient`/`WebViewFactory`, and switched `SplashController` to `NativeSplashDotsView` (saving ~40 MB RAM on startup).
* **Go Memory & ARMv8.0 Tuning:** Added RAM-scaled `GOMEMLIMIT`/`GOGC`, raised `--enable_sidecars` threshold to 5.5 GB RAM, and applied granular `GODEBUG` crypto flags so hardware AES/SHA stays active when LSE `atomics` are absent.
* **Reduced I/O, GPU & Background Overhead:** Added buffered in-session `core.log` rotation, debounced/deduped `NetworkConfigWriter`, cached `LocalAssetServer` paths, disabled mobile `backdrop-filter` blurs in `gpu.js`, filtered streaming `#root` mutations in `touch_model_menu`, added adaptive idle sleep + log rotation to `agy-daemon`, switched ring buffers to `ArrayDeque`, and enabled `D8 --release` dexing.

---

# Antigravity for Android v2.34.15

## Release Packages

| Package | Application ID | Runtime Port | Description |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Standard production release with original Google account checks. |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Region-bypass edition with account eligibility check bypass. |
| **`SHA256SUMS.txt`** | — | — | Cryptographic SHA-256 checksums for verification. |

## Changes & Fixes (v2.34.15)

### 1. Settings → Account Screen Fix
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

### 1. Multi-Account Vault & Instant Switcher in Settings
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
