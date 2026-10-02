# JB-9.07 paper backend handoff

- [x] Read dispatch/spec, catalogue/render contracts and board-model boundaries; isolate worktree and copy settings.
- [x] Add bounded worker-owned preview cache using PaperRaster, including all visible settings and dimensions.
- [x] Add look/default-surface and lit surface thumbnails; keep pure helpers independent of app UI and tiles.
- [x] Verify actual pixels, stale-preview regression, cache bounds, and mutation under shared build lock/no-daemon.
- [ ] Record own XML counts/time and commands; rebase/push, hand off to Lead without marking the UI row done.

No images generated; no phone installation. Lead retains PaperSheetView, LayerColumnView and JoyBrushActivity ownership.
