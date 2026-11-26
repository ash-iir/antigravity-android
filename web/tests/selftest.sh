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

    # Behavioral test: __agHandleBack() handles ancestor z-index overlays, Back/Close/Escape,
    # in-layout controls, SPA window.history.back() (__TSR_index > 0), breadcrumbs, and Home exit.
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
    tabIndex: typeof opts.tabIndex === 'number' ? opts.tabIndex : -1,
    style: Object.assign({ display: 'block', visibility: 'visible', zIndex: 'auto' }, opts.style || {}),
    rect: opts.rect || { width: 100, height: 40 },
    parent: null,
    parentElement: null,
    children: [],
    clicks: 0,
    events: [],
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
    dispatchEvent(ev) {
      this.events.push(ev);
      return true;
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
    querySelector(sel) {
      const all = this.querySelectorAll(sel);
      return all.length > 0 ? all[0] : null;
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
        k.parentElement = this;
        this.children.push(k);
      }
      return this;
    },
    removeChild(kid) {
      const idx = this.children.indexOf(kid);
      if (idx >= 0) {
        this.children.splice(idx, 1);
        kid.parent = null;
        kid.parentElement = null;
      }
    }
  };
  return node;
}

function matchSimple(el, sel) {
  if (sel === '[inert]') return el.hasAttribute('inert');
  if (sel === '[aria-hidden="true"]') return el.getAttribute('aria-hidden') === 'true';
  if (sel === '[data-closed]') return el.hasAttribute('data-closed');
  if (sel === '[data-bottom-sheet-popup]:not([data-closed])') {
    return el.hasAttribute('data-bottom-sheet-popup') && !el.hasAttribute('data-closed');
  }
  if (sel === '.settings-modal-container:not([aria-hidden="true"])') {
    return el.className.split(/\s+/).includes('settings-modal-container') &&
      el.getAttribute('aria-hidden') !== 'true';
  }
  if (sel === '[role="dialog"]:not([data-closed])') {
    return el.getAttribute('role') === 'dialog' && !el.hasAttribute('data-closed');
  }
  if (sel === '[role="alertdialog"]:not([data-closed])') {
    return el.getAttribute('role') === 'alertdialog' && !el.hasAttribute('data-closed');
  }
  if (sel === '[role="menu"][data-open]') {
    return el.getAttribute('role') === 'menu' && el.hasAttribute('data-open');
  }
  if (sel === '[role="listbox"][data-open]') {
    return el.getAttribute('role') === 'listbox' && el.hasAttribute('data-open');
  }
  if (sel === 'button[aria-label="Back"]:not([disabled])') {
    return el.tagName === 'BUTTON' &&
      el.getAttribute('aria-label') === 'Back' &&
      !el.disabled && !el.hasAttribute('disabled');
  }
  if (sel === 'button[aria-label="Close"]:not([disabled])') {
    return el.tagName === 'BUTTON' &&
      el.getAttribute('aria-label') === 'Close' &&
      !el.disabled && !el.hasAttribute('disabled');
  }
  if (sel === 'button[aria-label="Close Logs Modal"]:not([disabled])') {
    return el.tagName === 'BUTTON' &&
      el.getAttribute('aria-label') === 'Close Logs Modal' &&
      !el.disabled && !el.hasAttribute('disabled');
  }
  if (sel === 'button[aria-label="Close Sidebar"]:not([disabled])') {
    return el.tagName === 'BUTTON' &&
      el.getAttribute('aria-label') === 'Close Sidebar' &&
      !el.disabled && !el.hasAttribute('disabled');
  }
  if (sel === 'button[data-testid="onboarding-back"][aria-label="Back"]:not([disabled])') {
    return el.tagName === 'BUTTON' &&
      el.getAttribute('data-testid') === 'onboarding-back' &&
      el.getAttribute('aria-label') === 'Back' &&
      !el.disabled && !el.hasAttribute('disabled');
  }
  if (sel === 'nav[aria-label="Breadcrumb"]') {
    return el.tagName === 'NAV' && el.getAttribute('aria-label') === 'Breadcrumb';
  }
  if (sel === '[data-testid="breadcrumb-segment"]') {
    return el.getAttribute('data-testid') === 'breadcrumb-segment';
  }
  if (sel === 'button[data-testid="mobile-back-to-home"]:not([disabled])') {
    return el.tagName === 'BUTTON' &&
      el.getAttribute('data-testid') === 'mobile-back-to-home' &&
      !el.disabled && !el.hasAttribute('disabled');
  }
  if (sel === '[data-testid^="settings-nav-item-"]') {
    const id = el.getAttribute('data-testid') || '';
    return id.startsWith('settings-nav-item-');
  }
  if (sel === '[data-testid="settings-disabled-notice"]') {
    return el.getAttribute('data-testid') === 'settings-disabled-notice';
  }
  return false;
}

