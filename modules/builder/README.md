# APK Builder Module: `modules/builder`

> Self-contained Android APK build pipeline without Gradle or Android Studio.
> Optimized for native execution directly on Android (`ARM64 / Linux`) as well as Linux CI.

---

## 1. Architecture Overview

The Antigravity APK builds independently of Gradle, AGP, and Kotlin/Groovy daemons. [`build_apk.sh`](build_apk.sh) orchestrates atomic build stages driving low-level Android SDK tools (`aapt`, `javac`, `d8`/`r8`, `zipalign`, `apksigner`) alongside focused Python helpers.

### Pipeline Diagram

```mermaid
flowchart TD
    Env[config/app.env] --> P01[01_gen_config.py]
    Src[app/src/main/...] --> P01
    
    subgraph Config & Resources
        P01 -->|BuildConfig.java| GenDir[build/gen]
        P01 -->|gen.xml| BuildRes[build/res/values]
        P01 -->|Dev-aware Manifest| TargetManifest[build/AndroidManifest.xml]
        TargetManifest & ResDir[app/src/main/res] & BuildRes --> P02[02_r_java.sh: aapt overlay]
        P02 -->|R.java| GenDir
    end

    subgraph Bytecode & Dexing
        GenDir & Src --> JavaArg[@build/java_sources.txt]
        JavaArg --> P03[03_compile_java.sh: javac]
        P03 -->|*.class| ObjDir[build/obj]
        ObjDir --> ClassArg[@build/class_files.txt]
        ClassArg --> P04[04_dex.sh: D8 via r8.jar]
        P04 -->|classes.dex| DexDir[build/dex]
    end

    subgraph Assets & Packaging
        Runtime[modules/runner/prepare_runtime] --> P05[05_stage_assets.py]
        Web[web/] & EnvDist[env/dist] --> P05
        P05 -->|staged assets| StagingDir[build/staging/assets]
        
        TargetManifest & ResDir & BuildRes & StagingDir & DexDir --> P06[06_package.sh: aapt + zip]
        P06 -->|raw APK| Unaligned[build/unaligned.apk]
    end

    subgraph Align, Sign & Verify
        Unaligned --> P07[align.py: zipalign / python]
        P07 -->|4-byte aligned APK| Aligned[build/aligned.apk]
        Aligned --> P08[08_sign.sh: apksigner]
        P08 -->|Signed APK (v1+v2+v3)| Dist[dist/antigravity.apk / antigravity-dev.apk]
        Dist --> P09[09_verify.sh: apksigner verify]
    end
```

---

## 2. Build Stages

| Stage | Script | Core Tool | Inputs | Outputs | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **01** | [`01_gen_config.py`](01_gen_config.py) | Python 3 | `config/app.env`, `AndroidManifest.xml` | `build/gen/**/BuildConfig.java`, `build/res/values/gen.xml`, `build/AndroidManifest.xml` | Generates constants, ports, and dev-aware manifest inside `build/` without dirtying git |
| **02** | [`02_r_java.sh`](02_r_java.sh) | `aapt` | `AndroidManifest.xml`, `res/`, `build/res/`, `android.jar` | `build/gen/**/R.java` | Compiles resource IDs using resource overlay, skipping duplicate `aapt` in release builds |
| **03** | [`03_compile_java.sh`](03_compile_java.sh) | `javac` | `@build/java_sources.txt`, `android.jar` | `build/obj/**/*.class` | Compiles Java sources via `@argfile` (`ARG_MAX` safe) |
| **04** | [`04_dex.sh`](04_dex.sh) | `D8` (`tools/r8.jar`) | `@build/class_files.txt`, `android.jar` | `build/dex/classes.dex` | Converts `.class` bytecode to `classes.dex` via D8 `@argfile` |
| **05** | [`05_stage_assets.py`](05_stage_assets.py) | Python 3 + `modules/runner` | Patched core runtime, `web/`, `env/dist/env` | `build/staging/assets/` | Builds and stages verified runtime, web, and env assets |
| **06** | [`06_package.sh`](06_package.sh) | `aapt` + `zipfile` | Resources + overlay `build/res`, manifest, `classes.dex`, `staging/assets/` | `build/unaligned.apk` | Creates base APK package and injects `classes.dex` + assets |
| **07** | [`align.py`](align.py) | `zipalign` / Python | `build/unaligned.apk` | `build/aligned.apk` | 4-byte ZIP entry alignment (4096-byte for `.so`) for `mmap()` |
| **08** | [`08_sign.sh`](08_sign.sh) | `apksigner` | `build/aligned.apk`, `tools/debug.keystore` | `dist/antigravity.apk` or `dist/antigravity-dev.apk` | Signs APK with schemes v1, v2, and v3 |
| **09** | [`09_verify.sh`](09_verify.sh) | `apksigner` | `dist/*.apk` | Verification output on stdout | Verifies signature schemes and digests |

---

## 3. Detailed Stage Reference

### Stage 01: Config Generation (`01_gen_config.py`)
- **Input:** Variables from `config/app.env` and `DEV_MODE` (`0` or `1`).
- **Output:**
  - `build/gen/com/agy/BuildConfig.java`: `PORT`, `GATEWAY_PORT`, `CORE_VERSION`, `APP_VERSION_NAME`, `APP_VERSION_CODE`, `DEBUG`.
  - `build/res/values/gen.xml`: string and integer XML resources generated inside `build/` so git status stays clean.
  - `build/AndroidManifest.xml`: substitutes `${applicationId}`; in dev mode sets `package="com.agy.dev"`, `android:debuggable="true"`, and isolates `INTERNAL_BROADCAST`.

