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

    # Behavioral test: __agHandleBack() steps back inside topmost overlay,
    # clicks deepest visible non-inert Back button, and returns false at root.
    node - "$TMP/web/generated/patches/back_navigation.js" <<'EOF'
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const code = fs.readFileSync(process.argv[2], 'utf8');

function makeNode(opts) {
  const attrs = Object.assign({}, opts.attrs || {});
  const node = {
    tagName: (opts.tagName || 'DIV').toUpperCase(),
    className: opts.className || '',
    disabled: Boolean(opts.disabled),
    style: Object.assign({ display: 'block', visibility: 'visible', zIndex: 'auto' }, opts.style || {}),
    rect: opts.rect || { width: 100, height: 40 },
    parent: null,
    children: [],
    clicks: 0,
    getAttribute(name) {
      return Object.prototype.hasOwnProperty.call(attrs, name) ? attrs[name] : null;
    },
    hasAttribute(name) {
      return Object.prototype.hasOwnProperty.call(attrs, name);
    },
    getBoundingClientRect() {
      return this.rect;
    },
    click() {
      this.clicks += 1;
    },
    matches(sel) {
      const parts = sel.split(',').map((s) => s.trim()).filter(Boolean);
      return parts.some((p) => matchSimple(this, p));
    },
    closest(sel) {
      let cur = this;
      while (cur) {
        if (cur.matches(sel)) return cur;
        cur = cur.parent;
      }
      return null;
    },
    querySelectorAll(sel) {
      const out = [];
      function walk(n) {
        for (const ch of n.children) {
          if (ch.matches(sel)) out.push(ch);
          walk(ch);
        }
      }
      walk(this);
      return out;
    },
    append(...kids) {
      for (const k of kids) {
        k.parent = this;
        this.children.push(k);
      }
      return this;
    }
  };
  return node;
}

function matchSimple(el, sel) {
  if (sel === '[inert]') return el.hasAttribute('inert');
  if (sel === '[aria-hidden="true"]') return el.getAttribute('aria-hidden') === 'true';
  if (sel === '[data-bottom-sheet-popup]:not([data-closed])') {
    return el.hasAttribute('data-bottom-sheet-popup') && !el.hasAttribute('data-closed');
  }
  if (sel === '.settings-modal-container:not([aria-hidden="true"])') {
    return el.className.split(/\s+/).includes('settings-modal-container') &&
      el.getAttribute('aria-hidden') !== 'true';
  }
  if (sel === '[role="dialog"]') {
    return el.getAttribute('role') === 'dialog';
  }
  if (sel === 'button[aria-label="Back"]:not([disabled])') {
    return el.tagName === 'BUTTON' &&
      el.getAttribute('aria-label') === 'Back' &&
      !el.disabled && !el.hasAttribute('disabled');
  }
  return false;
}

const docRoot = makeNode({ tagName: 'BODY' });
const sandbox = {
  console,
  document: {
    querySelectorAll: (sel) => docRoot.querySelectorAll(sel)
  },
  getComputedStyle: (el) => el.style
};
sandbox.window = sandbox;
sandbox.self = sandbox;
sandbox.globalThis = sandbox;

vm.runInNewContext(code, sandbox);
assert.strictEqual(typeof sandbox.__agHandleBack, 'function', '__agHandleBack must be exposed');

// 1. No overlay open -> false.
assert.strictEqual(sandbox.__agHandleBack(), false, 'no overlay should return false');

// 2. Settings root (depth 0, no Back button) -> false.
const settingsModal = makeNode({
  className: 'settings-modal-container',
  style: { zIndex: '50' }
});
docRoot.append(settingsModal);
assert.strictEqual(sandbox.__agHandleBack(), false, 'settings root without Back button should return false');

// 3. Nested Back buttons + inert panel + hidden button:
//    - btnOuter (visible, active)
//    - btnNested (visible, active, deepest in DOM -> must be clicked)
//    - btnInert (inside [inert] panel later in DOM -> must be skipped)
//    - btnHidden (display:none later in DOM -> must be skipped)
const outerPanel = makeNode({});
const btnOuter = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Back' } });
outerPanel.append(btnOuter);

const nestedPanel = makeNode({});
const btnNested = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Back' } });
nestedPanel.append(btnNested);

const inertPanel = makeNode({ attrs: { inert: '' } });
const btnInert = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Back' } });
inertPanel.append(btnInert);

const hiddenPanel = makeNode({});
const btnHidden = makeNode({
  tagName: 'BUTTON',
  attrs: { 'aria-label': 'Back' },
  style: { display: 'none' }
});
hiddenPanel.append(btnHidden);

settingsModal.append(outerPanel, nestedPanel, inertPanel, hiddenPanel);

assert.strictEqual(sandbox.__agHandleBack(), true, 'nested visible Back button should be handled');
assert.strictEqual(btnNested.clicks, 1, 'deepest active Back button should be clicked');
assert.strictEqual(btnOuter.clicks, 0, 'outer Back button should not be clicked first');
assert.strictEqual(btnInert.clicks, 0, 'inert panel Back button must be skipped');
assert.strictEqual(btnHidden.clicks, 0, 'hidden Back button must be skipped');

// 4. Higher z-index bottom sheet on top takes precedence over settings modal.
const topSheet = makeNode({
  attrs: { 'data-bottom-sheet-popup': '' },
  style: { zIndex: '100' }
});
const sheetBack = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Back' } });
topSheet.append(sheetBack);
docRoot.append(topSheet);

assert.strictEqual(sandbox.__agHandleBack(), true, 'topmost overlay Back button should be handled');
assert.strictEqual(sheetBack.clicks, 1, 'higher z-index overlay Back button should be clicked');
assert.strictEqual(btnNested.clicks, 1, 'lower overlay Back button should not be clicked');

// 5. Only inert Back button in topmost overlay -> returns false.
const inertOnlyDialog = makeNode({
  attrs: { role: 'dialog' },
  style: { zIndex: '200' }
});
const inertWrap = makeNode({ attrs: { inert: '' } });
const btnOnlyInert = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Back' } });
inertWrap.append(btnOnlyInert);
inertOnlyDialog.append(inertWrap);
docRoot.append(inertOnlyDialog);

assert.strictEqual(sandbox.__agHandleBack(), false, 'overlay with only inert Back button should return false');
assert.strictEqual(btnOnlyInert.clicks, 0, 'inert Back button must not be clicked');
EOF
fi

echo "web build selftest: OK"
