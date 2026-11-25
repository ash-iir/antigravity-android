/**
 * patches/theme_sync/notify.js - push the resolved theme to native Android.
 */
function notify() {
  var theme = resolveTheme();
  if (theme === state.last) return theme;
  state.last = theme;
  state.notifications++;
  try {
    var isDark = (theme === 'dark');
    document.documentElement.classList.toggle('dark', isDark);
    document.documentElement.classList.toggle('light', !isDark);
    document.documentElement.style.colorScheme = theme;
  } catch (e) { /* ignore */ }
  try {
    if (window.Android) {
      var token = (typeof window !== 'undefined' && window.__AG_NATIVE_BRIDGE_TOKEN__)
        ? String(window.__AG_NATIVE_BRIDGE_TOKEN__) : '';
      state.hasAndroid = true;
      if (typeof window.Android.setThemeByUser === 'function') {
        window.Android.setThemeByUser(token, theme);
      } else if (typeof window.Android.setTheme === 'function') {
        window.Android.setTheme(token, theme);
      }
      if (typeof window.Android.onVisualThemeChanged === 'function') {
        window.Android.onVisualThemeChanged(token, theme);
      }
    }
  } catch (e) { /* never throw into JS */ }
  return theme;
}
