# Antigravity v2 - Environment, Diagnostics & System Gateway Subsystem (`modules/env`)

> **The `env` module** manages dynamic Android/Linux path resolution, host/port preflight diagnostics, structured telemetry logging, crash snapshots, command execution auditing, and IPC with the Android System Gateway (`CoreServerService`).

---

## 1. Architecture Overview

```text
 +-----------------------------------------------------------------------------------+
 |                             SPECIFICATION LAYER                                   |
 |          [spec/paths.json]                     [spec/env_vars.json]               |
 |     (Android path templates)             (Static vars, PATH, LD_LIBRARY_PATH)     |
 +--------------------------+---------------------------------+----------------------+
                            |                                 |
                            v                                 v
 +-----------------------------------------------------------------------------------+
 |                              CONTEXT & RESOLVER                                   |
 |         [path_resolver.py]                          [env_builder.py]              |
 |   Dynamic resolution of package_id,      Builds process env: HOME, PREFIX, PATH,  |
 |   app_root, runtime, prefix, cache       TMPDIR, GODEBUG, GIT_CONFIG_NOSYSTEM     |
 +--------------------------+---------------------------------+----------------------+
                            |                                 |
       +--------------------+----------------+----------------+----------------+
       |                                     |                                 |
       v                                     v                                 v
+--------------------------+  +--------------------------+  +--------------------------+
|  DIAGNOSTICS & PREFLIGHT |  |   LOGGING & CRASH DUMP   |  |     COMMAND RUNNER       |
|      [preflight.py]      |  |         [log.py]         |  |       [runner.py]        |
|  - Arch check (ARM64)    |  |  - ANSI Logger / JSONL   |  |  - Executes $ cmd        |
|  - Free space (>250 MB)  |  |   [flight_recorder.py]   |  |  - Tracks duration_ms    |
|  - Write access (W_OK)   |  |  - Disk & host snapshot  |  |  - Writes commands.jsonl |
|    [port_checker.py]     |  +--------------------------+  |  - Fallback & list       |
|  - Loopback TCP check    |                 |              +--------------------------+
+--------------------------+                 |                                 |
                                             v                                 v
 +-----------------------------------------------------------------------------------+
 |                             IPC & SYSTEM GATEWAY                                  |
 |           [bridge/client.py]                           [bridge/commands.py]       |
 |    Auto-discovers gateway.port & token         High-level typed helpers:          |
 |    Transport: POST http://127.0.0.1:port/gateway  notify, open, wakelock, info    |
 +-----------------------------------------------------------------------------------+
                                             |
                                             v
 +-----------------------------------------------------------------------------------+
 |                                CLI UTILITIES                                      |
 |          [cli/agy_system.py]                           [cli/agy_logs.py]          |
 |    Controls Android notifications,             Inspects core.log, crash reports,  |
 |    wakelocks, Intent URLs, clipboard           and commands.jsonl audit history   |
 +-----------------------------------------------------------------------------------+
```

---

## 2. Directory Layout

```text
modules/env/
├── spec/
│   ├── paths.json               # App directory structure template spec
│   └── env_vars.json            # Default environment variable & PATH spec
├── context/
│   ├── path_resolver.py         # Dynamic path resolver & package_id detector
│   └── env_builder.py           # Deterministic child process env builder
├── diagnostics/
│   ├── preflight.py             # ARM64 arch, write permissions, and disk preflight
│   └── port_checker.py          # Loopback TCP port availability checker
├── logging/
│   ├── log.py                   # Colored ANSI + atomic JSONL logger
│   └── flight_recorder.py       # Crash snapshot JSON generator
├── bridge/
│   ├── client.py                # Authenticated Android Gateway IPC client w/ failover
│   └── commands.py              # Typed facades for Android OS gateway calls
├── cmd/
│   └── runner.py                # Audited shell command execution engine
├── cli/
│   ├── agy_system.py            # CLI controller for Gateway (notify, open, wakelock, info)
│   └── agy_logs.py              # CLI inspector for core.log, commands, and crashes
└── tests/
    ├── run_all.sh               # Syntax checks + full module test runner
    ├── test_context.py          # Path resolver and env builder tests
    ├── test_diagnostics.py      # Preflight and port checker tests
    ├── test_logging.py          # Structured logger and flight recorder tests
    ├── test_bridge.py           # Gateway client auth, failover, and command facade tests
    └── test_cli.py              # CLI integration tests for agy_system and agy_logs
```

---

## 3. Environment & Path Spec (`spec/`)

### 3.1. Filesystem Spec (`spec/paths.json`)
Uses `{package_name}` / `{app_id}` placeholders resolved dynamically at runtime:
```json
{
  "app_root": "/data/user/0/{package_name}/files",
  "prefix": "/data/user/0/{package_name}/files/usr",
  "runtime": "/data/user/0/{package_name}/files/runtime",
  "cache": "/data/user/0/{package_name}/cache",
  "gemini_home": "/data/user/0/{package_name}/files/.gemini",
  "shared_storage": "/storage/emulated/0",
  "default_projects": "/storage/emulated/0/Documents"
}
```

### 3.2. Environment Vars Spec (`spec/env_vars.json`)
Defines baseline environment variables, `PATH` order, and shared library directories (`{runtime}/lib`, `{prefix}/lib`).

---

## 4. Context & Dynamic Path Resolver (`context/`)

### 4.1. `path_resolver.py`
Eliminates hardcoded package IDs (`com.agy` vs `com.agy.dev` vs custom builds):
- `get_application_id() -> str`: Priority order `AGY_APPLICATION_ID` -> `APPLICATION_ID` -> `DEV_MODE == "1"` (`com.agy.dev`) -> `com.agy`.
- `load_paths(custom_spec=None, app_id=None) -> Dict[str, str]`: Interpolates `paths.json` into normalized absolute paths.
- `resolve_subpath(key: str, rel_path: str, ...)`: Strips leading `/` (`rel_path.lstrip("/")`) before joining so callers never escape the base directory.