function createEnv(init) {
  const docRoot = makeNode({ tagName: 'BODY' });
  let historyBacks = 0;
  const loc = Object.assign({ pathname: '/', search: '' }, (init && init.location) || {});
  const hist = {
    state: (init && init.historyState !== undefined) ? init.historyState : { __TSR_index: 0 },
    back() {
      historyBacks += 1;
    }
  };
  const sandbox = {
    console,
    URLSearchParams,
    location: loc,
    history: hist,
    document: {
      querySelector: (sel) => docRoot.querySelector(sel),
      querySelectorAll: (sel) => docRoot.querySelectorAll(sel),
      dispatchEvent: (ev) => docRoot.dispatchEvent(ev)
    },
    getComputedStyle: (el) => el.style
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(code, sandbox);
  return {
    sandbox,
    docRoot,
    location: loc,
    history: hist,
    getHistoryBacks: () => historyBacks
  };
}

// 1. Clean Home ('/') with no overlay -> 'exit' (even if __TSR_index > 0 after mobile-back-to-home).
{
  const env = createEnv({ location: { pathname: '/', search: '?hostTheme=dark' }, historyState: { __TSR_index: 3 } });
  assert.strictEqual(typeof env.sandbox.__agHandleBack, 'function', '__agHandleBack must be exposed');
  assert.strictEqual(env.sandbox.__agHandleBack(), 'exit', 'clean Home should signal exit');
  assert.strictEqual(env.getHistoryBacks(), 0, 'clean Home must not call history.back()');
}

// 2. Settings desktop root (depth 0, no Back button):
//    - without router history -> false;
//    - with ?settingsOpen=true & __TSR_index > 0 -> pops via history.back() and returns true.
{
  const env = createEnv({ location: { pathname: '/', search: '?settingsOpen=true' }, historyState: { __TSR_index: 0 } });
  const settingsModal = makeNode({ className: 'settings-modal-container', style: { zIndex: '50' } });
  const settingsClose = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Close' } });
  settingsModal.append(settingsClose);
  env.docRoot.append(settingsModal);

  assert.strictEqual(env.sandbox.__agHandleBack(), false, 'settings root at __TSR_index=0 should return false');
  assert.strictEqual(settingsClose.clicks, 0, 'settings root must not click replace:true Close');

  env.history.state = { __TSR_index: 1 };
  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'settings root at __TSR_index>0 should pop history');
  assert.strictEqual(env.getHistoryBacks(), 1, 'settings root should call window.history.back()');
  assert.strictEqual(settingsClose.clicks, 0, 'settings root must not click Close when popping history');
}

// 3. Nested Back buttons + inert panel + hidden button inside Settings:
//    clicks deepest active Back button, skips inert & display:none.
{
  const env = createEnv({ location: { pathname: '/', search: '?settingsOpen=true' }, historyState: { __TSR_index: 1 } });
  const sheetWrap = makeNode({ style: { zIndex: '4001' } });
  const settingsSheet = makeNode({
    attrs: { 'data-bottom-sheet-popup': '', 'data-sheet-depth': '0' }
  });
  const navMarker = makeNode({ attrs: { 'data-testid': 'settings-nav-item-General' } });
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
  settingsSheet.append(navMarker, outerPanel, nestedPanel, inertPanel, hiddenPanel);
  sheetWrap.append(settingsSheet);
  env.docRoot.append(sheetWrap);

  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'nested visible Back button should be handled');
  assert.strictEqual(btnNested.clicks, 1, 'deepest active Back button should be clicked');
  assert.strictEqual(btnOuter.clicks, 0, 'outer Back button should not be clicked first');
  assert.strictEqual(btnInert.clicks, 0, 'inert panel Back button must be skipped');
  assert.strictEqual(btnHidden.clicks, 0, 'hidden Back button must be skipped');

  // 4. Ancestor z-index stacking: <HI> dialog (wrapper zIndex: 5000, popup zIndex: auto)
  //    opened over Settings bottom sheet (wrapper zIndex: 4001, popup zIndex: auto)
  //    must win and click dialog's Close button without touching Settings Back or history.
  const modalWrap = makeNode({ style: { zIndex: '5000' } });
  const hiDialog = makeNode({ attrs: { role: 'dialog' } });
  const inertBackWrap = makeNode({ attrs: { inert: '' } });
  const inertDialogBack = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Back' } });
  inertBackWrap.append(inertDialogBack);
  const hiClose = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Close' } });
  hiDialog.append(inertBackWrap, hiClose);
  modalWrap.append(hiDialog);
  // Prepend before sheetWrap to prove ancestor z-index (5000 > 4001) beats DOM order!
  env.docRoot.children.unshift(modalWrap);
  modalWrap.parent = env.docRoot;
  modalWrap.parentElement = env.docRoot;

  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'higher ancestor z-index dialog should be handled');
  assert.strictEqual(hiClose.clicks, 1, 'dialog Close button should be clicked');
  assert.strictEqual(inertDialogBack.clicks, 0, 'inert dialog Back button must be skipped');
  assert.strictEqual(btnNested.clicks, 1, 'lower Settings Back button must not be clicked');
  assert.strictEqual(env.getHistoryBacks(), 0, 'closing dialog must not pop history');
}

// 5. Customizations in-page modals (J5 skill detail / ybd Add MCP) close via Close without popping route.
{
  const env = createEnv({
    location: { pathname: '/customizations/installed', search: '' },
    historyState: { __TSR_index: 2 }
  });
  const modalWrap = makeNode({ style: { zIndex: '5000' } });
  const skillModal = makeNode({ attrs: { role: 'dialog' } });
  const closeBtn = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Close' } });
  skillModal.append(closeBtn);
  modalWrap.append(skillModal);
  env.docRoot.append(modalWrap);

  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'Customizations modal should close on Back');
  assert.strictEqual(closeBtn.clicks, 1, 'Customizations modal Close button should be clicked');
  assert.strictEqual(env.getHistoryBacks(), 0, 'closing modal must not navigate back');
}