### Stage 02: R.java Generation (`02_r_java.sh`)
- Runs `aapt package -m -J <gen_dir>` with `-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`.
- Skips duplicate second `aapt` pass for `com.agy` release builds; for dev (`com.agy.dev`), also generates compatibility `com.agy.R` via `--custom-package com.agy`.

### Stage 03: Java Compilation (`03_compile_java.sh`)
- Collects Java sources (`app/src/main/java` + `build/gen`) into `@$BUILD_DIR/java_sources.txt` to avoid Linux `ARG_MAX` overflow.
- Compiles with `javac -encoding UTF-8 -source 1.8 -target 1.8 -cp tools/android.jar @$BUILD_DIR/java_sources.txt`.

### Stage 04: D8 Dexing (`04_dex.sh`)
- Lists `.class` files in `@$BUILD_DIR/class_files.txt` and builds `classes.dex` using standalone D8 (`com.android.tools.r8.D8` in `tools/r8.jar`) with `--min-api 24`, passing `--release` for standard builds and `--debug` when `DEV_MODE=1`.

### Stage 05: Asset Staging (`05_stage_assets.py`)
- Stages three asset groups into `build/staging/assets/`:
  1. `runtime/`: patched & checksum-verified `language_server` bundle prepared by `modules/runner/prepare_runtime.py`.
  2. `web/`: runs `bash web/build.sh` automatically before staging, copying built bundles while filtering development-only files (`main.js`, `src/`, `tests/`, `build.sh`, `patch_main.py`, `src.manifest`) via `web_ignore`.
  3. `env/`: Bionic userspace bootstrap, overlays, `pkg`, `libtermux-exec.so`, and `manifest.json`.

### Stage 06: APK Packaging (`06_package.sh`)
- Uses `aapt package -f` with resource overlays for base manifest and `resources.arsc`, then appends `classes.dex` and `assets/` via streaming Python ZIP logic (`ZIP_STORED` for pre-compressed png/gz/jpeg/zip payloads, `ZIP_DEFLATED` otherwise).

### Stage 07: ZipAlign (`align.py`)
- Aligns uncompressed ZIP payloads to 4-byte boundaries (4096 bytes for native `.so` libraries) for zero-copy `mmap()` loading.
- Uses native `zipalign` when present in `$PATH`, falling back to a cross-platform streaming Python implementation.

### Stages 08 & 09: Signing & Verification (`08_sign.sh` & `09_verify.sh`)
- Signs using `apksigner sign` (`tools/debug.keystore`) with APK Signature Schemes v1, v2, and v3, then validates via `apksigner verify --verbose`.

---

## 4. Dependencies & Tooling

| Tool | Minimum Version | Purpose | Location |
| :--- | :--- | :--- | :--- |
| **Python** | 3.8+ | Config generation, asset staging, packaging, Python ZipAlign | System `$PATH` |
| **aapt** | v0.2+ (v1) | XML compilation, resource IDs, APK skeleton | System `$PATH` |
| **javac** | OpenJDK 8 / 11 / 17 | Compiles `.java` -> `.class` | System `$PATH` |
| **java** | JRE 8+ | Runs R8/D8 | System `$PATH` |
| **r8.jar** | Android R8/D8 | DEX compiler (`bytecode -> classes.dex`) | `tools/r8.jar` |
| **android.jar** | API 24+ (currently API 28) | Android Framework stub classes | `tools/android.jar` |
| **zipalign** | Any (optional) | Native 4-byte ZIP alignment | System `$PATH` |
| **apksigner** | 0.9+ | APK signing and signature verification | System `$PATH` |
| **debug.keystore** | JKS/PKCS12 | Signing keystore | `tools/debug.keystore` |

---

## 5. Usage

```bash
# Build standard release APK (dist/antigravity.apk, port 45157):
bash modules/builder/build_apk.sh

# Build release APK with region bypass (dist/antigravity-region-bypass.apk, port 45157):
bash modules/builder/build_apk.sh --region-bypass

# Run builder module tests:
bash modules/builder/tests/run_all.sh
```

---

## 6. Audit Findings & Optimizations

1. **`align.py` raw stream corruption (fixed):** Rewrote Python fallback to stream raw compressed payloads at 64 KB chunk boundaries (`O(1)` memory) instead of decompressing via `zin.read()` while preserving `compress_type=8` headers.
2. **Generated `gen.xml` workspace pollution (fixed):** Moved generated `gen.xml` into `$BUILD_DIR/res/values/gen.xml` with `aapt` overlay flags (`-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`) so git stays clean.
3. **Duplicate `aapt` invocation in `02_r_java.sh` (fixed):** Skips redundant second `aapt` invocation when target package equals `com.agy`.
4. **Linux `ARG_MAX` limits in `03_compile_java.sh` & `04_dex.sh` (fixed):** Switched `javac` and `D8` inputs to `@java_sources.txt` and `@class_files.txt` argument files.
5. **Web asset freshness & `src.manifest` filtering (fixed):** `05_stage_assets.py` triggers `bash web/build.sh` automatically and excludes `src.manifest` via `web_ignore`.
6. **Remaining potential improvements:** Merging sign+verify JVM invocations to reduce JVM startup overhead, propagating `--fast` into `05_stage_assets.py` / `06_package.sh` for incremental rebuilds, and aligning directly into `dist/*.apk` to avoid redundant full-APK writes.
