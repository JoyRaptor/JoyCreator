---
source: Official source (raw.githubusercontent.com tag 1.13.0) + Context7 API
library: Material Components for Android
package: com.google.android.material:material
topic: BottomSheetDialog setContentView(View) then add children before show()
version: 1.13.0
fetched: 2026-08-08T00:00:00Z
official_docs: https://developer.android.com/reference/com/google/android/material/bottomsheet/BottomSheetDialog
---

# setContentView(View) then add children before show() (Material 1.13.0)

## Question
Is it valid to add children (action buttons) to the content view AFTER `setContentView(view)` but BEFORE `show()`?

## Answer: YES — completely valid.

## Why (verbatim from 1.13.0 source)

```java
@Override
public void setContentView(View view) {
  super.setContentView(wrapInBottomSheet(0, view, null));
}
```

`wrapInBottomSheet` wraps your view in the dialog's own layout:

```java
private View wrapInBottomSheet(
    int layoutResId, @Nullable View view, @Nullable ViewGroup.LayoutParams params) {
  ensureContainerAndBehavior();
  CoordinatorLayout coordinator = (CoordinatorLayout) container.findViewById(R.id.coordinator);
  if (layoutResId != 0 && view == null) {
    view = getLayoutInflater().inflate(layoutResId, coordinator, false);
  }
  ...
  bottomSheet.removeAllViews();
  if (params == null) {
    bottomSheet.addView(view);
  } else {
    bottomSheet.addView(view, params);
  }
  ...
  return container;
}
```

- The view you pass becomes a child of the dialog's internal `design_bottom_sheet` FrameLayout inside a CoordinatorLayout.
- The wrapping happens synchronously in `setContentView` — but `show()` is what actually attaches the window and displays.
- Your view is a normal ViewGroup at that point; adding children to it before `show()` is a standard view-tree operation. `show()` does NOT re-wrap or re-inflate content.

## Gotchas
- The dialog's container structure (CoordinatorLayout + touch_outside + bottomSheet FrameLayout) is created on demand by `ensureContainerAndBehavior()` — calling `dlg.getBehavior()` before `setContentView` also forces creation; that's fine.
- `wrapInBottomSheet` calls `bottomSheet.removeAllViews()` — this clears the internal FrameLayout, NOT your root view. But beware: calling `setContentView(...)` a second time will detach/remove the previously-set root view from the sheet.
- `onStart()` resets state: `if (behavior != null && behavior.getState() == STATE_HIDDEN) behavior.setState(STATE_COLLAPSED);` — safe.
- For plan step 2: build your LinearLayout (content + Set/Cancel TextViews), call `setContentView(root)`, then wire listeners / add any remaining children, then `show()`. All fine.

## Slightly better alternative
- Build the full root view FIRST (including action buttons), then call `setContentView(root)` once. Equivalent outcome; the source guarantees post-setContentView mutation also works, but a fully-built view is clearer.
