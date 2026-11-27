(function(){
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
    if (typeof global.__AG_HOST_IN_FOREGROUND__ === 'boolean') {
      return global.__AG_HOST_IN_FOREGROUND__;
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
}


})();