// 6. Non-Settings bottom sheet (e.g. Mobile Side Panel Sod, which ALSO has role="dialog"
//    from Base UI Lvb, plus an inner tab Close button) must dismiss via Escape, NOT click
//    the inner tab Close button or pop history (even when ?feedbackOpen=true is present).
{
  const env = createEnv({
    location: { pathname: '/c/conv-1', search: '?feedbackOpen=true' },
    historyState: { __TSR_index: 2 }
  });
  const wrap = makeNode({ style: { zIndex: '6001' } });
  const sidePanelSheet = makeNode({
    attrs: { 'data-bottom-sheet-popup': '', 'data-sheet-depth': '0', role: 'dialog' }
  });
  const innerTabClose = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Close' } });
  sidePanelSheet.append(innerTabClose);
  wrap.append(sidePanelSheet);
  env.docRoot.append(wrap);

  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'Mobile Side Panel sheet should be dismissed');
  assert.strictEqual(innerTabClose.clicks, 0, 'inner tab Close button must NOT be clicked');
  assert.strictEqual(sidePanelSheet.events.length, 1, 'Escape event must be dispatched on sheet');
  assert.strictEqual(sidePanelSheet.events[0].key, 'Escape', 'dispatched key must be Escape');
  assert.strictEqual(env.getHistoryBacks(), 0, 'dismissing bottom sheet must not pop Feedback/history');
}

// 7. Open [role="menu"][data-open] dismisses via Escape without popping route.
{
  const env = createEnv({
    location: { pathname: '/customizations', search: '' },
    historyState: { __TSR_index: 1 }
  });
  const menu = makeNode({ attrs: { role: 'menu', 'data-open': '' }, style: { zIndex: '50' } });
  env.docRoot.append(menu);

  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'open menu should dismiss on Back');
  assert.strictEqual(menu.events.length, 1, 'Escape event must be dispatched on menu');
  assert.strictEqual(menu.events[0].key, 'Escape');
  assert.strictEqual(env.getHistoryBacks(), 0);
}

// 8. Mobile overlay sidebar (<div role="dialog" aria-modal="true">) clicks Close Sidebar
//    even when an unrelated inner Close button precedes it; Onboarding Back works in-layout.
{
  const env = createEnv({
    location: { pathname: '/customizations', search: '' },
    historyState: { __TSR_index: 1 }
  });
  const sidebarDialog = makeNode({
    attrs: { role: 'dialog', 'aria-modal': 'true', 'aria-hidden': 'false' },
    style: { zIndex: '50' }
  });
  const innerClose = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Close' } });
  const closeSidebar = makeNode({ tagName: 'BUTTON', attrs: { 'aria-label': 'Close Sidebar' } });
  sidebarDialog.append(innerClose, closeSidebar);
  env.docRoot.append(sidebarDialog);

  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'Close Sidebar dialog should be handled');
  assert.strictEqual(closeSidebar.clicks, 1, 'Close Sidebar must be clicked');
  assert.strictEqual(innerClose.clicks, 0, 'unrelated inner Close button must NOT be clicked');
  assert.strictEqual(env.getHistoryBacks(), 0);

  env.docRoot.removeChild(sidebarDialog);
  const onboardingBack = makeNode({
    tagName: 'BUTTON',
    attrs: { 'data-testid': 'onboarding-back', 'aria-label': 'Back' }
  });
  env.docRoot.append(onboardingBack);
  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'onboarding-back should be handled');
  assert.strictEqual(onboardingBack.clicks, 1);
}

// 9. Multi-step Customizations route (__TSR_index === 2 on /customizations/plugins/github)
//    steps back 1 entry via window.history.back() instead of falling back to webView.goBack().
{
  const env = createEnv({
    location: { pathname: '/customizations/plugins/github', search: '' },
    historyState: { __TSR_index: 2 }
  });
  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'Customizations detail with __TSR_index>0 must step back');
  assert.strictEqual(env.getHistoryBacks(), 1, 'must call window.history.back() once');
}

// 10. Deep-link / __TSR_index === 0 on /customizations/plugins/github uses parent breadcrumb
//     segment (never clicking leaf segment).
{
  const env = createEnv({
    location: { pathname: '/customizations/plugins/github', search: '' },
    historyState: { __TSR_index: 0 }
  });
  const nav = makeNode({ tagName: 'NAV', attrs: { 'aria-label': 'Breadcrumb' } });
  const parentSeg = makeNode({
    tagName: 'SPAN',
    attrs: { 'data-testid': 'breadcrumb-segment', role: 'button' },
    tabIndex: 0
  });
  const leafSeg = makeNode({
    tagName: 'SPAN',
    attrs: { 'data-testid': 'breadcrumb-segment', role: 'button' },
    tabIndex: 0
  });
  nav.append(parentSeg, leafSeg);
  env.docRoot.append(nav);

  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'parent breadcrumb fallback should handle Back');
  assert.strictEqual(parentSeg.clicks, 1, 'parent breadcrumb segment should be clicked');
  assert.strictEqual(leafSeg.clicks, 0, 'leaf breadcrumb segment must never be clicked');
  assert.strictEqual(env.getHistoryBacks(), 0);
}

