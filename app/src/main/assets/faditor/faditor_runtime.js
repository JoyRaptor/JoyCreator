window.Faditor = (function () {
  let tl = null;
  let durationMs = 0;
  let scale = 1; // registered-time → true-timeline-time correction
  function register(timeline, totalDurationMs) {
    tl = timeline;
    durationMs = totalDurationMs;
    // Models sometimes register a durationMs that differs from the timeline
    // they actually built. The host maps its window to [0..durationMs], so
    // rescale seeks onto the timeline's TRUE duration — this guarantees
    // "end" is the fully-resolved final state, never a stop just short of it.
    try {
      const trueSec = typeof tl.totalDuration === "function"
          ? tl.totalDuration() : tl.duration();
      if (trueSec > 0 && durationMs > 0) {
        scale = (trueSec * 1000) / durationMs;
      }
    } catch (e) { scale = 1; }
    tl.pause(0);
    document.title = "FADITOR_READY";
  }
  function seek(ms) {
    if (!tl) return;
    tl.pause();
    const trueSec = typeof tl.totalDuration === "function"
        ? tl.totalDuration() : tl.duration();
    tl.seek(Math.min((ms * scale) / 1000, trueSec), false);
  }
  function getDuration() {
    return durationMs;
  }
  return { register, seek, getDuration };
})();
