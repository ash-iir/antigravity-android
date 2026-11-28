<div align="center">

  <img src="docs/assets/logo.png" alt="Antigravity" width="88" height="88" style="border-radius: 20px;" />

  # Antigravity for Android

**Autonomous AI development environment running natively on Android & ARM64.**

  [![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
  [![Arch](https://img.shields.io/badge/ARM64-v8.0--A%2B-blue?style=flat-square)](https://arm.com)
  [![Release](https://img.shields.io/badge/App-v3.0.0-blueviolet?style=flat-square)](https://github.com/salmanbappi/antigravity-android/releases)
  [![Core](https://img.shields.io/badge/Core-2.36.0-informational?style=flat-square)](modules/core/spec/version.env)
  [![Discord](https://img.shields.io/badge/Discord-Community-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.gg/r4XTBuWpvA)
  [![License](https://img.shields.io/badge/License-Apache_2.0-orange?style=flat-square)](LICENSE)

  <br/>

  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero_dark.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/hero_light.png">
    <img alt="Antigravity Interface" src="docs/assets/hero_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128,128,128,0.2);" />
  </picture>

</div>

---

### Overview

**Antigravity for Android** brings autonomous agentic coding directly to mobile hardware.

No PRoot, chroot, or virtualization overhead. Antigravity runs **bare-metal inside native Android userspace (Bionic)**, unleashing the full read/write speed of UFS storage and physical CPU cores. Driven by Gemini models, the agent inspects repositories, authors code across multiple files, compiles binaries, and runs tests completely on-device.

---

### App Previews

<p align="center">
  <a href="docs/assets/preview_1.png"><img src="docs/assets/preview_1.png" width="165" alt="Voice Mode" title="Voice Mode" /></a>
  <a href="docs/assets/preview_2.png"><img src="docs/assets/preview_2.png" width="165" alt="MCP Server Diagnostics" title="MCP Diagnostics" /></a>
  <a href="docs/assets/preview_3.png"><img src="docs/assets/preview_3.png" width="165" alt="Settings & Permissions" title="Settings & Permissions" /></a>
  <a href="docs/assets/preview_4.png"><img src="docs/assets/preview_4.png" width="165" alt="Swarm Mention Graph" title="Swarm Graph" /></a>
  <a href="docs/assets/preview_5.png"><img src="docs/assets/preview_5.png" width="165" alt="Swarm Hive View" title="Swarm Hive" /></a>
</p>

<p align="center">
  <sub>Voice Mode &bull; MCP Diagnostics &bull; Settings &amp; Permissions &bull; Swarm Graph &bull; Swarm Hive</sub>
</p>

---

### Highlights in v2.4

* **Modular Core:** Clean separation into 6 standalone subsystems (`modules/*`) with independent test suites.
* **Native Splash:** Canvas-based dot animation saving ~40 MB RAM with instant startup.
* **Desktop Mode:** Instant toggle in Appearance settings for tablets, external keyboards, and Samsung DeX.
* **Universal ARMv8.0-A Support:** Built-in transparent atomic & SIMD emulation (`liblse_compat.so`) for older CPUs.
* **Background Micro-Daemon:** Ultra-lightweight `agy-daemon` (<10 MB RAM) with cron scheduler, file watcher, and AI task queue.

---

### Key Features

* **Autonomous Agent:** Plans tasks, edits code across project trees, executes shell tools, and fixes build errors.
* **Elevated Control (Root & Shizuku):** Optional native `uid=0` support (`agy-root`, `su`, `sudo`, `agy-sys`) for KernelSU/bakaSU/Magisk/APatch with Doze/OOM anti-kill resilience, alongside non-root Shizuku (`uid=2000`) via `rish`.
* **Chat Image Paste & Background Alerts:** Paste screenshots directly from Android clipboard or keyboard (`commitContent`), plus clickable background agent completion notifications.
* **Passive Update Checker:** Lightweight background GitHub Release checks (throttled to once every 24h) after UI launch.
* **Built-in `pkg` Manager:** Fast package manager installing development runtimes (`git`, `python`, `node`, `clang`) from Termux repos.
* **Adaptive Viewports:** Seamless touch-first mobile UI with optional desktop layout.
* **In-Place Upgrades:** Consistent keystore guarantees updating APKs without losing sessions or workspace data, paired with `agy-upgrade` CLI backup/restore.

---

### Quick Start

1. **Download APK** from [Releases](https://github.com/salmanbappi/antigravity-android/releases):
   * `antigravity.apk` — standard release (original Google account checks).
   * `antigravity-region-bypass.apk` — release with account eligibility check bypass.
2. **Grant Storage Permission** (`All Files Access`). *(Optional: enable Shizuku for ADB access)*.
3. **Install Runtimes** via terminal or agent:
   ```bash
   pkg update && pkg install git python nodejs
   ```

---

### Building & Testing

```bash
# Build standard release APK (dist/antigravity.apk):
bash build.sh

# Build release APK with region bypass (dist/antigravity-region-bypass.apk):
bash build.sh --region-bypass

# Run all test suites:
bash test_all_modules.sh
```

---

### Repository Structure

```text
antigravity/
├── config/             # App constants, environment paths, and AI rules
├── modules/            # Decoupled core modules (v2.4+)
│   ├── core/           # Binary unpacking, glibc loader, shims, SHA-256 manifest
│   ├── patches/        # Binary patch engine (syscall emulation, resolv, SIGILL)
│   ├── io/             # High-performance I/O Bus: event broker, FIFO pipes, sockets
│   ├── env/            # Path resolver, diagnostics, Flight Recorder logs, CLI
│   ├── builder/        # Gradle-free APK builder (javac, D8, aapt, zipalign, apksigner)
│   └── runner/         # Standalone runtime preparation, port probe, E2E tests
├── app/                # Android host app (native splash, WebView, system bridges)
├── web/                # Local web frontend, desktop mode toggle, themes
├── env/                # Bionic userspace, Termux overlay, pkg manager, liblse_compat
├── docs/assets/        # Screenshots, banners, and diagrams
├── build.sh            # Root APK build script
└── test_all_modules.sh # Test suite for all 6 modules
```

> [!TIP]
> Each module in `modules/`, `app/`, `web/`, and `env/` includes its own dedicated `README.md` with in-depth technical documentation.

---

<div align="center">
  <sub>Join our <a href="https://discord.gg/r4XTBuWpvA">Discord Community</a> &bull; Licensed under the <a href="LICENSE">Apache 2.0 License</a></sub>
</div>
