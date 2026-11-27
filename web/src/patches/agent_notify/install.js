/**
 * patches/agent_notify/install.js - Bridges upstream Antigravity agent notifications
 * (via window.nativeNotifications and Web Notification API fallback) to native Android
 * background notifications, with foreground-state focus sync and conversation deep-linking.
 */
var global = typeof window !== 'undefined' ? window : this;
if (!global.__AG_AGENT_NOTIFY_INSTALLED__) {
  global.__AG_AGENT_NOTIFY_INSTALLED__ = true;

  var lastSentByKey = {};
  var seenNotificationIds = {};
  var seenNotificationQueue = [];
  var MAX_SEEN_IDS = 200;
  var DEBOUNCE_MS = 2500;
  var clickListeners = [];
  var pendingClickedConvId = global.__AG_PENDING_NOTIFICATION_CONV_ID__ || null;

  var origHasFocus = (typeof document !== 'undefined' && typeof document.hasFocus === 'function')
    ? document.hasFocus.bind(document)
    : null;

  var hostForegroundFlag = (typeof global.__AG_HOST_IN_FOREGROUND__ === 'boolean')
    ? global.__AG_HOST_IN_FOREGROUND__
    : undefined;
  var onForegroundFlagChanged = null;
  var onUpstreamDoneSent = null;

  try {
    Object.defineProperty(global, '__AG_HOST_IN_FOREGROUND__', {
      configurable: true,
      enumerable: true,
      get: function () {
        return hostForegroundFlag;
      },
      set: function (val) {
        var next = Boolean(val);
        var changed = (hostForegroundFlag !== next);
        hostForegroundFlag = next;
        if (changed && typeof onForegroundFlagChanged === 'function') {
          try { onForegroundFlagChanged(next); } catch (ignored) {}
        }
      }
    });
  } catch (ignored) {}

  function getBridge() {
    return global.Android || global.AndroidBridge || null;
  }

  function isHostInForeground() {
    try {
      var bridge = getBridge();
      if (bridge && typeof bridge.isInForeground === 'function') {
        return Boolean(bridge.isInForeground());
      }
    } catch (ignored) {}
    if (typeof hostForegroundFlag === 'boolean') {
      return hostForegroundFlag;
    }
    if (typeof document !== 'undefined') {
      if (document.visibilityState === 'hidden') return false;
      if (origHasFocus) {
        try { return Boolean(origHasFocus()); } catch (ignored) {}
      }
    }
    return true;
  }

  // Keep document.hasFocus() accurate when MainActivity is backgrounded/screen-off.
  // WebViewLifecycle.pause() intentionally leaves the WebView unpaused so agent streams
  // keep draining in the background, which can leave Chromium's document.hasFocus() === true
  // and cause upstream isConversationInView() to suppress background notifications.
  try {
    if (typeof document !== 'undefined' && origHasFocus) {
      document.hasFocus = function () {
        if (!isHostInForeground()) {
          return false;
        }
        return origHasFocus();
      };
    }
  } catch (ignored) {}

  function rememberNotificationId(notifId) {
    if (!notifId) return false;
    var key = String(notifId);
    if (seenNotificationIds[key]) return true;
    seenNotificationIds[key] = true;
    seenNotificationQueue.push(key);
    if (seenNotificationQueue.length > MAX_SEEN_IDS) {
      var oldest = seenNotificationQueue.shift();
      if (oldest) delete seenNotificationIds[oldest];
    }
    return false;
  }

  function sendNativeAlert(eventType, title, body, conversationId, notificationId, silent) {
    try {
      if (isHostInForeground()) {
        return;
      }
      var convId = conversationId ? String(conversationId).trim() : '';
      var notifId = notificationId ? String(notificationId).trim() : '';
      if (notifId && rememberNotificationId(notifId)) {
        return;
      }

      var typeStr = String(eventType || 'done');
      var debounceKey = typeStr + ':' + convId;
      var now = Date.now();
      if (lastSentByKey[debounceKey] && (now - lastSentByKey[debounceKey]) < DEBOUNCE_MS) {
        return;
      }
      lastSentByKey[debounceKey] = now;

      var token = global.__AG_NATIVE_BRIDGE_TOKEN__;
      var bridge = getBridge();
      if (!token || !bridge) {
        return;
      }
      var safeTitle = String(title || 'Antigravity');
      var safeBody = String(body || 'Agent update ready');
      if (typeof bridge.notifyAgentEventRich === 'function') {
        bridge.notifyAgentEventRich(
          String(token),
          typeStr,
          safeTitle,
          safeBody,
          convId,
          notifId,
          Boolean(silent)
        );
      } else if (typeof bridge.notifyAgentEvent === 'function') {
        bridge.notifyAgentEvent(String(token), typeStr, safeTitle, safeBody);
      }
    } catch (e) {
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[AGY_NOTIFY] dispatch error', e);
      }
    }
  }

  function dispatchNotificationClick(conversationId) {
    if (!conversationId) return;
    var cid = String(conversationId).trim();
    if (!cid) return;
    if (clickListeners.length === 0) {
      pendingClickedConvId = cid;
      global.__AG_PENDING_NOTIFICATION_CONV_ID__ = cid;
      return;
    }
    pendingClickedConvId = null;
    global.__AG_PENDING_NOTIFICATION_CONV_ID__ = null;
    for (var i = 0; i < clickListeners.length; i++) {
      try {
        clickListeners[i]({ cascadeId: cid });
      } catch (e) {
        if (typeof console !== 'undefined' && console.warn) {
          console.warn('[AGY_NOTIFY] click listener error', e);
        }
      }
    }
  }

  global.__agOpenNotificationConversation = dispatchNotificationClick;

  // 1. Primary path: upstream main.js natively checks window.nativeNotifications
  //    (fJd notification service) for agent completion, replies, and approval/question prompts,
  //    and registers an onClicked({ cascadeId }) handler wired directly to the conversation router.
  try {
    global.nativeNotifications = {
      send: function (payload) {
        var p = payload || {};
        var inner = (p.payload && typeof p.payload === 'object') ? p.payload : {};
        var convId = inner.cascadeId || p.cascadeId || '';
        var notifId = p.id || '';
        var title = p.title || 'Antigravity';
        var body = p.body || 'Agent update ready';
        var eventType = (notifId && String(notifId).indexOf('agent-finished-') === 0)
          ? 'done'
          : (inner.type || 'action_required');
        sendNativeAlert(eventType, title, body, convId, notifId, Boolean(p.silent));
        if (eventType === 'done' && typeof onUpstreamDoneSent === 'function') {
          try { onUpstreamDoneSent(convId); } catch (ignored) {}
        }
        return Promise.resolve();
      },
      onClicked: function (cb) {
        if (typeof cb === 'function') {
          clickListeners.push(cb);
          var pending = pendingClickedConvId || global.__AG_PENDING_NOTIFICATION_CONV_ID__;
          if (pending) {
            pendingClickedConvId = null;
            global.__AG_PENDING_NOTIFICATION_CONV_ID__ = null;
            try {
              cb({ cascadeId: String(pending) });
            } catch (ignored) {}
          }
        }
        return function () {
          var idx = clickListeners.indexOf(cb);
          if (idx >= 0) {
            clickListeners.splice(idx, 1);
          }
        };
      },
      openSystemPreferences: function () {
        try {
          var token = global.__AG_NATIVE_BRIDGE_TOKEN__;
          var bridge = getBridge();
          if (token && bridge && typeof bridge.openNotificationSettings === 'function') {
            bridge.openNotificationSettings(String(token));
          }
        } catch (ignored) {}
        return Promise.resolve();
      }
    };
  } catch (ignored) {}

  // 2. Fallback: shim window.Notification so any Web Notification API caller
  //    also surfaces natively in Android's status bar.
  try {
    var NativeNotification = global.Notification;
    var WrappedNotification = function (title, options) {
      var opts = options || {};
      var data = (opts.data && typeof opts.data === 'object') ? opts.data : {};
      var convId = data.cascadeId || '';
      var notifId = opts.tag || '';
      sendNativeAlert('web_notification', title || 'Antigravity',
        opts.body || 'Tap to open Antigravity', convId, notifId, Boolean(opts.silent));
      this.title = title || '';
      this.body = opts.body || '';
      this.tag = notifId;
      this.close = function () {};
      this.addEventListener = function () {};
      this.removeEventListener = function () {};
      if (typeof NativeNotification === 'function') {
        try {
          return new NativeNotification(title, options);
        } catch (ignored) {}
      }
    };
    WrappedNotification.permission = 'granted';
    WrappedNotification.requestPermission = function (cb) {
      if (typeof cb === 'function') {
        try { cb('granted'); } catch (ignored) {}
      }
      return Promise.resolve('granted');
    };
    global.Notification = WrappedNotification;
  } catch (ignored) {}

  // 3. Background Assistant Reply Watcher:
  //    In Swarm conversations, background monitors keep the conversation active
  //    ("Waiting for subagent") instead of transitioning to IDLE, so upstream
  //    window.nativeNotifications.send never fires when the coordinator posts a reply.
  //    While backgrounded, watch [data-testid="conversation-view"] for a new, settled
  //    assistant reply and dispatch via sendNativeAlert('done', ...) with shared dedupe.
  try {
    var REPLY_SETTLE_MS = 1800;
    var MUTATION_THROTTLE_MS = 250;
    var BG_POLL_MS = 1500;
    var MAX_SNIPPET_LEN = 180;
    var EXCLUDED_TEXT_SELECTOR = [
      '.sr-only',
      '[data-find-ignore]',
      '[aria-hidden="true"]',
      '[data-testid="live-status-row"]',
      '[data-testid="idle-status-text"]',
      '[data-testid="turn-cards-container"]',
      '[data-testid="subagent-node"]',
      'button',
      'svg',
      'style',
      'script'
    ].join(',');
    var IGNORED_STATUS_TEXT = {
      'agent working': true,
      'agent said': true,
      'waiting for subagent': true,
      'working': true
    };

    var bgWatcherActive = false;
    var bgConvId = '';
    var baselineFingerprint = '';
    var pendingFingerprint = '';
    var pendingText = '';
    var settleTimer = null;
    var throttleTimer = null;
    var bgPollTimer = null;
    var replyObserver = null;
    var observedTarget = null;

    function normalizeText(str) {
      return String(str || '').replace(/\s+/g, ' ').trim();
    }

    function simpleHash(str) {
      var h = 2166136261;
      for (var i = 0; i < str.length; i++) {
        h ^= str.charCodeAt(i);
        h = (h + (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24)) >>> 0;
      }
      return h.toString(36);
    }

    function getConversationView() {
      if (typeof document === 'undefined' || typeof document.querySelector !== 'function') {
        return null;
      }
      return document.querySelector('[data-testid="conversation-view"]');
    }

    function getActiveConversationId(viewEl) {
      try {
        var el = viewEl || getConversationView();
        if (el && typeof el.getAttribute === 'function') {
          var attr = el.getAttribute('data-cascade-id');
          if (attr && attr !== 'conversation') {
            return String(attr).trim();
          }
        }
      } catch (ignored) {}
      try {
        var loc = global.location;
        if (loc && typeof loc.pathname === 'string') {
          var m = loc.pathname.match(/\/(?:c|conversations)\/([^/?#]+)/);
          if (m && m[1]) {
            return decodeURIComponent(m[1]).trim();
          }
        }
        if (loc && typeof loc.search === 'string') {
          var sm = loc.search.match(/[?&](?:cascadeId|conversationId)=([^&#]+)/);
          if (sm && sm[1]) {
            return decodeURIComponent(sm[1]).trim();
          }
        }
      } catch (ignored) {}
      return '';
    }

    function extractCleanText(node) {
      if (!node) return '';
      try {
        if (typeof node.cloneNode === 'function') {
          var clone = node.cloneNode(true);
          if (typeof clone.querySelectorAll === 'function') {
            var excluded = clone.querySelectorAll(EXCLUDED_TEXT_SELECTOR);
            for (var i = 0; i < excluded.length; i++) {
              if (excluded[i] && excluded[i].parentNode) {
                excluded[i].parentNode.removeChild(excluded[i]);
              }
            }
          }
          return normalizeText(clone.textContent);
        }
      } catch (ignored) {}
      return normalizeText(node.innerText || node.textContent || '');
    }

    function isIgnoredStatusOnly(text) {
      if (!text) return true;
      return Boolean(IGNORED_STATUS_TEXT[text.toLowerCase()]);
    }

    function readLatestAssistantReply(viewEl) {
      var empty = { fingerprint: '', text: '' };
      if (!viewEl || typeof viewEl.querySelectorAll !== 'function') {
        return empty;
      }

      // Path A: Coordinator / bubble layout ([data-testid="agent-response"] [data-bubble-side="agent"])
      var bubbles = viewEl.querySelectorAll('[data-testid="agent-response"] [data-bubble-side="agent"]');
      if (bubbles && bubbles.length > 0) {
        for (var i = bubbles.length - 1; i >= 0; i--) {
          var b = bubbles[i];
          var bText = extractCleanText(b);
          if (!isIgnoredStatusOnly(bText)) {
            var stepIdx = (typeof b.getAttribute === 'function' && b.getAttribute('data-step-index')) || '';
            return {
              fingerprint: 'bubble:' + bubbles.length + ':' + i + ':' + stepIdx + ':' + bText.length + ':' + simpleHash(bText),
              text: bText
            };
          }
        }
        return empty;
      }

      // Path B: Standard step-list layout ([data-testid="agent-response"])
      var responses = viewEl.querySelectorAll('[data-testid="agent-response"]');
      if (!responses || responses.length === 0) {
        return empty;
      }
      var lastIdx = responses.length - 1;
      var lastResp = responses[lastIdx];
      if (!lastResp) {
        return empty;
      }

      if (typeof lastResp.querySelectorAll === 'function') {
        var mdBlocks = lastResp.querySelectorAll('[data-markdown-block-index]');
        if (mdBlocks && mdBlocks.length > 0) {
          var lastBlock = mdBlocks[mdBlocks.length - 1];
          var mdParent = lastBlock && lastBlock.parentNode;
          var parts = [];
          if (mdParent && typeof mdParent.querySelectorAll === 'function') {
            var groupBlocks = mdParent.querySelectorAll('[data-markdown-block-index]');
            for (var j = 0; j < groupBlocks.length; j++) {
              var gText = extractCleanText(groupBlocks[j]);
              if (gText) parts.push(gText);
            }
          } else {
            var singleText = extractCleanText(lastBlock);
            if (singleText) parts.push(singleText);
          }
          var joined = normalizeText(parts.join(' '));
          if (!isIgnoredStatusOnly(joined)) {
            return {
              fingerprint: 'md:' + responses.length + ':' + mdBlocks.length + ':' + joined.length + ':' + simpleHash(joined),
              text: joined
            };
          }
        }
      }

      // Fallback: cleaned text of the latest agent-response container
      var fallbackText = extractCleanText(lastResp);
      if (!isIgnoredStatusOnly(fallbackText)) {
        return {
          fingerprint: 'resp:' + responses.length + ':' + fallbackText.length + ':' + simpleHash(fallbackText),
          text: fallbackText
        };
      }
      return empty;
    }

    function clearPendingReply() {
      pendingFingerprint = '';
      pendingText = '';
      if (settleTimer !== null && typeof clearTimeout === 'function') {
        clearTimeout(settleTimer);
      }
      settleTimer = null;
    }

    function detachObserver() {
      if (replyObserver) {
        try { replyObserver.disconnect(); } catch (ignored) {}
        replyObserver = null;
      }
      observedTarget = null;
    }

    function attachObserver(target) {
      if (typeof MutationObserver !== 'function') return;
      var node = target || (typeof document !== 'undefined' ? (document.body || document.documentElement) : null);
      if (!node || node === observedTarget) return;
      detachObserver();
      try {
        replyObserver = new MutationObserver(scheduleReplyCheck);
        replyObserver.observe(node, { childList: true, subtree: true, characterData: true });
        observedTarget = node;
      } catch (ignored) {
        replyObserver = null;
        observedTarget = null;
      }
    }

    function stopBackgroundWatcher() {
      bgWatcherActive = false;
      bgConvId = '';
      baselineFingerprint = '';
      clearPendingReply();
      detachObserver();
      if (throttleTimer !== null && typeof clearTimeout === 'function') {
        clearTimeout(throttleTimer);
      }
      throttleTimer = null;
      if (bgPollTimer !== null && typeof clearInterval === 'function') {
        clearInterval(bgPollTimer);
      }
      bgPollTimer = null;
    }

    function onReplySettled() {
      settleTimer = null;
      if (!bgWatcherActive || isHostInForeground()) {
        stopBackgroundWatcher();
        return;
      }
      var viewEl = getConversationView();
      var convId = getActiveConversationId(viewEl);
      if (convId !== bgConvId) {
        bgConvId = convId;
        baselineFingerprint = readLatestAssistantReply(viewEl).fingerprint;
        clearPendingReply();
        return;
      }
      var snap = readLatestAssistantReply(viewEl);
      if (!snap.text || snap.fingerprint === baselineFingerprint) {
        clearPendingReply();
        return;
      }
      if (snap.fingerprint !== pendingFingerprint) {
        pendingFingerprint = snap.fingerprint;
        pendingText = snap.text;
        if (typeof setTimeout === 'function') {
          settleTimer = setTimeout(onReplySettled, REPLY_SETTLE_MS);
        }
        return;
      }

      // Reply has settled: update baseline first so this reply can never fire twice.
      baselineFingerprint = snap.fingerprint;
      var fullText = snap.text;
      clearPendingReply();

      var snippet = fullText.length > MAX_SNIPPET_LEN
        ? (fullText.slice(0, MAX_SNIPPET_LEN - 3).trim() + '...')
        : fullText;
      var dedupeKey = 'bg-reply-' + (convId || 'active') + '-' + simpleHash(baselineFingerprint);
      sendNativeAlert('done', 'Antigravity', snippet, convId, dedupeKey, false);
    }

    function checkForBackgroundReply() {
      if (!bgWatcherActive) return;
      if (isHostInForeground()) {
        stopBackgroundWatcher();
        return;
      }
      var viewEl = getConversationView();
      if (viewEl && viewEl !== observedTarget) {
        attachObserver(viewEl);
      }
      var convId = getActiveConversationId(viewEl);
      if (convId !== bgConvId) {
        bgConvId = convId;
        baselineFingerprint = readLatestAssistantReply(viewEl).fingerprint;
        clearPendingReply();
        return;
      }
      var snap = readLatestAssistantReply(viewEl);
      if (!snap.text || snap.fingerprint === baselineFingerprint) {
        if (pendingFingerprint) {
          clearPendingReply();
        }
        return;
      }
      if (snap.fingerprint !== pendingFingerprint) {
        pendingFingerprint = snap.fingerprint;
        pendingText = snap.text;
        if (settleTimer !== null && typeof clearTimeout === 'function') {
          clearTimeout(settleTimer);
        }
        if (typeof setTimeout === 'function') {
          settleTimer = setTimeout(onReplySettled, REPLY_SETTLE_MS);
        }
      }
    }

    function scheduleReplyCheck() {
      if (!bgWatcherActive || throttleTimer !== null) return;
      if (typeof setTimeout !== 'function') {
        checkForBackgroundReply();
        return;
      }
      throttleTimer = setTimeout(function () {
        throttleTimer = null;
        checkForBackgroundReply();
      }, MUTATION_THROTTLE_MS);
    }

    function startBackgroundWatcher() {
      if (bgWatcherActive) return;
      var viewEl = getConversationView();
      bgWatcherActive = true;
      bgConvId = getActiveConversationId(viewEl);
      baselineFingerprint = readLatestAssistantReply(viewEl).fingerprint;
      clearPendingReply();
      attachObserver(viewEl);
      if (bgPollTimer === null && typeof setInterval === 'function') {
        bgPollTimer = setInterval(checkForBackgroundReply, BG_POLL_MS);
      }
    }

    function syncForegroundWatcher() {
      if (isHostInForeground()) {
        if (bgWatcherActive) {
          stopBackgroundWatcher();
        }
      } else {
        if (!bgWatcherActive) {
          startBackgroundWatcher();
        }
      }
    }

    onForegroundFlagChanged = function (inFg) {
      if (inFg) {
        stopBackgroundWatcher();
      } else {
        startBackgroundWatcher();
      }
    };

    onUpstreamDoneSent = function (convId) {
      if (!bgWatcherActive) return;
      var viewEl = getConversationView();
      var activeConvId = getActiveConversationId(viewEl);
      if (!convId || !activeConvId || String(convId) === activeConvId) {
        baselineFingerprint = readLatestAssistantReply(viewEl).fingerprint;
        clearPendingReply();
      }
    };

    if (typeof document !== 'undefined' && typeof document.addEventListener === 'function') {
      document.addEventListener('visibilitychange', syncForegroundWatcher, true);
    }
    if (typeof global.addEventListener === 'function') {
      global.addEventListener('pagehide', syncForegroundWatcher);
      global.addEventListener('pageshow', syncForegroundWatcher);
      global.addEventListener('blur', syncForegroundWatcher);
      global.addEventListener('focus', syncForegroundWatcher);
    }

    // If loaded while already backgrounded, arm immediately.
    if (!isHostInForeground()) {
      startBackgroundWatcher();
    }
  } catch (ignored) {}
}
