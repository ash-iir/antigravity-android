# Antigravity v2 - I/O & Event Bus Subsystem (`modules/io`)

> **The I/O Module** is the central inter-process communication (IPC), stdout/stderr stream-interception, and asynchronous event routing subsystem for Antigravity v2 on Android/Linux.

---

## 1. Architecture Overview

`modules/io` implements a **Publish/Subscribe Event Bus** with streaming interceptors and heterogeneous transports (named FIFO pipes, UNIX domain sockets, and Android Intents).

```text
 +-------------------------------------------------------------------------------+
 |                               INPUT SOURCES                                   |
 |  [broadcast_receiver.sh]       [fifo_input.py]       [unix_socket_input.py]   |
 |   (Android am Intents)          (Named FIFO)           (AF_UNIX Stream)       |
 +-----------------------+---------------+-----------------------+---------------+
                         |               |                       |
                         +---------------+-----------------------+
                                         |
                                         v  JSON Event Envelopes
 +-------------------------------------------------------------------------------+
 |                             STREAM INTERCEPTORS                               |
 |   Core Server stdout/stderr ---> [stream_splitter.py]                         |
 |                                     |                                         |
 |             +-----------------------+-----------------------+                 |
 |             v                       v                       v                 |
 |     [filter_open_url.py]  [filter_task_events.py]  [filter_error_alerts.py]   |
 +-------------------------------------+-----------------------------------------+
                                       |
                                       v  envelope.create_event()
 +-------------------------------------------------------------------------------+
 |                               CORE EVENT BUS                                  |
 |                            [event_broker.py]                                  |
 |                                     |                                         |
 |                 +-------------------+-------------------+                     |
 |                 |                                       |                     |
 |                 v                                       v                     |
 |    In-Memory Handlers (Python)            [subscriber_router.py]              |
 |    (broker.register_handler)             (spec/default_routes.json)           |
 +---------------------------------------------------------+---------------------+
                                                           |
                                                           v  Process Dispatch
 +-------------------------------------------------------------------------------+
 |                               OUTPUT HANDLERS                                 |
 |  [notify_tray.sh]      [broadcast_emitter.sh]    [file_sink.py]  [socket_out] |
 |   Android Tray via         ACTION_OPEN_URL       events.jsonl    AF_UNIX IPC  |
 |   agy-notify / am          to Android Activity     Append Log     Forwarding  |
 +-------------------------------------------------------------------------------+
```

---

## 2. Event Specification (`Event Envelope`)

All events flowing through the bus conform to `spec/event_schema.json` (JSON Schema Draft-07).

