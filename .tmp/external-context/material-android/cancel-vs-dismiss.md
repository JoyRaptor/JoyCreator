---
source: Official source (raw.githubusercontent.com tag 1.13.0) + Context7 API
library: Material Components for Android
package: com.google.android.material:material
topic: BottomSheetDialog swipe-down STATE_HIDDEN -> cancel() vs dismiss()
version: 1.13.0
fetched: 2026-08-08T00:00:00Z
official_docs: https://developer.android.com/reference/com/google/android/material/bottomsheet/BottomSheetDialog
---

# Swipe-down dismissal: cancel() vs dismiss() (Material 1.13.0)

## Question
When the user swipes the sheet down to STATE_HIDDEN, does Material 1.13 fire `cancel()` (OnCancelListener) or `dismiss()` (OnDismissListener only)?

## Answer: swipe-down to STATE_HIDDEN FIRES OnCancelListener. Your revert logic in OnCancelListener will work.

## Mechanism (verbatim from 1.13.0 source)

BottomSheetDialog registers an internal BottomSheetCallback in `ensureContainerAndBehavior()`:

```java
private BottomSheetBehavior.BottomSheetCallback bottomSheetCallback =
    new BottomSheetBehavior.BottomSheetCallback() {
      @Override
      public void onStateChanged(
          @NonNull View bottomSheet, @BottomSheetBehavior.State int newState) {
        if (newState == BottomSheetBehavior.STATE_HIDDEN) {
          cancel();
        }
      }

      @Override
      public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
    };
```

`cancel()` (1.13.0):

```java
@Override
public void cancel() {
  BottomSheetBehavior<FrameLayout> behavior = getBehavior();

  if (!dismissWithAnimation || behavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
    super.cancel();
  } else {
    behavior.setState(BottomSheetBehavior.STATE_HIDDEN);
  }
}
```

## Flow
1. User drags sheet down → STATE_DRAGGING → release → STATE_SETTLING → STATE_HIDDEN.
2. Internal callback fires on STATE_HIDDEN → calls `cancel()`.
3. Inside `cancel()`, `behavior.getState()` is already STATE_HIDDEN → `super.cancel()` is called directly.
4. `Dialog.cancel()` → fires **OnCancelListener** → dialog dismisses.

## Important conditions / gotchas
- **hideable is what makes swipe-to-dismiss possible.** `ensureContainerAndBehavior()` runs `behavior.setHideable(cancelable)` and `cancelable` defaults to `true`. If you call `dlg.setCancelable(false)`, hideable becomes false and swipe-down will NOT reach STATE_HIDDEN (sheet bounces back) — OnCancelListener never fires. Don't set cancelable=false if you want swipe-dismiss to revert.
- **`dismiss()` does NOT fire OnCancelListener.** BottomSheetDialog does NOT override `dismiss()` in 1.13.0 — it goes straight to `Dialog.dismiss()` → OnDismissListener only. Your Set/Cancel buttons calling `dlg.dismiss()` after firing your own callback behave exactly as planned (no double-revert).
- **Touch-outside tap also routes through cancel()**: the `touch_outside` click listener calls `cancel()` when `cancelable && isShowing() && shouldWindowCloseOnTouchOutside()` → OnCancelListener fires.
- **Back button routes through cancel()** via MaterialBackOrchestrator (predictive back) / collapse-to-hide → STATE_HIDDEN → cancel().
- **`setDismissWithAnimation(true)`**: programmatic `cancel()` will animate the sheet to STATE_HIDDEN first, then the same callback fires `cancel()` at STATE_HIDDEN. Swipe-down path unaffected.
- **Accessibility ACTION_DISMISS** on the sheet also calls `cancel()`.

## Conclusion for the plan
- Swipe-down dismiss → OnCancelListener fires → your revert (`onLive.onLive(initial)`) runs. 
- BUT note: revert in OnCancelListener also fires for touch-outside and back-button dismissals — that is consistent with "revert the original colour on any non-Set dismissal", which matches the plan's intent.
- If you need to distinguish swipe from back/touch-outside (rare), add a `BottomSheetBehavior.BottomSheetCallback` on `dlg.getBehavior()` and watch `STATE_DRAGGING -> STATE_HIDDEN`; but for the stated plan, OnCancelListener is sufficient and correct.
