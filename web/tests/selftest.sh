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
fi

echo "web build selftest: OK"
