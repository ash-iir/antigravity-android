# Antigravity Android Agent Rules & Environment Guidelines

> [!NOTE]
> This rule is automatically loaded into every conversation from `~/.gemini/config/AGENTS.md` in Antigravity for Android (AGY v2).
> It defines the operating environment, execution rules, package management, elevated permissions, and development standards.

---

## 1. Platform & Environment Identity

- **Platform:** Native Android (ARM64 / `aarch64` architecture, `minSdk 24` / `targetSdk 28`, **ARMv8.0-A+ baseline** with `liblse_compat.so` LSE emulation and native ARMv8.1-A+ LSE atomics).
- **Application Context:** Runs directly inside the Antigravity (`com.agy`, or `com.agy.dev` in dev builds) sandbox.
- **Key Filesystem Paths:**
  - Application Root (`$HOME`): `/data/user/0/com.agy/files` (or `com.agy.dev` in dev builds; app-private `files/` directory).
  - Linux Userspace Prefix (`$PREFIX`): `$HOME/usr` (`/data/user/0/com.agy/files/usr`).
  - Binary Directory: `$PREFIX/bin`.
  - Runtime Internals: `$HOME/runtime/bin`.
  - External / Shared Storage: `/storage/emulated/0` (Projects typically reside in `/storage/emulated/0/Documents/`).
  - AI State & Config: `$HOME/.gemini/config/`.
  - Conversation Brain & Artifacts: `$HOME/.gemini/antigravity-app/brain/<conversation-id>/`.
  - Daemon State & Logs: `$HOME/.gemini/antigravity-app/daemon/`.

---

## 2. Terminal Execution Rules

- **Pre-configured Environment (NO REDUNDANT EXPORTS):**
  Antigravity pre-configures `$PATH` (`$PREFIX/bin:/system/bin` in terminal tools; core processes prepend `$HOME/runtime/bin`), `$HOME`, `$PREFIX`, and `GIT_CONFIG_NOSYSTEM=1`.
  **DO NOT** prepend `export PATH=...` or `export HOME=...` before commands. Execute binaries (`python`, `git`, `pkg`, `bash`, `agy-daemon`) directly.
- **No Bubblewrap / Desktop Sandboxing:**
  Standard desktop Linux bubblewrap (`bwrap`) is **not supported** by the Android kernel and SELinux. Never attempt to invoke `bwrap` or run commands wrapped in desktop sandboxes. All shell commands execute directly within the native userspace shell.
- **Shared Storage `noexec` Restriction:**
  Android mounts `/storage/emulated/0` with the `noexec` flag. Directly running `./script.sh` or executable binaries from shared storage fails with `Permission denied` (exit code 126).
  **Always invoke scripts with their explicit interpreter:**
  ```bash
  bash script.sh
  python script.py
  sh script.sh
  ```
  Native compiled binaries must reside in `$PREFIX/bin` or internal storage (`$HOME/`).
- **Path Quoting:**
  Shared storage paths frequently contain spaces (e.g. `/storage/emulated/0/Documents/antigravity release/`). Always quote file paths in commands.
- **Git on Android Shared Storage:**
  Git in `/storage/emulated/0/...` may trigger `fatal: detected dubious ownership`. Configure `safe.directory` and keep `GIT_CONFIG_NOSYSTEM=1` (already exported by Antigravity) to avoid nonexistent system gitconfig errors:
  ```bash
  export GIT_CONFIG_NOSYSTEM=1
  git config --global --add safe.directory "<path>"
  ```

---

## 3. Package Management (`pkg`)

AGY includes a native package manager (`pkg`) backed by Python (`pkg_core.py` for `install`, `upgrade`, `list`, and `search`) with a Bash & AWK fallback:

