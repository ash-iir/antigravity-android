/**
 * patches/clipboard_image_paste/install.js - Enables pasting images into chat
 * from Android's clipboard or soft keyboard (Gboard / Samsung Keyboard commitContent).
 */
var global = typeof window !== 'undefined' ? window : this;
if (!global.__AG_CLIPBOARD_IMAGE_PASTE__) {
  global.__AG_CLIPBOARD_IMAGE_PASTE__ = true;

  function base64ToUint8Array(b64) {
    var bin = atob(b64);
    var len = bin.length;
    var bytes = new Uint8Array(len);
    for (var i = 0; i < len; i++) {
      bytes[i] = bin.charCodeAt(i);
    }
    return bytes;
  }

  function isVisible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  }

  function findComposerTarget() {
    var active = document.activeElement;
    if (active && (active.tagName === 'TEXTAREA' || active.tagName === 'INPUT' || active.isContentEditable)) {
      return active;
    }
    var selectors = [
      'textarea',
      '[contenteditable="true"]',
      '[role="textbox"]'
    ];
    for (var s = 0; s < selectors.length; s++) {
      var list = document.querySelectorAll(selectors[s]);
      for (var i = list.length - 1; i >= 0; i--) {
        if (isVisible(list[i])) {
          return list[i];
        }
      }
    }
    return document.body || document.documentElement;
  }

  function findImageFileInput() {
    var inputs = document.querySelectorAll('input[type="file"]');
    var wildcardAll = '*' + '/' + '*';
    for (var i = inputs.length - 1; i >= 0; i--) {
      var accept = (inputs[i].getAttribute('accept') || '').toLowerCase();
      if (!accept || accept.indexOf('image') >= 0 || accept.indexOf(wildcardAll) >= 0) {
        return inputs[i];
      }
    }
    return inputs.length > 0 ? inputs[inputs.length - 1] : null;
  }

  function attachDataTransfer(ev, name, dt) {
    try {
      if (!ev[name] || !ev[name].files || ev[name].files.length === 0) {
        Object.defineProperty(ev, name, {
          configurable: true,
          enumerable: true,
          get: function () { return dt; }
        });
      }
    } catch (ignored) {}
  }

  global.__agPasteImage = function (base64, mimeType, filename) {
    if (!base64) return false;
    try {
      var bytes = base64ToUint8Array(base64);
      var type = mimeType || 'image/png';
      var name = filename || 'pasted_image.png';
      var file = new File([bytes], name, { type: type, lastModified: Date.now() });
      var dt = new DataTransfer();
      dt.items.add(file);

      var target = findComposerTarget();
      if (target && typeof target.focus === 'function') {
        try { target.focus(); } catch (ignored) {}
      }

      var pasteEv;
      if (typeof ClipboardEvent === 'function') {
        pasteEv = new ClipboardEvent('paste', {
          bubbles: true,
          cancelable: true,
          clipboardData: dt
        });
      } else {
        pasteEv = new Event('paste', { bubbles: true, cancelable: true });
      }
      pasteEv.__agSyntheticPaste = true;
      attachDataTransfer(pasteEv, 'clipboardData', dt);
      var notCanceled = target.dispatchEvent(pasteEv);
      if (!notCanceled || pasteEv.defaultPrevented) {
        return true;
      }

      var dropEv;
      if (typeof DragEvent === 'function') {
        dropEv = new DragEvent('drop', {
          bubbles: true,
          cancelable: true,
          dataTransfer: dt
        });
      } else {
        dropEv = new Event('drop', { bubbles: true, cancelable: true });
      }
      attachDataTransfer(dropEv, 'dataTransfer', dt);
      notCanceled = target.dispatchEvent(dropEv);
      if (!notCanceled || dropEv.defaultPrevented) {
        return true;
      }

      var fileInput = findImageFileInput();
      if (fileInput) {
        try {
          fileInput.files = dt.files;
          fileInput.dispatchEvent(new Event('change', { bubbles: true }));
          return true;
        } catch (ignored) {}
      }
    } catch (e) {
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[AGY_PASTE] image paste failed', e);
      }
    }
    return false;
  };

  try {
    document.addEventListener('paste', function (e) {
      if (!e || e.__agSyntheticPaste) return;
      var cd = e.clipboardData;
      if (cd) {
        if (cd.files && cd.files.length > 0) return;
        var plain = '';
        try { plain = cd.getData('text/plain') || ''; } catch (ignored) {}
        if (plain.length > 0) return;
      }
      var token = global.__AG_NATIVE_BRIDGE_TOKEN__;
      var bridge = global.Android || global.AndroidBridge;
      if (!token || !bridge) return;

      if (typeof bridge.hasClipboardImage === 'function'
          && typeof bridge.requestClipboardImagePaste === 'function') {
        if (!bridge.hasClipboardImage(String(token))) return;
        e.preventDefault();
        if (typeof e.stopImmediatePropagation === 'function') {
          e.stopImmediatePropagation();
        }
        bridge.requestClipboardImagePaste(String(token));
        return;
      }

      if (typeof bridge.readClipboardImage !== 'function') return;
      var raw = bridge.readClipboardImage(String(token));
      if (!raw) return;
      var parsed;
      try {
        parsed = JSON.parse(raw);
      } catch (ignored) {
        return;
      }
      if (!parsed || !parsed.base64) return;

      e.preventDefault();
      if (typeof e.stopImmediatePropagation === 'function') {
        e.stopImmediatePropagation();
      }
      global.__agPasteImage(parsed.base64, parsed.mimeType, parsed.filename);
    }, true);
  } catch (e) {
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[AGY_PASTE] listener install error', e);
    }
  }
}