### 2.1. Envelope Format
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AntigravityEventEnvelope",
  "type": "object",
  "required": ["event", "timestamp", "source", "data"],
  "properties": {
    "event": {
      "type": "string",
      "description": "Dotted event name, e.g. core.url.open, agent.task.started"
    },
    "timestamp": {
      "type": "integer",
      "description": "Epoch timestamp in milliseconds"
    },
    "source": {
      "type": "string",
      "description": "Event source, e.g. language_server:stdout, input:socket"
    },
    "data": {
      "type": "object",
      "description": "Arbitrary event JSON payload"
    }
  }
}
```

### 2.2. Serialization & Validation (`core_bus/envelope.py`)
- `create_event(event_type, source, data)`: creates an event dict stamped with `int(time.time() * 1000)`.
- `serialize_event(envelope)`: serializes the envelope to UTF-8 JSON.
- `deserialize_event(raw_text)`: parses JSON and validates required fields.
- `validate_event(envelope)`: checks presence and types of `event`, `timestamp`, `source`, and `data`.

---

## 3. Core Event Bus (`core_bus/`)

### 3.1. Subscriber Router (`subscriber_router.py`)
- Loads route mappings from `spec/default_routes.json`.
- Supports wildcard matching via `fnmatch.fnmatch` (`agent.task.*`, `core.*`, `*`).
- `get_destinations(event_type)` returns a sorted, deduplicated list of output handlers.

#### Default Routing Table (`spec/default_routes.json`)
| Event Pattern | Handlers (`outputs`) | Purpose |
| :--- | :--- | :--- |
| `core.url.open` | `broadcast_emitter.sh`, `file_sink.py` | Launches OAuth/auth URLs in the Android system browser |
| `agent.task.*` | `notify_tray.sh`, `file_sink.py` | Posts agent task lifecycle notifications to the status bar |
| `system.error` | `notify_tray.sh`, `file_sink.py` | Alerts on critical core crashes and panics |
| `external.*` | `file_sink.py` | External integration events |

### 3.2. Event Broker (`event_broker.py`)
- Central dispatcher `EventBroker` supporting two subscriber tiers:
  1. **In-Memory Handlers:** Python callbacks (`register_handler(pattern, callable)`) invoked synchronously in-process.
  2. **External Script Handlers:** Shell/Python scripts under `outputs/` spawned via `subprocess.Popen` with the JSON envelope on `stdin` and a configurable timeout (default 5 s).
- **Zombie & FD Leak Protection:** On `subprocess.TimeoutExpired`, runs `proc.kill()` + `proc.wait()` and closes `stdin`/`stderr`.
- **Android `noexec` Aware:** Invokes `.sh` handlers through `/system/bin/sh` or `bash` explicitly so scripts on `/storage/emulated/0` still run cleanly.

---

## 4. Input Channels (`inputs/`)

1. **FIFO Pipe Listener (`inputs/fifo_input.py`):** Listens on a named pipe (`mkfifo`), parses line-delimited JSON envelopes, and publishes to `EventBroker` (`python3 fifo_input.py <path_to_fifo>`). Automatically redirects FUSE paths (`/storage/emulated/0`, `/sdcard`) via `get_safe_pipe_path()` and offers `write_fifo_nonblocking()` with `O_NONBLOCK`.
2. **UNIX Domain Socket Listener (`inputs/unix_socket_input.py`):** Streaming `AF_UNIX` server (`python3 unix_socket_input.py <path_to_socket>`) collecting `\n`-delimited JSON events.
3. **Android Broadcast Receiver Bridge (`inputs/broadcast_receiver.sh`):** Translates Android broadcasts (`broadcast_receiver.sh <action> <payload_json> [destination_fifo]`) into `android.intent.<ACTION>` JSON events using non-blocking FIFO writes.
4. **Custom Source Template (`inputs/templates/template_input.sh`):** Starter script for sensors/hooks/webhooks with safe non-blocking FIFO writes.

---

## 5. Stream Interceptors (`interceptors/`)

Attached to core stdout/stderr by `modules/runner/start_core.sh`:

### 5.1. Stream Splitter (`stream_splitter.py`)
- Reads lines from `sys.stdin`, mirrors them to `sys.stdout` immediately, and pushes matched events onto an async background `queue.Queue` worker thread so external handlers never stall core log output.

### 5.2. Filter Modules (`interceptors/filters/`)
- `filter_open_url.py`: Matches `AGY_OPEN_URL=`, `ANTIGRAVITY_OPEN_URL:`, and OAuth URLs (`accounts.google.com`, `/auth/callback`), emitting `core.url.open`.
- `filter_task_events.py`: Tracks `[task:start]`, `[task:done]`, and `tool_call:` markers, emitting `agent.task.started`, `agent.task.completed`, and `agent.tool.executed`.
- `filter_error_alerts.py`: Detects `panic:`, `fatal error:`, `sigsegv`, and `sigill`, emitting `system.error`.

---

## 6. Output Handlers (`outputs/`)

1. **`outputs/notify_tray.sh`:** Reads JSON events from `stdin` and posts Android notifications via `agy-notify` or `am broadcast -a com.agy.ACTION_NOTIFY`.
2. **`outputs/broadcast_emitter.sh`:** Extracts `url` from `core.url.open` and broadcasts `com.agy.ACTION_OPEN_URL`.
3. **`outputs/file_sink.py`:** Appends JSONL events to `AGY_EVENT_LOG` (default `~/.gemini/events.jsonl`).
4. **`outputs/unix_socket_emitter.py`:** Forwards events to external UNIX socket listeners.

---

## 7. Unit Testing (`tests/`)

```bash
bash modules/io/tests/run_all.sh
```

| Test File | Scope | Covered Scenarios |
| :--- | :--- | :--- |
| `test_envelope.py` | `envelope.py` | Type checks, envelope creation, round-trip JSON, schema rejection |
| `test_event_broker.py` | `SubscriberRouter`, `EventBroker` | Wildcards (`*`, `core.*`), in-memory dispatch, timeout cleanup (`proc.kill()`/`wait()`/FD close) |
| `test_fifo_io.py` | `fifo_input.py`, `EventBroker` | FIFO creation, listener thread, FUSE path redirection, non-blocking writes without reader |
| `test_stream_splitter.py` | `stream_splitter.py`, filters | Pass-through output + async `core.url.open` / `system.error` dispatch via `queue.Queue` |
| `run_all.sh` | Entire module | Python `compileall`, `bash -n`, and all module unit tests |

---

## 8. Reliability Audit & Backlog

1. **Shell JSON Regex Compatibility [RESOLVED]:** Updated `notify_tray.sh`, `broadcast_emitter.sh`, and `template_output.sh` regexes (`"url"[[:space:]]*:[[:space:]]*"[^"]*`) to handle `json.dumps()` spacing in Toybox `/system/bin/sh` and Bash.
2. **Head-of-Line Blocking in `stream_splitter.py` [RESOLVED]:** Moved `broker.publish()` onto an async daemon `queue.Queue` worker so stdout/stderr draining never stalls the 64 KB kernel pipe.
3. **`subprocess.Popen` Timeout Zombies & FD Leaks [RESOLVED]:** Added `TimeoutExpired` handling in `event_broker.py` to call `proc.kill()`, `proc.wait()`, and close pipes.
4. **Blocking FIFO Writes [RESOLVED]:** Added non-blocking `os.O_NONBLOCK` / `timeout 1s` sends in `broadcast_receiver.sh`, `template_input.sh`, and `write_fifo_nonblocking()`.
5. **Android FUSE `mkfifo` EACCES [RESOLVED]:** Added `get_safe_pipe_path()` to redirect `/storage/`, `/sdcard/`, and `/mnt/` paths to writable internal runtime/$TMPDIR storage.
6. **Open Backlog:** `filter_open_url.py` URL matching duplicates `OpenUrlWatcher.java`; future work can consolidate routing over the event bus.