- **Usage & Syntax:**
  ```bash
  # Update package indexes (run before installing if index is empty):
  pkg update

  # Upgrade installed packages:
  pkg upgrade

  # Install packages (-y/--yes, -q/--quiet, -f/--fix-broken, --keep-cache are accepted):
  pkg install git python nodejs bash

  # Search, list, and query:
  pkg search <regex>
  pkg list [regex]
  pkg installed
  pkg info <package>
  pkg why <file>

  # Remove package or clear cached .deb archives:
  pkg remove <package>
  pkg clean

  # Self-test system health and print version:
  pkg selftest
  pkg version
  ```
- **Post-Install Shebang Fixes:**
  After installing global Python/Node scripts (`pip`, `npm`), rewrite hardcoded prefixes and `/usr/bin/env` shebangs using:
  ```bash
  agy-fix-paths
  ```

---

## 4. Elevated Operations (`agy-root` / `sudo` / `agy-sys` & Shizuku `rish`)

### Native Root (`uid=0`)
For rooted devices (KernelSU, bakaSU, Magisk, APatch), use `agy-root`, `sudo`, `su`, or `agy-sys`:
```bash
agy-root status                         # Check root status & manager (also: sudo -v)
agy-root -c "<command>"                 # Run command as uid=0 preserving AGY env
agy-root -mm -c "<command>"             # Run in global mount namespace (e.g. for /storage/emulated/0/Android/data)
agy-root --fallback-rish -c "<command>" # Fall back to Shizuku (rish) if root is unavailable
agy-sys info                            # System, kernel, root, RAM, Doze & OOM status
agy-sys android-data [subpath]          # List /storage/emulated/0/Android/data via -mm
agy-sys install <path.apk>              # Install/upgrade APK via pm install -r
agy-sys logcat [lines]                  # Filtered Android logcat (default: 120)
```

### Shizuku ADB Shell (`rish`, `uid=2000`)
Antigravity does **not** link the Shizuku Binder API library; instead it provides the `rish` CLI wrapper (`$PREFIX/bin/rish` and `$HOME/runtime/bin/rish`):
- **Prerequisites (Not Pre-Bundled):**
  1. `rish` requires `rish_shizuku.dex`, which is **not** shipped in the repo/prefix by default. In **Shizuku Manager → Use Shizuku in terminal apps → Export files**, export `rish_shizuku.dex` and place it in `$PREFIX/bin/`, `$PREFIX/share/rish/`, or `$HOME/runtime/bin/` (with `chmod 400` on Android 14+). Without it, `rish` fails with `rish_shizuku.dex not found`.
  2. Shizuku must be running, and Antigravity (`com.agy` / `com.agy.dev`) must declare both `moe.shizuku.manager.permission.API_V23` and `<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />` in `AndroidManifest.xml` to appear in Shizuku Manager's **Authorized applications** list.
- **Common Elevated Operations (once `rish_shizuku.dex` & Shizuku authorization are active):**
  ```bash
  rish -c "ls -la /storage/emulated/0/Android/data"
  rish -c "pm install -r /storage/emulated/0/.../app.apk"
  rish -c "pm list packages -3"
  rish -c "am start -n com.example.app/.MainActivity"
  rish -c "logcat -d -t 150"
  rish -c "dumpsys package com.example.app"
  rish -c "getprop ro.build.version.release"
  rish -c "settings get global airplane_mode_on"
  ```

---

## 5. Development & Server Guidelines

- **Port Binding:** Local development servers (Vite, Next.js, Express, FastAPI) MUST bind to `localhost` or `127.0.0.1`.
- **Reserved Ports:** Never bind dev servers to Antigravity core ports `45157` (release) / `45158` (dev) or System Gateway ports `45160` (release) / `45161` (dev).
- **Safe Dev Ports:** `3000`, `5173`, `8000`, `8080`.
- **Native Compilation:** When compiling native C/C++/Rust code, target `-march=armv8-a` baseline (ARMv8.0-A minimum, with runtime LSE emulation or native atomics).

---

## 6. Background Daemon & Notifications (`agy-daemon`, `agy-notify`)

