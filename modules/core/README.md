# Antigravity Core Module (`modules/core`)

> Foundational core distribution module for Antigravity v2: unpacking, AArch64 ELF/ABI validation, glibc runtime delivery, system shims, and cryptographic runtime assembly.

---

## 1. Responsibilities

`modules/core` underpins the Antigravity v2 runtime on Android (`ARM64 / AArch64`). It ships the core `language_server` binary, glibc shared libraries, TLS certificates, seed state, and tooling for:

1. **Unpacking (`unpack/`):** Detects archive format (`tar.gz` or raw ELF) and extracts `language_server` with proper POSIX permissions.
2. **Binary Validation (`validate/`):** Verifies SHA-256 checksums, 64-bit Little-Endian ELF headers, `EM_AARCH64` (`183`) machine type, and `ET_DYN`/`ET_EXEC` compatibility.
3. **Cryptographic Manifests (`manifest/`):** Streams 1 MB SHA-256 hashing to build and verify `manifest.json`.
4. **Runtime Bundling (`bundle/`):** Assembles an isolated runtime directory (`bin/`, `lib/`, `certs/`, `seed/`, `etc/`) consumed by `modules/runner` and `modules/builder`.
5. **System Shims (`shims/`):** Provides Linux compatibility shims (`xdg-open`, browser delegates, Shizuku `rish`).

---

## 2. Directory Layout

```text
modules/core/
├── spec/                       # Declarative core specs
│   ├── version.env             # Version variables, build tag, loader name
│   ├── checksums.json          # Reference SHA-256 digests and sizes
│   ├── glibc_deps.list         # Required glibc shared libraries
│   └── shims.list              # Installed shim binaries/symlinks
├── pristine/                   # Unmodified upstream core archive
│   └── language_server.tar.gz  # Pristine aarch64 language_server (~181 MB unpacked)
├── unpack/                     # Archive detection, extraction & chmod helpers
│   ├── archive_detector.py     # Detects tar_gz, elf, or unknown
│   ├── extractor.py            # Extracts core binary from tar.gz or raw ELF
│   └── permissions.py          # Sets POSIX execute/read bits
├── validate/                   # ELF and checksum validators
│   ├── elf_header.py           # Validates \x7fELF, 64-bit (EI_CLASS=2), LE (EI_DATA=1)
│   ├── elf_arch.py             # Validates e_machine == EM_AARCH64 (183)
│   ├── elf_compat.py           # Validates e_type (ET_EXEC / ET_DYN)
│   ├── checksum_checker.py     # Streaming SHA-256 file verifier
│   └── verify_pipeline.py      # 5-stage E2E validation pipeline
├── manifest/                   # Manifest builder & verifier
│   ├── file_hasher.py          # Streaming SHA-256 (1 MB chunks)
│   ├── manifest_builder.py     # Scans directory and writes manifest.json
│   └── manifest_verifier.py    # Verifies directory against manifest.json
├── bundle/                     # Runtime bundle assembler
│   ├── layout.py               # Initializes bin, lib, certs, seed, etc dirs
│   ├── pack_binaries.py        # Copies core, loader, and browser/system shims
│   ├── pack_libraries.py       # Copies glibc libs and etc DNS/hosts configs
│   ├── pack_certs.py           # Copies CA certificates into certs/
│   ├── pack_seed.py            # Copies seed state files into seed/
│   └── assemble.py             # CLI & Python orchestrator for runtime assembly
├── glibc/                      # AArch64 glibc runtime libraries
│   ├── ld-linux-aarch64.so.1   # Dynamic linker / ELF interpreter
│   ├── libc.so.6, libm.so.6... # glibc core shared libraries
│   ├── liblse_compat.so        # Software LSE atomic emulator for ARMv8.0-A CPUs
│   └── etc/                    # Network configs (hosts, nsswitch.conf, resolv.conf)
├── certs/                      # TLS trust store
│   └── ca-certificates.crt     # Root CA certificate bundle
├── seed/                       # Initial onboarding state & rules
│   ├── AGENTS.md               # Default agent system prompt / rules
│   └── *.pbtxt                 # Initial protobuf onboarding state files
├── shims/                      # Browser and system helper shims
│   ├── browser/                # xdg-open and browser delegates (chrome, open, etc.)
│   ├── privilege/              # rish.sh (Shizuku ADB shell wrapper)
│   └── system/                 # open_helper.sh, xdg_open.sh (URL dispatcher)
└── tests/                      # Unit tests
    ├── test_extractor.py       # Extraction and format detection tests
    ├── test_elf_validator.py   # ELF header & AArch64 validation tests
    ├── test_manifest.py        # Manifest build & corruption detection tests
    ├── test_bundle.py          # Bundle layout, etc configs & assemble.py tests
    ├── test_shims.py           # xdg-open URL parsing & delegate tests
    └── run_all.sh              # Syntax validation + test suite runner
```

