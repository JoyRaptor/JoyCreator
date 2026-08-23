---
source: Official source (raw.githubusercontent.com tag 1.13.0) + Context7 API (BottomSheet.md docs)
library: Material Components for Android
package: com.google.android.material:material
topic: BottomSheetDialog drag handle / grabber default
version: 1.13.0
fetched: 2026-08-08T00:00:00Z
official_docs: https://github.com/material-components/material-components-android/blob/master/docs/components/BottomSheet.md
---

# Drag handle / grabber with setContentView(View) (Material 1.13.0)

## Question
Does the sheet show a drag handle/grabber by default with `setContentView(View)` in Material 1.13, and can it be hidden/shown?

## Answer: NO drag handle by default. Add `BottomSheetDragHandleView` to your content to show one (or omit it to hide).

## Evidence
1. **Dialog layout has no drag handle.** The 1.13.0 `design_bottom_sheet_dialog.xml` contains only:
   - `container` (FrameLayout)
   - `coordinator` (CoordinatorLayout)
   - `touch_outside` (View)
   - `design_bottom_sheet` (FrameLayout, `style="?attr/bottomSheetStyle"`, behavior = bottom_sheet_behavior)

2. **BottomSheetDialog.java 1.13.0 does not inflate or add any drag handle** in `wrapInBottomSheet` / `ensureContainerAndBehavior`.

3. **The drag handle is a separate opt-in widget**: `com.google.android.material.bottomsheet.BottomSheetDragHandleView`.
   - Docs (BottomSheet.md, "Standard bottom sheet layout") show it placed explicitly in the sheet layout:
     ```xml
     <com.google.android.material.bottomsheet.BottomSheetDragHandleView
         android:id="@+id/drag_handle"
         android:layout_width="match_parent"
         android:layout_height="wrap_content"/>
     ```
   - Default minimum width/height 48dp (touch target).
   - Purpose: accessibility (TalkBack) — it handles accessibility commands to expand/collapse/hide the sheet AND provides a visual indicator.

## For the colour-picker conversion
- With `setContentView(root)`, the sheet will NOT show a grabber unless you add one.
- To show a grabber: put a `BottomSheetDragHandleView` as the first child of your root LinearLayout.
- To hide it: simply don't include it (or call `setVisibility(View.GONE)` on it if conditionally hidden).
- There is no dedicated `BottomSheetDialog` API in 1.13.0 to toggle the handle; it's purely a content view you control.
- Keep at least ~48dp at the top if you add the handle (per docs guidance).

## Notes
- Drag-to-dismiss itself works WITHOUT any drag handle (the whole sheet is draggable via BottomSheetBehavior, `draggable` defaults to true).
- The handle is primarily for discoverability + accessibility, not a prerequisite for swiping.