Antigravity includes an ultra-lightweight background scheduler, watcher, and task manager:
- **Footprint:** <10MB RAM, 0.0% CPU idle.
- **Persistence:** Runs alongside `CoreServerService` under Android Foreground Service Partial WakeLock (executes even with screen off or locked).

### Service Lifecycle
```bash
agy-daemon status              # Check daemon status, PID, and recent daemon logs
agy-daemon start               # Start background daemon (add --foreground/-f to debug)
agy-daemon stop                # Stop daemon service
agy-daemon restart             # Restart daemon service
```

### Task Management (`agy-daemon task`)
Scheduled tasks execute shell `--command`s (and support `--json` output). Note that `--prompt` does **not** invoke an AI agent: if `--command` is omitted, `--prompt` posts a notification (`agy-notify post -t 'AI Task Trigger' -m '<first 100 chars>'`).

```bash
# List tasks:
agy-daemon task list
agy-daemon task list --json
agy-daemon task list --active-only

# Add scheduled tasks (one trigger flag per task; do NOT combine --interval with watcher triggers):
agy-daemon task add --name "Notify Later" --delay 30m --command "agy-notify post -t 'Reminder' -m 'Check test results'"
agy-daemon task add --name "Disk Health" --interval 1h --command "df -h > ~/.gemini/disk.log"
agy-daemon task add --name "Nightly Clean" --cron "0 3 * * *" --command "rm -rf ~/.gemini/tmp/*"

# Event & Watcher Triggers (uses built-in polling intervals; omit --interval because --interval takes precedence):
agy-daemon task add --name "Watch PRs" --github-pr "owner/repo" --command "agy-notify post -t 'New PR' -m 'PR detected'"
agy-daemon task add --name "Watch Issues" --github-issue "owner/repo" --command "agy-notify post -t 'New Issue' -m 'Issue detected'"
agy-daemon task add --name "File Watcher" --watch-file "/path/to/project/build.gradle" --command "pkg selftest"
agy-daemon task add --name "Battery Alert" --battery-below 15 --command "agy-notify post -t 'Low Battery' -m 'Save your work'"
agy-daemon task add --name "Webhook" --webhook-port 45199 --command "agy-notify post -t 'Webhook' -m 'Triggered'"

# Manage tasks:
agy-daemon task pause <task_id_or_name>
agy-daemon task resume <task_id_or_name>
agy-daemon task run-now <task_id_or_name>
agy-daemon task logs <task_id_or_name>
agy-daemon task remove <task_id_or_name>
agy-daemon clear
```

### Android Status Notifications (`agy-notify`)
Post or manage notifications directly in the Android status bar (posting/listing/cancelling uses `rish`, falling back to `cmd notification` or queued log output):
```bash
agy-notify post -t "Build Finished" [-s "Subtitle"] -m "Tests passed without errors" [--tag build_done]
agy-notify list
agy-notify cancel <tag>    # Takes a single positional <tag> (not -t); pass --tag on post to cancel it later
```

---

## 7. Project Architecture & Sync Rules (Documentation-First)

- **Decoupled Versioning (`config/app.env`):** `APP_VERSION_NAME` / `APP_VERSION_CODE` track Android app releases independently (`MAJOR.MINOR.PATCH`), while `CORE_VERSION` (and `modules/core/spec/version.env`) tracks upstream Antigravity `language_server`. Use `python3 modules/runner/auto_update.py bump <patch|minor|major>` for app-only releases.
- **Documentation Sync:** Every module (`modules/*`, `app/`, `web/`, `env/`) must maintain an accurate, concise `README.md`. Any change to code, interfaces, or flags MUST be accompanied by immediate documentation updates.
- **No Hardcoded Crutches:** Avoid hardcoding paths or assumptions where dynamic calculation (`$PREFIX`, `$HOME`, `$PROJECT_ROOT`) is possible.
- **Clean State:** Keep working trees clean, avoid creating dead or duplicated state, and do not commit temporary build artifacts.
