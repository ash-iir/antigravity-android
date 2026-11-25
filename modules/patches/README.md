# Antigravity Core Binary Patcher (`modules/patches`)

Precision AArch64 binary patcher for the Antigravity core (`language_server`) targeting **Android ARM64**, bypassing platform restrictions (Android seccomp, Bionic libc differences, and missing hardware LSE atomics on ARMv8.0-A) and integrating runtime helpers.

---

## 1. Architecture

```text
modules/patches/
├── pipeline.py              # CLI orchestrator and Python pipeline API
├── spec/
│   └── patch_manifest.json  # Expected patches and verification policies
├── engine/                  # Low-level binary analysis and patching engines
│   ├── elf_scanner.py       # ELF64/AArch64 parser and .text locator
│   ├── pclntab_scanner.py   # Go pclntab function table parser & MOVZ encoder
│   ├── byte_replacer.py     # In-place pattern matching and replacement
│   ├── reporter.py          # JSON patch report generator
│   └── patch_context.py     # Execution context state model
├── groups/                  # Functional patch groups
│   ├── gates/               # CLI gate and Enterprise Auth bypasses
│   ├── syscalls/            # Seccomp-blocked faccessat2/fchmodat2 replacements
│   ├── sigill/              # SIGILL handler protection for liblse_compat
│   ├── resolv/              # Relative /etc/resolv.conf redirect + fallback DNS
│   └── failfast/            # ARMv8.0-A CPU check and preinit atomic suppression
└── tests/                   # Unit and E2E tests
    ├── test_byte_replacer.py
    ├── test_elf_scanner.py
    ├── test_pclntab_scanner.py
    ├── test_full_pipeline.py
    └── run_all.sh
```

---

## 2. Patch Groups

| Group | Module | Target & Signature | Action |
| :--- | :--- | :--- | :--- |
| **gates** | `cli_gate` | `ldrb w1, [x0,#8]` in CLI gate check | Patched to `mov w1, #1` (`21 00 80 52`) |
| **gates** | `enterprise_auth` | `EnterpriseAuthValidator.Validate` | Forces valid flag return |
| **syscalls** | `faccessat` | `syscall.faccessat2` (sys_439: `0xd28036e0`) | Retargeted to `faccessat` (sys_48: `0xd2800600`) |
| **syscalls** | `fchmodat` | `syscall.fchmodat2` (sys_452: `0xd2803880`) | Retargeted to `fchmodat` (sys_53: `0xd28006a0`) |
| **syscalls** | `pidfd` | `internal/syscall/unix.PidFDOpen` (sys_434: `0xd2803640`) | Returns `(-1, syscall.ENOSYS)` without trapping seccomp `SIGSYS` |
| **sigill** | `sigill_handler` | Go runtime `rt_sigaction` call | Skips overwriting `SIGILL` (4) to preserve `liblse_compat.so` handler |
| **resolv** | `resolv_redirect` | `/etc/resolv.conf` (16 bytes) & `defaultNS` fallback | Rewrites `/etc/resolv.conf` to `etc//resolv.conf` and patches fallback DNS to `8.8.8.8:53` |
| **failfast** | `crash_suppressor` | ARMv8.0-A CPU check (`0x7701180`) & preinit atomic (`0x7765c60`) | Branches `b +0x64` over LSE/CRC/AES checks and replaces `ldaddal` with `ldr`/`str` for ARMv8.0-A CPUs |

---

## 3. Analyzer Engine Optimizations

1. **`elf_scanner.py`:** Validates `e_ident` directly at offset 0 (with embedded ELF scan support), falls back to executable `PT_LOAD` program headers on stripped binaries (`e_shnum == 0`), and enforces strict section/header bounds.
2. **`pclntab_scanner.py`:** Performs direct substring lookup in `funcnametab` and caches function resolution (`_cache`) across syscall checks.
3. **Zero-Copy Scanning:** Eliminates redundant `bytes(data)` copies using `bytearray`/`memoryview` and enforces fail-closed verification in `crash_suppressor.py`.

---

## 4. CLI & Python API

### CLI Runner
```bash
# Apply all patches:
python3 modules/patches/pipeline.py /path/to/language_server

# Save JSON patch report:
python3 modules/patches/pipeline.py /path/to/language_server --manifest report.json

# Verify applied patches (exit 0 / 1):
python3 modules/patches/pipeline.py /path/to/language_server --verify

# Dry-run without modifying disk:
python3 modules/patches/pipeline.py /path/to/language_server --dry-run

# Allow missing optional signatures:
python3 modules/patches/pipeline.py /path/to/language_server --allow-missing
```

### Python API
```python
import pipeline

ok = pipeline.run_pipeline(
    target_path="/path/to/language_server",
    verify=False,
    dry_run=False,
    allow_missing=False,
    manifest_path=None,
)
```

---

## 5. Testing

```bash
bash modules/patches/tests/run_all.sh
```

- `test_byte_replacer.py`: Byte pattern matching and in-place replacement checks.
- `test_elf_scanner.py`: ELF64 validation, stripped `PT_LOAD` fallback, and bounds checks.
- `test_pclntab_scanner.py`: AArch64 `MOVZ` encoding, `funcnametab` lookup, and cache tests.
- `test_full_pipeline.py`: End-to-end test against the pristine `language_server` binary (dry-run, patch, SHA-256 verification, idempotence, and fail-closed validation).
