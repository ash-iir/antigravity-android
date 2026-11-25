(function(){
/**
 * patches/agent_notify/install.js - Bridges agent completion & action-required cues
 * (audio chimes and Web Notification API) to native Android background notifications.
 */
var global = typeof window !== 'undefined' ? window : this;
if (!global.__AG_AGENT_NOTIFY_INSTALLED__) {
  global.__AG_AGENT_NOTIFY_INSTALLED__ = true;

  var lastSentByType = {};
  var DEBOUNCE_MS = 4000;

  function sendNativeAlert(eventType, title, body) {
    try {
      var now = Date.now();
      if (lastSentByType[eventType] && (now - lastSentByType[eventType]) < DEBOUNCE_MS) {
        return;
      }
      lastSentByType[eventType] = now;

      var token = global.__AG_NATIVE_BRIDGE_TOKEN__;
      var bridge = global.Android || global.AndroidBridge;
      if (token && bridge && typeof bridge.notifyAgentEvent === 'function') {
        bridge.notifyAgentEvent(String(token), String(eventType || 'done'),
          String(title || 'Antigravity'), String(body || 'Task update ready'));
      }
    } catch (e) {
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[AGY_NOTIFY] dispatch error', e);
      }
    }
  }

  function inspectMediaSrc(src) {
    if (!src) return;
    var s = String(src);
    if (s.indexOf('antigravityActionRequired') >= 0) {
      sendNativeAlert('action_required', 'Antigravity — Action Required',
        'Agent is waiting for your confirmation. Tap to return.');
    } else if (s.indexOf('antigravityCascadeDone') >= 0) {
      sendNativeAlert('done', 'Antigravity — Task Finished',
        'Your agent finished responding. Tap to view.');
    }
  }

  // 1. Hook HTMLAudioElement.prototype.play() for built-in completion/approval chimes.
  try {
    if (typeof HTMLAudioElement !== 'undefined' && HTMLAudioElement.prototype && HTMLAudioElement.prototype.play) {
      var origPlay = HTMLAudioElement.prototype.play;
      HTMLAudioElement.prototype.play = function () {
        try {
          inspectMediaSrc(this.currentSrc || this.src);
        } catch (ignored) {}
        return origPlay.apply(this, arguments);
      };
    }
  } catch (ignored) {}

  // 2. Hook window.Notification so web notifications surface natively in Android status bar.
  try {
    var NativeNotification = global.Notification;
    var WrappedNotification = function (title, options) {
      var opts = options || {};
      sendNativeAlert('web_notification', title || 'Antigravity', opts.body || 'Tap to open Antigravity');
      this.title = title || '';
      this.body = opts.body || '';
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