---

## 3. Component APIs & CLI

### 3.1. Unpack (`unpack/`)
- `extractor.extract_core(src_path: str, dest_path: str, binary_name: str = "language_server") -> bool`
  - CLI: `python3 extractor.py <source_path> <dest_binary_path> [binary_name]`
- `archive_detector.detect_type(file_path: str) -> str` — returns `"tar_gz"`, `"elf"`, `"unknown"`, or `"not_found"`.
- `permissions.make_executable(file_path: str) -> None` — sets `r-xr-xr-x` mode flags.

### 3.2. Validate (`validate/`)
- `elf_header.validate_elf_header(file_path: str) -> bool` — checks `\x7fELF`, 64-bit, Little-Endian.
- `elf_arch.validate_aarch64(file_path: str) -> bool` — checks `e_machine == 183`.
- `elf_compat.validate_executable_type(file_path: str) -> str` — returns `"ET_DYN"` or `"ET_EXEC"`.
- `checksum_checker.verify_checksum(file_path: str, expected_hash: str) -> bool` — streaming 1 MB SHA-256 verification.

### 3.3. Manifest (`manifest/`)
- `manifest_builder.build_manifest(runtime_dir: str, out_file: str = None) -> dict` — recursively hashes runtime assets into `manifest.json` (`schema: 1`).
- `manifest_verifier.verify_manifest(manifest_path: str, target_dir: str) -> bool` — verifies every file and SHA-256 digest against `manifest.json`.

### 3.4. Bundle Assembler (`bundle/`)
- `assemble.assemble_runtime(core_bin_path: str, module_root: str, out_dir: str, dry_run: bool = False) -> dict`
  1. Initializes directories via `layout.py`.
  2. Packs `bin/liblanguage_server.so`, `bin/libloader.so`, `bin/ld-linux-aarch64.so.1`, and browser/system shims (`pack_binaries.py`).
  3. Copies glibc libraries into `lib/` and network configs (`hosts`, `resolv.conf`, `nsswitch.conf`) into `etc/` (`pack_libraries.py`).
  4. Copies SSL certs (`pack_certs.py`) and seed files (`pack_seed.py`), then writes `manifest.json`.
  - CLI: `python3 modules/core/bundle/assemble.py --core-bin /path/to/language_server --out-dir /path/to/runtime [--dry-run]`

---

## 4. Assembled Runtime Layout (`assets/runtime`)

| Directory | Contents | Purpose |
| :--- | :--- | :--- |
| `bin/` | `liblanguage_server.so`, `libloader.so`, `ld-linux-aarch64.so.1`, `xdg-open`, `chrome`, `rish`... | Core binary, glibc dynamic loader, and shell shims |
| `lib/` | `libc.so.6`, `libm.so.6`, `libdl.so.2`, `libpthread.so.0`, `liblse_compat.so`, `ld-linux-aarch64.so.1` | AArch64 glibc runtime libraries & LSE atomic emulator |
| `etc/` | `hosts`, `resolv.conf`, `nsswitch.conf` | DNS & resolver configs for glibc networking |
| `certs/` | `ca-certificates.crt` | Root SSL/TLS store for Go TLS |
| `seed/` | `AGENTS.md`, `antigravity_state.pbtxt`, `jetski_state.pbtxt` | Onboarding presets & global agent rules |
| `/` | `manifest.json` | Cryptographic integrity manifest |

---

## 5. Testing & Verification

```bash
bash modules/core/tests/run_all.sh
```

Covers Python `compileall`, shell `bash -n`, `test_extractor.py`, `test_elf_validator.py`, `test_manifest.py`, `test_bundle.py`, and `test_shims.py`.

```bash
python3 modules/core/validate/verify_pipeline.py
```

Runs the 5-step sandboxed end-to-end verification (checksum -> unpack -> ELF/ABI check -> test runtime assembly -> manifest verification).
