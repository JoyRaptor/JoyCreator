---
source: Official source (raw.githubusercontent.com tag 1.13.0) + Context7 API + framework touch-dispatch behavior
library: Material Components for Android
package: com.google.android.material:material
topic: BottomSheetDialog + child requestDisallowInterceptTouchEvent (hue wheel drag)
version: 1.13.0
fetched: 2026-08-08T00:00:00Z
official_docs: https://developer.android.com/reference/com/google/android/material/bottomsheet/BottomSheetDialog
---

# Child touch disallow (hue wheel) vs sheet drag (Material 1.13.0)

## Question
Any gotchas with BottomSheetDialog containing a custom View that calls `getParent().requestDisallowInterceptTouchEvent(true)` on touch (a hue wheel)? Does the sheet's drag-to-dismiss conflict?

## Answer: No conflict/crash. Disallowing interception on the wheel is the CORRECT approach; it stops the sheet drag only when the gesture starts on the wheel.

## Mechanism
- The sheet drag is implemented by `BottomSheetBehavior` acting on the internal `design_bottom_sheet` FrameLayout, and touch interception happens at the `CoordinatorLayout` level (`CoordinatorLayout.onInterceptTouchEvent` → `BottomSheetBehavior.onInterceptTouchEvent`).
- `requestDisallowInterceptTouchEvent(true)` is the standard Android framework mechanism: it tells the entire ancestor chain (including the CoordinatorLayout) not to intercept the ongoing gesture.
- Therefore, while the finger is down on the wheel, the sheet will NOT start dragging; the wheel receives the gesture. When the finger is placed on non-wheel parts of the sheet (background, buttons, drag handle), the sheet drags normally.
- 1.13.0 even ships a related fix: "Prevent ACTION_DOWN events on the BottomSheetHandleDragView from setting touchingScrollChild to true" (release notes), i.e. the library explicitly manages which child starts a "scroll" gesture for the behavior — same principle.

## Confirmed 1.13.0 touch details in BottomSheetDialog
```java
bottomSheet.setOnTouchListener(
    new View.OnTouchListener() {
      @Override
      public boolean onTouch(View view, MotionEvent event) {
        // Consume the event and prevent it from falling through
        return true;
      }
    });
```
- This listener is on the internal bottomSheet FrameLayout (the PARENT of your content root), not on your content. Child views (wheel, buttons) still receive and handle their own touches first; the listener prevents touches that reach the FrameLayout from falling through to the window behind.
- Your action buttons are TextViews inside your LinearLayout — they receive clicks normally.

## Nested-scroll nuance
- Your content is a plain LinearLayout (non-scrollable) → no NestedScrolling/NestedScrollView interplay, so there is no scroll-conflict beyond the touch-disallow described above.
- If you later swap in a NestedScrollView, you would need to handle scroll flags for the behavior; not applicable to the current plan.

## Recommendation
- Keep `requestDisallowInterceptTouchEvent(true)` on the hue wheel's DOWN action (and re-disallow on subsequent moves as needed). This is the standard, expected way to make a custom drag-surface inside a bottom sheet, and it does not break sheet dismissal from other areas.
- Optionally set `dlg.getBehavior().setDraggable(false)` if you want to disable drag-to-dismiss entirely and rely on explicit buttons; otherwise leave it true (default).
