/**
 * patches/swarm_chip/install.js - Injects a compact, theme-matched `/swarm`
 * toggle chip into the chat composer toolbar (`[data-agent-composer-root]`).
 *
 * - Tap when inactive: prefixes `/swarm ` at the start of the composer
 *   (preserving any existing draft text) using Lexical-compatible input events,
 *   then focuses the composer with the cursor at the end.
 * - Tap when active (composer already starts with `/swarm `): removes the
 *   leading `/swarm ` prefix cleanly.
 */
var global = typeof window !== 'undefined' ? window : this;
if (!global.__AG_SWARM_CHIP_INSTALLED__) {
  global.__AG_SWARM_CHIP_INSTALLED__ = true;

  var PREFIX = '/swarm ';
  var STYLE_ATTR = 'data-agy';
  var STYLE_VAL = 'swarm-chip';
  var CHIP_ATTR = 'data-agy-swarm-chip';
  var FOOTER_SELECTOR = '[data-agent-composer-root]';
  var EDITOR_SELECTOR = '[data-lexical-editor="true"], [contenteditable="true"], textarea';

  var CHIP_CSS =
    'button[' + CHIP_ATTR + '="true"] {' +
      'display: inline-flex;' +
      'align-items: center;' +
      'justify-content: center;' +
      'flex-shrink: 0;' +
      'height: 24px;' +
      'padding: 0 7px;' +
      'margin: 0 1px;' +
      'border-radius: 9999px;' +
      'border: 1px solid var(--border, rgba(148, 163, 184, 0.28));' +
      'background: var(--muted, rgba(148, 163, 184, 0.12));' +
      'color: var(--muted-foreground, var(--foreground, #94a3b8));' +
      'font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;' +
      'font-size: 11px;' +
      'font-weight: 500;' +
      'line-height: 1;' +
      'letter-spacing: -0.01em;' +
      'white-space: nowrap;' +
      'cursor: pointer;' +
      'user-select: none;' +
      '-webkit-user-select: none;' +
      '-webkit-tap-highlight-color: transparent;' +
      'transition: background-color 140ms ease, border-color 140ms ease, color 140ms ease, opacity 140ms ease;' +
    '}\n' +
    'button[' + CHIP_ATTR + '="true"]:hover {' +
      'color: var(--foreground, #f1f5f9);' +
      'border-color: var(--foreground, rgba(148, 163, 184, 0.45));' +
    '}\n' +
    'button[' + CHIP_ATTR + '="true"]:active {' +
      'opacity: 0.78;' +
    '}\n' +
    'button[' + CHIP_ATTR + '="true"][data-active="true"] {' +
      'background: color-mix(in srgb, var(--primary, #38bdf8) 18%, transparent);' +
      'border-color: var(--primary, #38bdf8);' +
      'color: var(--primary, #38bdf8);' +
    '}\n';

  function injectStyle() {
    if (typeof document === 'undefined' || !document.createElement) return;
    if (document.querySelector && document.querySelector('style[' + STYLE_ATTR + '="' + STYLE_VAL + '"]')) {
      return;
    }
    var style = document.createElement('style');
    style.setAttribute(STYLE_ATTR, STYLE_VAL);
    style.textContent = CHIP_CSS;
    var target = document.head || document.documentElement;
    if (target && typeof target.appendChild === 'function') {
      target.appendChild(style);
    }
  }

  function isTextInput(el) {
    if (!el || !el.tagName) return false;
    var tag = String(el.tagName).toUpperCase();
    return tag === 'TEXTAREA' || tag === 'INPUT';
  }

  function normalizeText(text) {
    if (!text) return '';
    return String(text).replace(/\u200B/g, '').replace(/\u00A0/g, ' ');
  }

  function getEditorText(editorEl) {
    if (!editorEl) return '';
    if (isTextInput(editorEl)) {
      return normalizeText(editorEl.value || '');
    }
    return normalizeText(editorEl.textContent || '');
  }

  function hasSwarmPrefix(text) {
    return normalizeText(text).indexOf(PREFIX) === 0;
  }

  function findComposerEditor(footer) {
    var cur = footer ? (footer.parentElement || footer.parent) : null;
    for (var depth = 0; cur && depth < 4; depth++) {
      if (typeof cur.querySelector === 'function') {
        var found = cur.querySelector('[data-lexical-editor="true"]') ||
                    cur.querySelector('[contenteditable="true"]') ||
                    cur.querySelector('textarea');
        if (found) return found;
      }
      cur = cur.parentElement || cur.parent;
    }
    if (typeof document !== 'undefined' && typeof document.querySelector === 'function') {
      return document.querySelector('[data-lexical-editor="true"]') ||
             document.querySelector('[contenteditable="true"]') ||
             document.querySelector('textarea');
    }
    return null;
  }

  function focusEditor(editorEl) {
    if (!editorEl || typeof editorEl.focus !== 'function') return;
    try {
      editorEl.focus({ preventScroll: true });
    } catch (ignored) {
      try { editorEl.focus(); } catch (ignored2) {}
    }
  }

  function collectTextNodes(root) {
    var out = [];
    if (!root) return out;
    function walk(node) {
      if (!node) return;
      if (node.nodeType === 3) {
        out.push(node);
        return;
      }
      var kids = node.childNodes || node.children || [];
      for (var i = 0; i < kids.length; i++) {
        walk(kids[i]);
      }
    }
    walk(root);
    return out;
  }

  function dispatchSelectionChange() {
    if (typeof document === 'undefined' || typeof document.dispatchEvent !== 'function') return;
    try {
      var ev = typeof global.Event === 'function'
        ? new global.Event('selectionchange')
        : { type: 'selectionchange' };
      document.dispatchEvent(ev);
    } catch (ignored) {}
  }

  function setCaretAtStart(editorEl) {
    if (typeof document === 'undefined' || typeof document.createRange !== 'function') return false;
    var sel = typeof global.getSelection === 'function' ? global.getSelection() : null;
    if (!sel) return false;
    try {
      var range = document.createRange();
      var textNodes = collectTextNodes(editorEl);
      if (textNodes.length > 0) {
        range.setStart(textNodes[0], 0);
      } else {
        var firstChild = editorEl.firstElementChild || (editorEl.children && editorEl.children[0]) || null;
        if (firstChild) {
          range.setStart(firstChild, 0);
        } else {
          range.setStart(editorEl, 0);
        }
      }
      range.collapse(true);
      sel.removeAllRanges();
      sel.addRange(range);
      dispatchSelectionChange();
      return true;
    } catch (ignored) {
      return false;
    }
  }

  function setCaretAtEnd(editorEl) {
    if (!editorEl) return;
    if (isTextInput(editorEl)) {
      try {
        var len = (editorEl.value || '').length;
        if (typeof editorEl.setSelectionRange === 'function') {
          editorEl.setSelectionRange(len, len);
        } else {
          editorEl.selectionStart = len;
          editorEl.selectionEnd = len;
        }
      } catch (ignored) {}
      return;
    }
    if (typeof document === 'undefined' || typeof document.createRange !== 'function') return;
    var sel = typeof global.getSelection === 'function' ? global.getSelection() : null;
    if (!sel) return;
    try {
      var range = document.createRange();
      var textNodes = collectTextNodes(editorEl);
      if (textNodes.length > 0) {
        var last = textNodes[textNodes.length - 1];
        var lastLen = typeof last.nodeValue === 'string' ? last.nodeValue.length : (last.textContent || '').length;
        range.setStart(last, lastLen);
      } else {
        range.selectNodeContents(editorEl);
        range.collapse(false);
      }
      range.collapse(true);
      sel.removeAllRanges();
      sel.addRange(range);
      dispatchSelectionChange();
    } catch (ignored) {}
  }

  function selectLeadingChars(editorEl, count) {
    if (typeof document === 'undefined' || typeof document.createRange !== 'function') return false;
    var sel = typeof global.getSelection === 'function' ? global.getSelection() : null;
    if (!sel) return false;
    var textNodes = collectTextNodes(editorEl);
    if (textNodes.length === 0) return false;

    var startNode = null;
    var startOffset = 0;
    var endNode = null;
    var endOffset = 0;
    var remaining = count;

    for (var i = 0; i < textNodes.length && remaining > 0; i++) {
      var tn = textNodes[i];
      var raw = typeof tn.nodeValue === 'string' ? tn.nodeValue : (tn.textContent || '');
      for (var j = 0; j < raw.length && remaining > 0; j++) {
        if (raw.charAt(j) === '\u200B') continue;
        if (!startNode) {
          startNode = tn;
          startOffset = j;
        }
        remaining--;
        endNode = tn;
        endOffset = j + 1;
      }
    }

    if (!startNode || !endNode || remaining > 0) return false;
    try {
      var range = document.createRange();
      range.setStart(startNode, startOffset);
      range.setEnd(endNode, endOffset);
      sel.removeAllRanges();
      sel.addRange(range);
      dispatchSelectionChange();
      return true;
    } catch (ignored) {
      return false;
    }
  }

  function makeInputEvent(type, inputType, data, cancelable) {
    var init = {
      bubbles: true,
      cancelable: Boolean(cancelable),
      inputType: inputType,
      data: data === undefined ? null : data
    };
    if (typeof global.InputEvent === 'function') {
      try {
        return new global.InputEvent(type, init);
      } catch (ignored) {}
    }
    if (typeof global.Event === 'function') {
      var ev = new global.Event(type, { bubbles: true, cancelable: Boolean(cancelable) });
      try { ev.inputType = inputType; } catch (ignored) {}
      try { ev.data = data === undefined ? null : data; } catch (ignored) {}
      return ev;
    }
    return {
      type: type,
      bubbles: true,
      cancelable: Boolean(cancelable),
      inputType: inputType,
      data: data === undefined ? null : data,
      defaultPrevented: false,
      preventDefault: function () { this.defaultPrevented = true; }
    };
  }

  function setNativeInputValue(el, nextValue) {
    try {
      var proto = String(el.tagName).toUpperCase() === 'TEXTAREA'
        ? (global.HTMLTextAreaElement && global.HTMLTextAreaElement.prototype)
        : (global.HTMLInputElement && global.HTMLInputElement.prototype);
      var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
      if (desc && typeof desc.set === 'function') {
        desc.set.call(el, nextValue);
      } else {
        el.value = nextValue;
      }
    } catch (ignored) {
      el.value = nextValue;
    }
    if (typeof el.dispatchEvent === 'function') {
      el.dispatchEvent(makeInputEvent('input', 'insertText', nextValue, false));
    }
  }

  function insertPrefixInContentEditable(editorEl) {
    focusEditor(editorEl);
    setCaretAtStart(editorEl);

    var executed = false;
    if (typeof document !== 'undefined' && typeof document.execCommand === 'function') {
      try {
        executed = Boolean(document.execCommand('insertText', false, PREFIX));
      } catch (ignored) {
        executed = false;
      }
    }

    if (!executed || !hasSwarmPrefix(getEditorText(editorEl))) {
      var beforeEv = makeInputEvent('beforeinput', 'insertText', PREFIX, true);
      if (typeof editorEl.dispatchEvent === 'function') {
        editorEl.dispatchEvent(beforeEv);
      }
      if (!beforeEv.defaultPrevented && !hasSwarmPrefix(getEditorText(editorEl))) {
        var textNodes = collectTextNodes(editorEl);
        if (textNodes.length > 0) {
          var first = textNodes[0];
          if (typeof first.nodeValue === 'string') {
            first.nodeValue = PREFIX + first.nodeValue;
          } else {
            first.textContent = PREFIX + (first.textContent || '');
          }
        } else {
          editorEl.textContent = PREFIX + (editorEl.textContent || '');
        }
        if (typeof editorEl.dispatchEvent === 'function') {
          editorEl.dispatchEvent(makeInputEvent('input', 'insertText', PREFIX, false));
        }
      }
    }

    focusEditor(editorEl);
    setCaretAtEnd(editorEl);
  }

  function removePrefixInContentEditable(editorEl) {
    focusEditor(editorEl);
    var selected = selectLeadingChars(editorEl, PREFIX.length);

    var executed = false;
    if (selected && typeof document !== 'undefined' && typeof document.execCommand === 'function') {
      try {
        executed = Boolean(document.execCommand('delete', false));
      } catch (ignored) {
        executed = false;
      }
    }

    if (!executed || hasSwarmPrefix(getEditorText(editorEl))) {
      var beforeEv = makeInputEvent('beforeinput', 'deleteContentBackward', null, true);
      if (typeof editorEl.dispatchEvent === 'function') {
        editorEl.dispatchEvent(beforeEv);
      }
      if (!beforeEv.defaultPrevented && hasSwarmPrefix(getEditorText(editorEl))) {
        var textNodes = collectTextNodes(editorEl);
        if (textNodes.length > 0) {
          var remaining = PREFIX.length;
          for (var i = 0; i < textNodes.length && remaining > 0; i++) {
            var tn = textNodes[i];
            var raw = typeof tn.nodeValue === 'string' ? tn.nodeValue : (tn.textContent || '');
            var kept = '';
            for (var j = 0; j < raw.length; j++) {
              var ch = raw.charAt(j);
              if (remaining > 0 && ch !== '\u200B') {
                remaining--;
              } else {
                kept += ch;
              }
            }
            if (typeof tn.nodeValue === 'string') {
              tn.nodeValue = kept;
            } else {
              tn.textContent = kept;
            }
          }
        } else {
          var current = getEditorText(editorEl);
          if (current.indexOf(PREFIX) === 0) {
            editorEl.textContent = current.slice(PREFIX.length);
          }
        }
        if (typeof editorEl.dispatchEvent === 'function') {
          editorEl.dispatchEvent(makeInputEvent('input', 'deleteContentBackward', null, false));
        }
      }
    }

    focusEditor(editorEl);
    setCaretAtEnd(editorEl);
  }

  function toggleSwarmInEditor(editorEl) {
    if (!editorEl) return false;
    var current = getEditorText(editorEl);
    var active = hasSwarmPrefix(current);

    if (isTextInput(editorEl)) {
      var nextVal = active ? current.slice(PREFIX.length) : PREFIX + current;
      setNativeInputValue(editorEl, nextVal);
      focusEditor(editorEl);
      setCaretAtEnd(editorEl);
      return !active;
    }

    if (active) {
      removePrefixInContentEditable(editorEl);
      return false;
    }
    insertPrefixInContentEditable(editorEl);
    return true;
  }

  function syncChipState(chip, footer) {
    if (!chip) return;
    var editor = findComposerEditor(footer);
    var active = editor ? hasSwarmPrefix(getEditorText(editor)) : false;
    chip.setAttribute('data-active', active ? 'true' : 'false');
    chip.setAttribute('aria-pressed', active ? 'true' : 'false');
  }

  function syncAllChips() {
    if (typeof document === 'undefined' || !document.querySelectorAll) return;
    var chips = document.querySelectorAll('button[' + CHIP_ATTR + '="true"]');
    for (var i = 0; i < chips.length; i++) {
      var chip = chips[i];
      var footer = typeof chip.closest === 'function' ? chip.closest(FOOTER_SELECTOR) : (chip.parentElement || chip.parent);
      syncChipState(chip, footer);
    }
  }

  function createChip(footer) {
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.setAttribute(CHIP_ATTR, 'true');
    btn.setAttribute('data-active', 'false');
    btn.setAttribute('aria-label', 'Toggle /swarm command');
    btn.setAttribute('aria-pressed', 'false');
    btn.setAttribute('title', 'Prefix message with /swarm');
    btn.textContent = '/swarm';

    function preventBlur(e) {
      if (e && typeof e.preventDefault === 'function') {
        e.preventDefault();
      }
    }

    if (typeof btn.addEventListener === 'function') {
      btn.addEventListener('mousedown', preventBlur);
      btn.addEventListener('pointerdown', preventBlur);
      btn.addEventListener('click', function (e) {
        if (e && typeof e.preventDefault === 'function') e.preventDefault();
        if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
        var editor = findComposerEditor(footer);
        if (!editor) return;
        toggleSwarmInEditor(editor);
        syncChipState(btn, footer);
        if (typeof global.setTimeout === 'function') {
          global.setTimeout(function () {
            syncChipState(btn, footer);
          }, 0);
        }
      });
    }

    return btn;
  }

  function ensureChipInFooter(footer) {
    if (!footer || typeof footer.querySelector !== 'function') return;
    var existing = footer.querySelector('button[' + CHIP_ATTR + '="true"]');
    if (existing) {
      syncChipState(existing, footer);
      return;
    }

    var chip = createChip(footer);
    var rightGroup = footer.lastElementChild || (footer.children && footer.children.length > 1 ? footer.children[footer.children.length - 1] : null);
    if (rightGroup && rightGroup.parentElement === footer && typeof footer.insertBefore === 'function') {
      footer.insertBefore(chip, rightGroup);
    } else if (typeof footer.appendChild === 'function') {
      footer.appendChild(chip);
    }
    syncChipState(chip, footer);
  }

  function scan() {
    injectStyle();
    if (typeof document === 'undefined' || !document.querySelectorAll) return;
    var footers = document.querySelectorAll(FOOTER_SELECTOR);
    for (var i = 0; i < footers.length; i++) {
      ensureChipInFooter(footers[i]);
    }
  }

  var scanScheduled = false;
  function scheduleScan() {
    if (scanScheduled) return;
    scanScheduled = true;
    var run = function () {
      scanScheduled = false;
      try { scan(); } catch (ignored) {}
    };
    if (typeof global.setTimeout === 'function') {
      global.setTimeout(run, 50);
    } else {
      run();
    }
  }

  function installObservers() {
    if (typeof document === 'undefined') return;
    if (typeof document.addEventListener === 'function') {
      var syncEvents = ['input', 'keyup', 'paste', 'cut', 'compositionend'];
      for (var i = 0; i < syncEvents.length; i++) {
        document.addEventListener(syncEvents[i], syncAllChips, true);
      }
    }
    if (typeof global.MutationObserver === 'function' && (document.body || document.documentElement)) {
      try {
        var obs = new global.MutationObserver(function () {
          scheduleScan();
        });
        obs.observe(document.body || document.documentElement, {
          childList: true,
          subtree: true,
          characterData: true
        });
      } catch (ignored) {}
    }
  }

  function init() {
    scan();
    installObservers();
  }

  if (typeof document !== 'undefined' && document.readyState === 'loading' && typeof document.addEventListener === 'function') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  global.__agSwarmChip = {
    scan: scan,
    sync: syncAllChips,
    toggle: toggleSwarmInEditor,
    hasPrefix: hasSwarmPrefix,
    PREFIX: PREFIX
  };
}
