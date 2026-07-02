window.Faditor = (function () {
  let tl = null;
  let durationMs = 0;
  function register(timeline, totalDurationMs) {
    tl = timeline;
    durationMs = totalDurationMs;
    tl.pause(0);
    document.title = "FADITOR_READY";
  }
  function seek(ms) {
    if (!tl) return;
    tl.pause();
    tl.seek(ms / 1000, false);
  }
  function getDuration() {
    return durationMs;
  }
  return { register, seek, getDuration };
})();
