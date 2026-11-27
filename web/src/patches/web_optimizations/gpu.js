/**
 * patches/web_optimizations/gpu.js - GPU-accelerate scroll/animation surfaces,
 * isolate layout for lists, disable expensive mobile backdrop blurs, and
 * optimize mobile touch & plugin UI.
 */
function installGpu(doc) {
  try {
    if (!doc.querySelector('style[data-agy="gpu-acceleration"]')) {
      var gpu = doc.createElement('style');
      gpu.setAttribute('data-agy', 'gpu-acceleration');
      // Promote ONLY the real scroll containers with contain: layout style paint /
      // content-visibility for smooth scrolling on mid-range mobile GPUs.
      gpu.textContent =
        'main, [data-testid="chat-container"], .scrollable {' +
        ' -webkit-overflow-scrolling: touch;' +
        ' overscroll-behavior-y: contain;' +
        ' touch-action: pan-y pinch-zoom; }' +
        '[role="listitem"], .message-row, .artifact-viewer, .agy-sidebar-row {' +
        ' contain: layout style paint;' +
        ' content-visibility: auto;' +
        ' contain-intrinsic-size: auto 120px; }' +
        '*{ -webkit-tap-highlight-color: transparent; }' +
        /* Eliminate expensive full-screen backdrop blur passes on mid-range mobile GPUs */
        '@media (max-width: 768px), (pointer: coarse) {' +
        ' .surface-glass, .agy-fastpick.surface-glass, .quick-input-widget.surface-glass,' +
        ' .surface-composer > .surface-composer-card, [class*="backdrop-blur"] {' +
        '   -webkit-backdrop-filter: none !important;' +
        '   backdrop-filter: none !important; }' +
        ' body[data-agy-theme] .surface-glass,' +
        ' body[data-agy-theme] .agy-fastpick.surface-glass,' +
        ' body[data-agy-theme] .quick-input-widget.surface-glass {' +
        '   background-color: var(--card, #18181b) !important; }' +
        '}' +
        /* Mobile Customizations & Plugin / Sidecar UI Optimizations */
        '@media (max-width: 640px) {' +
        ' [data-testid="plugin-detail"] > div > div {' +
        '   padding-left: 12px !important; padding-right: 12px !important; padding-top: 14px !important; }' +
        ' [role="tablist"][aria-label="Plugin details"] {' +
        '   flex-wrap: nowrap !important; overflow-x: auto !important; -webkit-overflow-scrolling: touch;' +
        '   scrollbar-width: none; padding-bottom: 4px; }' +
        ' [role="tablist"][aria-label="Plugin details"]::-webkit-scrollbar { display: none; }' +
        ' [data-testid="plugin-sidecar-modal"] {' +
        '   max-width: 100vw !important; }' +
        ' [data-testid="sidecar-side-panel-callout"] {' +
        '   flex-direction: column !important; align-items: stretch !important; gap: 10px !important; }' +
        ' [data-testid="sidecar-side-panel-callout"] button {' +
        '   width: 100% !important; justify-content: center !important; height: 32px !important; }' +
        ' [data-testid^="sidecar-row-"] {' +
        '   flex-direction: column !important; align-items: flex-start !important; gap: 2px !important; }' +
        ' [data-testid^="sidecar-row-"] > span:last-child {' +
        '   white-space: normal !important; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }' +
        ' [data-side-panel-open], [data-testid="aux-pane-split"], [data-side-panel-body] {' +
        '   width: 100% !important; max-width: 100vw !important; }' +
        ' [data-testid="side-panel-sash"], .side-panel-sash {' +
        '   display: none !important; }' +
        ' iframe[src*="swarm"], iframe[data-sidecar="swarm_board"] {' +
        '   width: 100% !important; height: 100% !important; border: 0 !important; }' +
        '}';
      (doc.head || doc.documentElement).appendChild(gpu);
    }
  } catch (e) {}
}
