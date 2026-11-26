#!/usr/bin/env bash
# Build web assets on a disposable copy and prove upstream main.js stays intact.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="${TMPDIR:-${HOME:-.}/.tmp}"
mkdir -p "$TMP_DIR"
TMP="$(mktemp -d "$TMP_DIR/agy-web-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
cp -a "$ROOT/web" "$TMP/web"
before="$(sha256sum "$TMP/web/main.js" | cut -d' ' -f1)"
bash "$TMP/web/build.sh"
after="$(sha256sum "$TMP/web/main.js" | cut -d' ' -f1)"
[ "$before" = "$after" ] || { echo "web/build.sh modified source main.js" >&2; exit 1; }
[ -s "$TMP/web/generated/main.js" ] || { echo "generated/main.js missing" >&2; exit 1; }
grep -Fq 'src="./generated/main.js"' "$TMP/web/index.html"

# Verify no double IIFE wrappers in generated bundles (excluding vendor main.js)
for f in "$TMP"/web/generated/boot/*.js "$TMP"/web/generated/patches/*.js; do
    [ -f "$f" ] || continue
    if awk '
        BEGIN { iife_opens = 0; in_block = 0 }
        {
            line = $0
            if (in_block) {
                if (line ~ /\*\//) { sub(/^.*\*\//, "", line); in_block = 0 }
                else next
            }
            if (line ~ /\/\*/) { sub(/\/\*.*$/, "", line); in_block = 1 }
            sub(/^[ \t]+/, "", line)
            if (line ~ /^\(function/) iife_opens++
        }
        END { if (iife_opens > 1) exit 0; exit 1 }
    ' "$f"; then
        echo "Double IIFE wrapper detected in $f" >&2
        exit 1
    fi
done

# Behavioral test: agy:host-resume force-resets stalled streaming RPCs without
# aborting caller signal or touching unary/audio RPCs, and defers while hidden.
if command -v node >/dev/null 2>&1; then
    node - "$TMP/web/generated/bridge.core.js" <<'EOF'
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const code = fs.readFileSync(process.argv[2], 'utf8');

function makeEventTarget() {
  const listeners = new Map();
  return {
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type).add(fn);
    },
    removeEventListener(type, fn) {
      if (listeners.has(type)) listeners.get(type).delete(fn);
    },
    dispatchEvent(ev) {
      const set = listeners.get(ev.type);
      if (!set) return true;
      for (const fn of [...set]) fn(ev);
      return true;
    }
  };
}

