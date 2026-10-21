# D8 actual stabilizer integration

Status: core/preview implementation staged; tests pending. Canonical Lead engine/View and Helper media untouched.

Brushv18 adds an optional suggestion. Raw stroke codecv6 adds optional actual settings on u16 flagbit7: u8 mode, f32 strength, f32 screen radius, u16 window, exactly11bytes after the frozen secondary palette and before sample count. Historical v1-v5 layouts/flag policies remain. A missing actual setting replays the exact historical smoother; a later brush suggestion is never consulted.

`StrokeStabilizer` supplies off, smooth, pulled string, moving average and catch-up-on-lift through identical list/sink state transitions. All three ordinary/admitted/shaped replay filter sites consume the saved actual setting. Capture freezes settings once and retains post-guide raw samples. Captured-source identity includes them. Saved stamp-to-fill rebrush, core raster and SVG outline paths carry them; watercolor rebrush refuses an actual setting rather than silently using its separate Helper filter.

Apply UNIBRUSH_D8_VIEW.patch after D4/D5/D6 and the D7 host admission gate. It resolves preference once only for family-marked Unibrush stamp contacts, passes the same actual value to the live filter and both raw captures, and preserves null historical paths for unrelated engines. D7 whole-stroke activation remains refused pending the physical publisher.

Apply UNIBRUSH_D8_FILL.patch and UNIBRUSH_D8_FILL_TESTS.patch for Android saved fill outlines. The live unrelated fill path passes null. View preference persistence, canvas panel and sheet controls land with D9. Source values must be set on both live canvas and preview requests; UI and GL resolvers retain separate owners.

Exact raw-codec, historical fixtures, contact-policy, ordinary/admitted/whole-stroke replay, fill/SVG and actual-preview tests are included. Execution and combined adapter proof remain pending; no phone/frame-rate/owner acceptance claim.
