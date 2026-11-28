# STU-F1: Text "Fit to width", side-handle drag and export vs preview (11-28, Note 9, project "Untitled 8", 6 s blue clip, text "ahello 2")
(The stray "a" is a key press of mine; it does not matter for the check.)

| Check | Result |
|---|---|
| Width chip cycles to **Fit to width** (4 taps from Auto width); a slider appears beside it | ✅ PASS (`fit_to_width_before_drag.png`) |
| Drag the **right side handle** inward (swipe from the handle, 400 ms) | ✅ PASS: the box narrows from about 800 to 550 px and the text re-fits to it, same proportion (text about 0.83 of the box before and after); Undo count went from 1 to 2, one step (`box_selected_with_side_handles.png`, `after_side_handle_drag_preview.png`) |
| Export (video, defaults, 640x360) vs the preview after the drag | ✅ PASS: the exported text spans x 0.141 to 0.605 of the frame (width 0.464, `measure_text.py`); the preview showed 0.139 to 0.606 by eye on the screenshot. Same size and place, `export_frame_1_5s.png` |
Not tried: the Fit slider's own effect; Fit with several lines; Fit on a text with a Look (3D/neon) applied. Row STU-F1: **done** for the three checks above.
