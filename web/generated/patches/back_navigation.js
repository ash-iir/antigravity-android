(function(){
/**
 * patches/back_navigation/install.js - Steps back one level inside open
 * Settings / modal / bottom-sheet overlays before falling back to WebView
 * history navigation.
 */
var global = typeof window !== 'undefined' ? window : this;
if (!global.__AG_BACK_NAVIGATION_INSTALLED__) {
  global.__AG_BACK_NAVIGATION_INSTALLED__ = true;

  var OVERLAY_SELECTOR =
    '[data-bottom-sheet-popup]:not([data-closed]), ' +
    '.settings-modal-container:not([aria-hidden="true"]), ' +
    '[role="dialog"]';

  var BACK_BUTTON_SELECTOR = 'button[aria-label="Back"]:not([disabled])';

  function getStyle(el) {
    if (typeof global.getComputedStyle === 'function') {
      try {
        return global.getComputedStyle(el);
      } catch (ignored) {}
    }
    return el && el.style ? el.style : null;
  }

  function isVisible(el) {
    if (!el) return false;
    var style = getStyle(el);
    if (style) {
      if (style.display === 'none' || style.visibility === 'hidden') {
        return false;
      }
    }
    if (typeof el.getBoundingClientRect !== 'function') {
      return false;
    }
    var rect = el.getBoundingClientRect();
    return Boolean(rect && rect.width > 0 && rect.height > 0);
  }

  function getZIndex(el) {
    var style = getStyle(el);
    if (!style || style.zIndex == null || style.zIndex === '' || style.zIndex === 'auto') {
      return 0;
    }
    var n = parseInt(style.zIndex, 10);
    return isNaN(n) ? 0 : n;
  }

  function findTopmostOverlay() {
    if (typeof document === 'undefined' || !document.querySelectorAll) {
      return null;
    }
    var candidates = document.querySelectorAll(OVERLAY_SELECTOR);
    var best = null;
    var bestZ = -Infinity;
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (!isVisible(el)) continue;
      if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') continue;
      if (el.hasAttribute && el.hasAttribute('inert')) continue;
      var z = getZIndex(el);
      if (z >= bestZ) {
        bestZ = z;
        best = el;
      }
    }
    return best;
  }

  function handleBack() {
    try {
      var overlay = findTopmostOverlay();
      if (!overlay || typeof overlay.querySelectorAll !== 'function') {
        return false;
      }
      var buttons = overlay.querySelectorAll(BACK_BUTTON_SELECTOR);
      var target = null;
      for (var i = 0; i < buttons.length; i++) {
        var btn = buttons[i];
        if (!btn || btn.disabled) continue;
        if (btn.getAttribute && btn.getAttribute('aria-hidden') === 'true') continue;
        if (btn.hasAttribute && btn.hasAttribute('inert')) continue;
        if (typeof btn.closest === 'function') {
          if (btn.closest('[inert]') || btn.closest('[aria-hidden="true"]')) continue;
        }
        if (!isVisible(btn)) continue;
        target = btn;
      }
      if (!target || typeof target.click !== 'function') {
        return false;
      }
      target.click();
      return true;
    } catch (e) {
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[AGY_BACK_NAV] handleBack error', e);
      }
      return false;
    }
  }

  global.__agHandleBack = handleBack;
}


})();