async function run() {
  const winTarget = makeEventTarget();
  const docTarget = makeEventTarget();
  const mockDoc = {
    visibilityState: 'visible',
    addEventListener: docTarget.addEventListener,
    removeEventListener: docTarget.removeEventListener,
    dispatchEvent: docTarget.dispatchEvent
  };

  let fakeNow = 100000;
  const RealDate = Date;
  function FakeDate(...args) {
    return args.length ? new RealDate(...args) : new RealDate(fakeNow);
  }
  FakeDate.now = () => fakeNow;
  FakeDate.UTC = RealDate.UTC;
  FakeDate.parse = RealDate.parse;
  FakeDate.prototype = RealDate.prototype;

  const fetchCalls = [];
  function mockOrigFetch(url, opts) {
    const call = { url: String(url), opts: opts || {}, aborted: false };
    fetchCalls.push(call);
    if (opts && opts.signal) {
      opts.signal.addEventListener('abort', () => { call.aborted = true; });
    }
    if (call.url.includes('SendUserCascadeMessage')) {
      return Promise.resolve(new Response(JSON.stringify({ ok: true }), { status: 200 }));
    }
    // Streaming response whose reader stays pending indefinitely until cancelled/errored.
    const body = new ReadableStream({
      start() {},
      pull() {
        return new Promise(() => {});
      }
    });
    return Promise.resolve(new Response(body, { status: 200 }));
  }

  const sandbox = {
    console,
    setTimeout,
    clearTimeout,
    Date: FakeDate,
    URL,
    Headers,
    Response,
    Request,
    ReadableStream,
    AbortController,
    DOMException,
    Event,
    CustomEvent,
    fetch: mockOrigFetch,
    document: mockDoc,
    navigator: {},
    location: { origin: 'https://127.0.0.1:45157', href: 'https://127.0.0.1:45157/', search: '' },
    __AG_CONFIG__: { coreBase: 'https://127.0.0.1:45157', csrfToken: 'test-csrf' },
    addEventListener: winTarget.addEventListener,
    removeEventListener: winTarget.removeEventListener,
    dispatchEvent: winTarget.dispatchEvent
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.globalThis = sandbox;

  vm.runInNewContext(code, sandbox);

  // 1. Open a stalled StreamAgentStateUpdates stream, a unary RPC, and an audio stream.
  const callerCtrl = new AbortController();
  const streamResp = await sandbox.fetch(
    'https://127.0.0.1:45157/exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates',
    { method: 'POST', signal: callerCtrl.signal, body: '{}' }
  );
  const streamReader = streamResp.body.getReader();
  const pendingRead = streamReader.read();

  const audioCtrl = new AbortController();
  const audioResp = await sandbox.fetch(
    'https://127.0.0.1:45157/exa.language_server_pb.LanguageServerService/StreamAudioTranscription',
    { method: 'POST', signal: audioCtrl.signal, body: '{}' }
  );
  assert.strictEqual(audioResp.status, 200);

  const unaryCtrl = new AbortController();
  const unaryResp = await sandbox.fetch(
    'https://127.0.0.1:45157/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage',
    { method: 'POST', signal: unaryCtrl.signal, body: '{}' }
  );
  assert.strictEqual(unaryResp.status, 200);

  // Advance clock past grace window (5s backgrounded).
  fakeNow += 5000;

  // 2. Dispatch agy:host-resume while document is still 'hidden': must defer, not burn cooldown.
  mockDoc.visibilityState = 'hidden';
  let recoverEvents = [];
  sandbox.addEventListener('agy:stream-recover', (ev) => recoverEvents.push(ev.detail));
  sandbox.dispatchEvent(new CustomEvent('agy:host-resume', { detail: { backgroundMs: 5000, wasStopped: true } }));
  assert.strictEqual(recoverEvents.length, 0, 'should defer recovery while document is hidden');
  assert.strictEqual(fetchCalls[0].aborted, false, 'should not abort stream while still hidden');

  // 3. Transition document to 'visible' -> deferred recovery fires and resets StreamAgentStateUpdates.
  mockDoc.visibilityState = 'visible';
  mockDoc.dispatchEvent(new Event('visibilitychange'));

  let readErr = null;
  try {
    await pendingRead;
  } catch (err) {
    readErr = err;
  }
  assert(readErr, 'stalled StreamAgentStateUpdates reader.read() must reject on resume');
  assert.strictEqual(readErr.name, 'AbortError', 'rejection should be AbortError');
  assert.strictEqual(callerCtrl.signal.aborted, false, 'caller signal must stay unaborted so kda reconnects');
  assert.strictEqual(fetchCalls[0].aborted, true, 'inner fetch controller for StreamAgentStateUpdates must abort');
  assert.strictEqual(fetchCalls[1].aborted, false, 'StreamAudioTranscription must not be aborted');
  assert.strictEqual(fetchCalls[2].aborted, false, 'unary SendUserCascadeMessage must not be aborted');
  assert.strictEqual(recoverEvents.length, 1, 'should fire one agy:stream-recover event');
  assert.strictEqual(recoverEvents[0].resetCount, 1, 'should report 1 reset stream');
}

run().catch((err) => {
  console.error(err);
  process.exit(1);
});
EOF
fi

echo "web build selftest: OK"
