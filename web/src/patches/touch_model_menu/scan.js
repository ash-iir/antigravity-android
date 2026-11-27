/**
 * patches/touch_model_menu/scan.js - debounced, mutation-filtered DOM scan for effort groups.
 */

// Trailing debounce: DOM mutates constantly while a reply streams. Coalesce to one pass.
var SCAN_DEBOUNCE_MS = 180;

function scan() {
  var groups = [];
  try {
    groups = document.querySelectorAll(CONFIG.effortGroup);
  } catch (_) {
    groups = [];
  }
  state.groups = groups.length;
  for (var i = 0; i < groups.length; i++) {
    var group = groups[i];
    var row = (group.closest && group.closest(CONFIG.menuItemRole)) || group.parentElement || group;
    registerRow(row);
  }
}

function scheduleScan() {
  if (state.scanTimer) return;
  state.scanTimer = setTimeout(function () {
    state.scanTimer = 0;
    scan();
  }, SCAN_DEBOUNCE_MS);
}

function onMutations(mutations) {
  if (state.scanTimer || !mutations) return;
  for (var i = 0; i < mutations.length; i++) {
    var m = mutations[i];
    var added = m.addedNodes;
    if (!added || added.length === 0) continue;
    var target = m.target;
    if (target && target.closest && target.closest('.prose, pre, code')) {
      continue;
    }
    for (var j = 0; j < added.length; j++) {
      var node = added[j];
      if (!node || node.nodeType !== 1) continue;
      if (node.matches && (node.matches(CONFIG.panel) || node.matches(CONFIG.effortGroup) || node.matches('[role="menu"]'))) {
        scheduleScan();
        return;
      }
      if (node.querySelector && node.querySelector(CONFIG.effortGroup)) {
        scheduleScan();
        return;
      }
    }
  }
}