// 11. Deep-link / __TSR_index === 0 on /customizations (no breadcrumb) clicks mobile-back-to-home.
{
  const env = createEnv({
    location: { pathname: '/customizations', search: '' },
    historyState: { __TSR_index: 0 }
  });
  const homeBack = makeNode({
    tagName: 'BUTTON',
    attrs: { 'data-testid': 'mobile-back-to-home', 'aria-label': 'Back to home' }
  });
  env.docRoot.append(homeBack);

  assert.strictEqual(env.sandbox.__agHandleBack(), true, 'mobile-back-to-home fallback should handle Back');
  assert.strictEqual(homeBack.clicks, 1);
}
EOF

    # Behavioral test: web_optimizations.js injects valid, balanced Plugins mobile & Flexbox CSS rules.
    node - "$TMP/web/generated/patches/web_optimizations.js" <<'EOF'
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const code = fs.readFileSync(process.argv[2], 'utf8');
const appended = [];
const mockDoc = {
  readyState: 'complete',
  head: {
    appendChild(node) {
      appended.push(node);
    }
  },
  documentElement: {
    style: { setProperty() {} },
    appendChild(node) {
      appended.push(node);
    }
  },
  querySelector() {
    return null;
  },
  createElement(tag) {
    const attrs = {};
    return {
      tagName: tag.toUpperCase(),
      textContent: '',
      setAttribute(k, v) { attrs[k] = v; },
      getAttribute(k) { return attrs[k] || null; }
    };
  },
  addEventListener() {}
};
const sandbox = {
  console,
  document: mockDoc,
  navigator: { userAgent: 'Mozilla/5.0 Android' },
  innerHeight: 800,
  addEventListener() {},
  setTimeout,
  clearTimeout
};
sandbox.window = sandbox;
sandbox.self = sandbox;
sandbox.globalThis = sandbox;
vm.runInNewContext(code, sandbox);

const gpuStyle = appended.find((n) => n.getAttribute('data-agy') === 'gpu-acceleration');
assert(gpuStyle, 'gpu-acceleration style element must be injected');
const css = gpuStyle.textContent;
assert(css.length > 0, 'gpu-acceleration CSS must be non-empty');

// Check brace balance
let depth = 0;
for (let i = 0; i < css.length; i++) {
  if (css[i] === '{') depth += 1;
  else if (css[i] === '}') depth -= 1;
  assert(depth >= 0, 'unbalanced closing brace in gpu.js CSS');
}
assert.strictEqual(depth, 0, 'unbalanced opening brace in gpu.js CSS');

// Verify key Plugins / Accounts selectors exist
for (const sel of [
  'section[aria-label="Accounts"] > div > div > div.flex-wrap > div.flex-1',
  'section[aria-label="Accounts"] ul[aria-label="Signed-in accounts"] > li > div.flex-1',
  '[data-testid="plugin-detail"]',
  '[data-testid="bundle-detail"]',
  '[data-testid="mcp-server-row"]',
  '[data-testid="mcp-server-browser-sign-in"]'
]) {
  assert(css.includes(sel), 'gpu.js CSS missing expected selector: ' + sel);
}
EOF

    # Behavioral test: github_device_auth.js scopes strictly to GitHub (Local), handles
    # initial sign-in, multi-account Add account, slow_down backoff, errors, and Cancel.
    node - "$TMP/web/generated/patches/github_device_auth.js" <<'EOF'
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const code = fs.readFileSync(process.argv[2], 'utf8');

function makeNode(tag, opts) {
  const o = opts || {};
  const attrs = Object.assign({}, o.attrs || {});
  const listeners = new Map();
  let innerHtmlVal = '';
  const node = {
    tagName: tag.toUpperCase(),
    type: o.type || '',
    className: o.className || '',
    value: o.value || '',
    disabled: Boolean(o.disabled),
    textContent: o.textContent || '',
    parentElement: null,
    parent: null,
    children: [],
    ownerDocument: null,
    clicks: 0,
    setAttribute(k, v) { attrs[k] = String(v); },
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(attrs, k) ? attrs[k] : null; },
    hasAttribute(k) { return Object.prototype.hasOwnProperty.call(attrs, k); },
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    dispatchEvent(ev) {
      const list = listeners.get(ev.type) || [];
      for (const fn of [...list]) fn(ev);
      return true;
    },
    click() {
      this.clicks += 1;
      this.dispatchEvent({ type: 'click', target: this });
    },
    appendChild(child) {
      if (child.parentElement && child.parentElement.children) {
        const idx = child.parentElement.children.indexOf(child);
        if (idx >= 0) child.parentElement.children.splice(idx, 1);
      }
      child.parentElement = this;
      child.parent = this;
      child.ownerDocument = this.ownerDocument;
      this.children.push(child);
      return child;
    },
    get lastElementChild() {
      return this.children.length ? this.children[this.children.length - 1] : null;
    },
    matches(sel) {
      if (sel === 'section[aria-label="Accounts"]') {
        return this.tagName === 'SECTION' && this.getAttribute('aria-label') === 'Accounts';
      }
      if (sel === 'div') return this.tagName === 'DIV';
      if (sel === 'span') return this.tagName === 'SPAN';
      if (sel === 'button') return this.tagName === 'BUTTON';
      if (sel === 'ul[aria-label="Signed-in accounts"]') {
        return this.tagName === 'UL' && this.getAttribute('aria-label') === 'Signed-in accounts';
      }
      if (sel === 'a[href*="github.com/settings/tokens"]') {
        return this.tagName === 'A' && (this.getAttribute('href') || '').includes('github.com/settings/tokens');
      }
      if (sel === 'input[aria-label="Secret for github"]') {
        return this.tagName === 'INPUT' && this.getAttribute('aria-label') === 'Secret for github';
      }
      if (sel === 'input[aria-label="Secret for a new github account"]') {
        return this.tagName === 'INPUT' && this.getAttribute('aria-label') === 'Secret for a new github account';
      }
      if (sel === 'style[data-agy="github-device-auth"]') {
        return this.tagName === 'STYLE' && this.getAttribute('data-agy') === 'github-device-auth';
      }
      if (sel === '[data-agy-github-auth]') return this.hasAttribute('data-agy-github-auth');
      const m = /^\[data-agy-github-auth="([^"]+)"\]$/.exec(sel);
      if (m) return this.getAttribute('data-agy-github-auth') === m[1];
      return false;
    },
    closest(sel) {
      let cur = this;
      while (cur) {
        if (cur.matches(sel)) return cur;
        cur = cur.parentElement;
      }
      return null;
    },
    querySelector(sel) {
      const list = this.querySelectorAll(sel);
      return list.length ? list[0] : null;
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
    }
  };
  Object.defineProperty(node, 'innerHTML', {
    get() { return innerHtmlVal; },
    set(v) {
      innerHtmlVal = String(v);
      if (v === '') node.children.length = 0;
    }
  });
  return node;
}