### 4.2. `env_builder.py`
- `build_process_env(...) -> Dict[str, str]`: Sets `HOME`, `PREFIX`, `TMPDIR`, `GIT_CONFIG_NOSYSTEM="1"`, `AGY_APPLICATION_ID`, `PATH`, `LD_LIBRARY_PATH`, and `extra_env`.

---

## 5. System Diagnostics (`diagnostics/`)

- **`diagnostics/preflight.py` (`run_preflight`):** Verifies ARM64/aarch64 architecture, writable `app_root`, and >= 250 MB free storage (resolving `app_root` dynamically when omitted).
- **`diagnostics/port_checker.py` (`check_port_available`):** Checks `127.0.0.1` TCP loopback bind availability with a 0.5 s timeout.

---

## 6. Logging & Crash Snapshots (`logging/`)

- **`logging/log.py`:** ANSI color logger (`DEBUG`, `INFO`, `OK`, `WARN`, `ERROR`) honoring `NO_COLOR`/`TERM=dumb`, routing errors to `stderr` and optional atomic JSONL append logging.
- **`logging/flight_recorder.py`:** Captures platform metadata, PID, free disk stats, and contextual error state to JSON.

---

## 7. System Gateway IPC Bridge (`bridge/`)

- **`bridge/client.py`:**
  - `resolve_gateway_url()`: Checks `AGY_GATEWAY_URL`, scans `gateway.port`, and defaults to `45161` (dev) or `45160` (release).
  - `resolve_gateway_token()` / `gateway_headers()`: Reads `AGY_GATEWAY_TOKEN` or `logs/gateway.token` (`0600`) and automatically attaches `X-AGY-Token` and `Authorization: Bearer <token>` on requests across `client.py` and `cli/agy_logs.py`.
  - `send_command(command, payload, ...)`: Sends authenticated POST requests to `/gateway` with automatic port failover (`45160 <-> 45161`).
- **`bridge/commands.py`:** Typed helpers `notify`, `cancel_notify`, `open_url`, `wakelock`, `clipboard_set`, `clipboard_get`, and `system_info`.

---

## 8. Audited Command Runner (`cmd/runner.py`)

```bash
python3 modules/env/cmd/runner.py run "echo 'Hello Antigravity'"
python3 modules/env/cmd/runner.py pkg list
python3 modules/env/cmd/runner.py list 20
```

Records exit codes and `duration_ms` into `$HOME/logs/commands.jsonl`.

---

## 9. CLI Tools (`cli/`)

### 9.1. System Controller (`cli/agy_system.py`)
```bash
python3 modules/env/cli/agy_system.py notify -t "Build Finished" -m "APK built cleanly" --tag "build"
python3 modules/env/cli/agy_system.py notify -t "Downloading" --progress 75 --ongoing
python3 modules/env/cli/agy_system.py notify cancel --tag "build"
python3 modules/env/cli/agy_system.py open "https://github.com"
python3 modules/env/cli/agy_system.py wakelock on --tag "long_job"
python3 modules/env/cli/agy_system.py wakelock off --tag "long_job"
python3 modules/env/cli/agy_system.py info --json
echo "secret token" | python3 modules/env/cli/agy_system.py clipboard set
python3 modules/env/cli/agy_system.py clipboard get
```

### 9.2. Telemetry & Log Inspector (`cli/agy_logs.py`)
```bash
python3 modules/env/cli/agy_logs.py -n 50
python3 modules/env/cli/agy_logs.py --channel core --level ERROR
python3 modules/env/cli/agy_logs.py --commands -n 25
python3 modules/env/cli/agy_logs.py --crash --json
```

---

## 10. Unit Testing (`tests/`)

```bash
bash modules/env/tests/run_all.sh
```

- `test_context.py` (9 tests): Path resolution, leading-slash `resolve_subpath`, `PREFIX`/`GIT_CONFIG_NOSYSTEM`, dynamic `AGY_APPLICATION_ID`, `DEV_MODE`.
- `test_diagnostics.py` (3 tests): Preflight checks and loopback TCP port binding.
- `test_logging.py` (2 tests): JSONL logging and crash snapshot generation.
- `test_bridge.py` (6 tests): Offline failover, bearer token headers (`test_06_gateway_bearer_token_headers`), wake lock, `get_type_hints()` introspection, and typed command facades.
- `test_cli.py` (6 tests): CLI help, JSON system info, crash inspection, clipboard JSON output, and command history.

---

## 11. Audit Fixes Resolved

1. **Hardcoded `app_root` in `preflight.py`:** Defaults to `None` and resolves dynamically (`path_resolver` -> `$HOME`).
2. **Missing `Optional` import & methods in `bridge/commands.py`:** Fixed `typing` imports and added `clipboard_get()`, `cancel_notify()`, and `system_info()`.
3. **Hardcoded log dir in `cmd/runner.py` & `cli/agy_logs.py`:** Unified via `_resolve_log_dir()` / `$HOME/logs`.
4. **Duplicated gateway discovery logic:** Consolidated into `bridge/client.py`.
5. **Missing `PREFIX` & `GIT_CONFIG_NOSYSTEM` in `env_builder.py`:** Explicitly populated.
6. **Path reset bug in `resolve_subpath`:** Strips leading `/` cleanly.
7. **Ignored `--json` & silent errors in `cli/agy_system.py`:** Prints structured JSON and reports failures on `stderr` with non-zero exit codes.