function createHarness(opts) {
  const o = opts || {};
  const head = makeNode('head');
  const body = makeNode('body');
  const doc = {
    readyState: 'complete',
    visibilityState: 'visible',
    head,
    body,
    documentElement: body,
    createElement(tag) {
      const n = makeNode(tag);
      n.ownerDocument = doc;
      return n;
    },
    querySelector(sel) {
      return head.querySelector(sel) || body.querySelector(sel);
    },
    querySelectorAll(sel) {
      return [...head.querySelectorAll(sel), ...body.querySelectorAll(sel)];
    },
    addEventListener() {}
  };
  head.ownerDocument = doc;
  body.ownerDocument = doc;

  const bridgeCalls = { start: [], poll: [], open: [], copy: [], fetch: [] };
  const timers = [];
  let timerSeq = 0;
  let fakeNow = 1000000;

  const RealDate = Date;
  function FakeDate(...args) {
    return args.length ? new RealDate(...args) : new RealDate(fakeNow);
  }
  FakeDate.now = () => fakeNow;

  const androidBridge = {
    startGitHubDeviceAuth(tok, reqId, scope) {
      bridgeCalls.start.push({ tok, reqId, scope });
    },
    pollGitHubDeviceAuth(tok, reqId, deviceCode) {
      bridgeCalls.poll.push({ tok, reqId, deviceCode });
    },
    openGitHubDevicePage(tok, uri) {
      bridgeCalls.open.push({ tok, uri });
      return true;
    },
    copyGitHubDeviceCode(tok, codeStr) {
      bridgeCalls.copy.push({ tok, codeStr });
      return true;
    }
  };

  const sandbox = {
    console,
    Date: FakeDate,
    Event,
    location: { pathname: o.pathname || '/customizations/plugins/github' },
    document: doc,
    navigator: {},
    fetch(url, init) {
      bridgeCalls.fetch.push({ url, init });
      return Promise.resolve({ ok: true, status: 200 });
    },
    __AG_NATIVE_BRIDGE_TOKEN__: 'bridge-secret-tok',
    Android: androidBridge,
    setTimeout(fn, ms) {
      const id = ++timerSeq;
      timers.push({ id, fn, ms, cleared: false });
      return id;
    },
    clearTimeout(id) {
      for (const t of timers) {
        if (t.id === id) t.cleared = true;
      }
    },
    addEventListener() {},
    dispatchEvent() { return true; }
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(code, sandbox);

  return {
    sandbox,
    doc,
    body,
    bridgeCalls,
    timers,
    advanceTime(ms) { fakeNow += ms; },
    flushMicrotasks() { return new Promise((r) => setImmediate(r)); },
    runPendingTimers() {
      const ready = timers.filter((t) => !t.cleared);
      for (const t of ready) {
        t.cleared = true;
        t.fn();
      }
    }
  };
}

function buildProviderCard(doc, cfg) {
  const card = doc.createElement('div');
  card.className = 'rounded-md border border-border/60 p-3 space-y-3';
  const top = doc.createElement('div');
  top.className = 'flex flex-wrap items-center gap-3';
  const info = doc.createElement('div');
  info.className = 'min-w-0 flex-1';
  const hdr = doc.createElement('div');
  hdr.className = 'flex items-center gap-2';
  const nameSpan = doc.createElement('span');
  nameSpan.textContent = cfg.provider;
  const typeSpan = doc.createElement('span');
  typeSpan.textContent = cfg.typeLabel;
  hdr.appendChild(nameSpan);
  hdr.appendChild(typeSpan);
  info.appendChild(hdr);
  if (cfg.setupUrl) {
    const a = doc.createElement('a');
    a.setAttribute('href', cfg.setupUrl);
    info.appendChild(a);
  }
  top.appendChild(info);
  card.appendChild(top);
  return { card, top };
}

async function runTests() {
  // 1. Strict scoping: NEVER inject on Gmail (OAuth) or non-GitHub API key providers.
  {
    const h = createHarness({ pathname: '/customizations/plugins/gmail' });
    const sec = h.doc.createElement('section');
    sec.setAttribute('aria-label', 'Accounts');
    const list = h.doc.createElement('div');
    const gmail = buildProviderCard(h.doc, { provider: 'google', typeLabel: 'OAuth' });
    const stripe = buildProviderCard(h.doc, { provider: 'stripe', typeLabel: 'API key' });
    list.appendChild(gmail.card);
    list.appendChild(stripe.card);
    sec.appendChild(list);
    h.body.appendChild(sec);
    h.sandbox.__agGitHubDeviceAuth.scan();

    assert.strictEqual(gmail.card.querySelector('[data-agy-github-auth="container"]'), null, 'must not inject on Gmail');
    assert.strictEqual(stripe.card.querySelector('[data-agy-github-auth="container"]'), null, 'must not inject on non-GitHub API key');
  }

  // 2. Initial sign-in flow: start -> auto-copy -> Open GitHub -> pending -> slow_down -> complete -> fills YXb input & clicks Sign in.
  {
    const h = createHarness({ pathname: '/customizations/plugins/github' });
    const sec = h.doc.createElement('section');
    sec.setAttribute('aria-label', 'Accounts');
    const list = h.doc.createElement('div');
    const gh = buildProviderCard(h.doc, {
      provider: 'github',
      typeLabel: 'API key',
      setupUrl: 'https://github.com/settings/tokens'
    });
    const yxb = h.doc.createElement('div');
    const input = h.doc.createElement('input');
    input.type = 'password';
    input.setAttribute('aria-label', 'Secret for github');
    const signInBtn = h.doc.createElement('button');
    signInBtn.textContent = 'Sign in';
    signInBtn.disabled = true;
    input.addEventListener('input', () => {
      signInBtn.disabled = !input.value;
    });
    yxb.appendChild(input);
    yxb.appendChild(signInBtn);
    gh.top.appendChild(yxb);
    list.appendChild(gh.card);
    sec.appendChild(list);
    h.body.appendChild(sec);

    h.sandbox.__agGitHubDeviceAuth.scan();
    const startBtn = gh.card.querySelector('[data-agy-github-auth="signin-btn"]');
    assert(startBtn, 'Sign in with GitHub (Browser) button must be injected');
    startBtn.click();

    assert.strictEqual(h.bridgeCalls.start.length, 1);
    assert.strictEqual(h.bridgeCalls.start[0].tok, 'bridge-secret-tok');
    const startReqId = h.bridgeCalls.start[0].reqId;

    h.sandbox.__agGitHubDeviceAuthCallback(startReqId, JSON.stringify({
      ok: true,
      device_code: 'dev-code-123',
      user_code: 'ABCD-1234',
      verification_uri: 'https://github.com/login/device',
      expires_in: 900,
      interval: 5
    }));
    await h.flushMicrotasks();

    assert.strictEqual(h.bridgeCalls.copy.length, 1, 'must auto-copy user_code');
    assert.strictEqual(h.bridgeCalls.copy[0].codeStr, 'ABCD-1234');
    const codeEl = gh.card.querySelector('[data-agy-github-auth="user-code"]');
    assert(codeEl && codeEl.textContent === 'ABCD-1234', 'must render user_code');

    const openBtn = gh.card.querySelector('[data-agy-github-auth="open-btn"]');
    assert(openBtn, 'Open GitHub button must render');
    openBtn.click();
    assert.strictEqual(h.bridgeCalls.open.length, 1);
    assert.strictEqual(h.bridgeCalls.open[0].uri, 'https://github.com/login/device');

    // Poll 1: authorization_pending
    h.advanceTime(5000);
    h.runPendingTimers();
    assert.strictEqual(h.bridgeCalls.poll.length, 1);
    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.poll[0].reqId, JSON.stringify({
      ok: true, status: 'pending', error: 'authorization_pending'
    }));
    await h.flushMicrotasks();
    assert.strictEqual(h.sandbox.__agGitHubDeviceAuth.getState().intervalMs, 5000);

    // Poll 2: slow_down -> interval must increase
    h.advanceTime(5000);
    h.runPendingTimers();
    assert.strictEqual(h.bridgeCalls.poll.length, 2);
    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.poll[1].reqId, JSON.stringify({
      ok: true, status: 'slow_down', error: 'slow_down', interval: 10
    }));
    await h.flushMicrotasks();
    assert.strictEqual(h.sandbox.__agGitHubDeviceAuth.getState().intervalMs, 10000, 'slow_down must increase interval to 10s');

    // Poll 3: complete -> fills input and clicks Sign in (after deferred macrotask)
    h.advanceTime(10000);
    h.runPendingTimers();
    assert.strictEqual(h.bridgeCalls.poll.length, 3);
    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.poll[2].reqId, JSON.stringify({
      ok: true, status: 'complete', access_token: 'gho_test_token_xyz', token_type: 'bearer'
    }));
    await h.flushMicrotasks();
    h.runPendingTimers();
    h.runPendingTimers();

    assert.strictEqual(input.value, 'gho_test_token_xyz', 'must populate YXb password input');
    assert.strictEqual(signInBtn.clicks, 1, 'must click enabled Sign in button');
    assert.strictEqual(h.sandbox.__agGitHubDeviceAuth.getState().status, 'idle');
  }

  // 3. Multi-account Add account flow: clicks native "Add account", mounts new input, fills & clicks "Add".
  {
    const h = createHarness({ pathname: '/customizations/plugins/github' });
    const sec = h.doc.createElement('section');
    sec.setAttribute('aria-label', 'Accounts');
    const list = h.doc.createElement('div');
    const gh = buildProviderCard(h.doc, {
      provider: 'github',
      typeLabel: 'API key',
      setupUrl: 'https://github.com/settings/tokens'
    });
    const ul = h.doc.createElement('ul');
    ul.setAttribute('aria-label', 'Signed-in accounts');
    gh.card.appendChild(ul);

    let mountedAddInput = null;
    let mountedAddSubmit = null;
    const nativeAddBtn = h.doc.createElement('button');
    nativeAddBtn.textContent = 'Add account';
    nativeAddBtn.addEventListener('click', () => {
      const yxb = h.doc.createElement('div');
      mountedAddInput = h.doc.createElement('input');
      mountedAddInput.type = 'password';
      mountedAddInput.setAttribute('aria-label', 'Secret for a new github account');
      mountedAddSubmit = h.doc.createElement('button');
      mountedAddSubmit.textContent = 'Add';
      mountedAddSubmit.disabled = true;
      mountedAddInput.addEventListener('input', () => {
        mountedAddSubmit.disabled = !mountedAddInput.value;
      });
      yxb.appendChild(mountedAddInput);
      yxb.appendChild(mountedAddSubmit);
      gh.card.appendChild(yxb);
    });
    gh.card.appendChild(nativeAddBtn);
    list.appendChild(gh.card);
    sec.appendChild(list);
    h.body.appendChild(sec);

    h.sandbox.__agGitHubDeviceAuth.scan();
    const addGhBtn = gh.card.querySelector('[data-agy-github-auth="add-btn"]');
    assert(addGhBtn, 'Add account with GitHub (Browser) button must be injected when accounts exist');
    addGhBtn.click();

    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.start[0].reqId, JSON.stringify({
      ok: true, device_code: 'dev-2', user_code: 'WXYZ-9876', verification_uri: 'https://github.com/login/device', expires_in: 900, interval: 5
    }));
    await h.flushMicrotasks();
    h.advanceTime(5000);
    h.runPendingTimers();

    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.poll[0].reqId, JSON.stringify({
      ok: true, status: 'complete', access_token: 'gho_second_account_tok'
    }));
    await h.flushMicrotasks();
    h.runPendingTimers();
    h.runPendingTimers();

    assert.strictEqual(nativeAddBtn.clicks, 1, 'must click native Add account button to reveal YXb');
    assert(mountedAddInput, 'new account input must mount');
    assert.strictEqual(mountedAddInput.value, 'gho_second_account_tok');
    assert.strictEqual(mountedAddSubmit.clicks, 1, 'must click Add submit button');
  }

  // 4. Cancel ignores stale callbacks & error states (expired_token, access_denied) stop polling cleanly.
  {
    const h = createHarness({ pathname: '/customizations/plugins/github' });
    const sec = h.doc.createElement('section');
    sec.setAttribute('aria-label', 'Accounts');
    const list = h.doc.createElement('div');
    const gh = buildProviderCard(h.doc, {
      provider: 'github',
      typeLabel: 'API key',
      setupUrl: 'https://github.com/settings/tokens'
    });
    list.appendChild(gh.card);
    sec.appendChild(list);
    h.body.appendChild(sec);
    h.sandbox.__agGitHubDeviceAuth.scan();

    gh.card.querySelector('[data-agy-github-auth="signin-btn"]').click();
    const cancelBtn = gh.card.querySelector('[data-agy-github-auth="cancel-btn"]');
    cancelBtn.click();
    assert.strictEqual(h.sandbox.__agGitHubDeviceAuth.getState().status, 'idle');

    // Late start response for canceled flow must be ignored
    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.start[0].reqId, JSON.stringify({
      ok: true, device_code: 'stale', user_code: 'STAL-0000', verification_uri: 'https://github.com/login/device', expires_in: 900, interval: 5
    }));
    await h.flushMicrotasks();
    assert.strictEqual(h.sandbox.__agGitHubDeviceAuth.getState().status, 'idle', 'canceled flow must ignore late callback');

    // access_denied stops polling cleanly
    gh.card.querySelector('[data-agy-github-auth="signin-btn"]').click();
    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.start[1].reqId, JSON.stringify({
      ok: true, device_code: 'dev-deny', user_code: 'DENY-1111', verification_uri: 'https://github.com/login/device', expires_in: 900, interval: 5
    }));
    await h.flushMicrotasks();
    h.advanceTime(5000);
    h.runPendingTimers();
    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.poll[0].reqId, JSON.stringify({
      ok: true, status: 'error', error: 'access_denied'
    }));
    await h.flushMicrotasks();
    assert.strictEqual(h.sandbox.__agGitHubDeviceAuth.getState().status, 'error');
    assert(h.sandbox.__agGitHubDeviceAuth.getState().error.includes('canceled'));
  }

  // 5. Regression: pre-filled PAT input + React 18 async microtask state commit must submit fresh token, not stale value.
  {
    const h = createHarness({ pathname: '/customizations/plugins/github' });
    const sec = h.doc.createElement('section');
    sec.setAttribute('aria-label', 'Accounts');
    const list = h.doc.createElement('div');
    const gh = buildProviderCard(h.doc, {
      provider: 'github',
      typeLabel: 'API key',
      setupUrl: 'https://github.com/settings/tokens'
    });
    const yxb = h.doc.createElement('div');
    const input = h.doc.createElement('input');
    input.type = 'password';
    input.value = 'stale_typed_pat';
    input.setAttribute('aria-label', 'Secret for github');
    const signInBtn = h.doc.createElement('button');
    signInBtn.textContent = 'Sign in';
    signInBtn.disabled = false; // already enabled due to pre-filled stale input

    let committedState = 'stale_typed_pat';
    let submittedSecret = null;
    input.addEventListener('input', () => {
      const nextVal = input.value;
      // Simulate React 18 createRoot committing state asynchronously in a microtask
      Promise.resolve().then(() => {
        committedState = nextVal;
      });
    });
    signInBtn.addEventListener('click', () => {
      submittedSecret = committedState;
    });
    yxb.appendChild(input);
    yxb.appendChild(signInBtn);
    gh.top.appendChild(yxb);
    list.appendChild(gh.card);
    sec.appendChild(list);
    h.body.appendChild(sec);

    h.sandbox.__agGitHubDeviceAuth.scan();
    gh.card.querySelector('[data-agy-github-auth="signin-btn"]').click();
    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.start[0].reqId, JSON.stringify({
      ok: true, device_code: 'dev-race', user_code: 'RACE-2222', verification_uri: 'https://github.com/login/device', expires_in: 900, interval: 5
    }));
    await h.flushMicrotasks();
    h.advanceTime(5000);
    h.runPendingTimers();
    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.poll[0].reqId, JSON.stringify({
      ok: true, status: 'complete', access_token: 'gho_fresh_device_token'
    }));
    await h.flushMicrotasks();
    // Before macrotask fires, button must NOT have been clicked synchronously with stale value
    assert.strictEqual(signInBtn.clicks, 0, 'must wait a macrotask before clicking already-enabled submit button');
    h.runPendingTimers();
    assert.strictEqual(signInBtn.clicks, 1, 'must click Sign in after async React commit');
    assert.strictEqual(submittedSecret, 'gho_fresh_device_token', 'must submit fresh access_token, not stale input');

    // 6. Regression: bottom-pinning when React appends Signed-in accounts list + Add account after container.
    const container = gh.card.querySelector('[data-agy-github-auth="container"]');
    const ul = h.doc.createElement('ul');
    ul.setAttribute('aria-label', 'Signed-in accounts');
    const nativeAdd = h.doc.createElement('button');
    nativeAdd.textContent = 'Add account';
    gh.card.appendChild(ul);
    gh.card.appendChild(nativeAdd);
    assert.notStrictEqual(gh.card.lastElementChild, container, 'precondition: React appended nodes after container');
    h.sandbox.__agGitHubDeviceAuth.scan();
    assert.strictEqual(gh.card.lastElementChild, container, 'container must be re-pinned to bottom of card');
  }

  // 7. Regression: ConnectRPC fallback sends Connect-Protocol-Version: 1 header when card is unmounted.
  {
    const h = createHarness({ pathname: '/customizations/plugins/github' });
    const sec = h.doc.createElement('section');
    sec.setAttribute('aria-label', 'Accounts');
    const list = h.doc.createElement('div');
    const gh = buildProviderCard(h.doc, {
      provider: 'github',
      typeLabel: 'API key',
      setupUrl: 'https://github.com/settings/tokens'
    });
    list.appendChild(gh.card);
    sec.appendChild(list);
    h.body.appendChild(sec);
    h.sandbox.__agGitHubDeviceAuth.scan();

    gh.card.querySelector('[data-agy-github-auth="signin-btn"]').click();
    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.start[0].reqId, JSON.stringify({
      ok: true, device_code: 'dev-rpc', user_code: 'RPCC-3333', verification_uri: 'https://github.com/login/device', expires_in: 900, interval: 5
    }));
    await h.flushMicrotasks();
    // Simulate navigating away (card unmounted)
    h.body.innerHTML = '';
    h.advanceTime(5000);
    h.runPendingTimers();
    h.sandbox.__agGitHubDeviceAuthCallback(h.bridgeCalls.poll[0].reqId, JSON.stringify({
      ok: true, status: 'complete', access_token: 'gho_rpc_fallback_tok'
    }));
    await h.flushMicrotasks();
    assert.strictEqual(h.bridgeCalls.fetch.length, 1, 'must fall back to SignInPluginAuthProvider RPC');
    assert.strictEqual(
      h.bridgeCalls.fetch[0].url,
      '/exa.language_server_pb.LanguageServerService/SignInPluginAuthProvider'
    );
    assert.strictEqual(
      h.bridgeCalls.fetch[0].init.headers['Connect-Protocol-Version'],
      '1',
      'ConnectRPC fallback must send Connect-Protocol-Version: 1'
    );
    assert.strictEqual(
      JSON.parse(h.bridgeCalls.fetch[0].init.body).secret,
      'gho_rpc_fallback_tok'
    );
  }
}

runTests().catch((err) => {
  console.error(err);
  process.exit(1);
});
EOF
fi

echo "web build selftest: OK"
