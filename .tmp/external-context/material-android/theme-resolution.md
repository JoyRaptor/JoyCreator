---
source: Official source (raw.githubusercontent.com tag 1.13.0) + Context7 API
library: Material Components for Android
package: com.google.android.material:material
topic: BottomSheetDialog theme resolution (bottomSheetDialogTheme)
version: 1.13.0
fetched: 2026-08-08T00:00:00Z
official_docs: https://developer.android.com/reference/com/google/android/material/bottomsheet/BottomSheetDialog
---

# BottomSheetDialog theme resolution (Material 1.13.0)

## Question
Does `new BottomSheetDialog(Context)` need special theme handling? Does it pick up the `bottomSheetDialogTheme` attr automatically?

## Answer: YES — picked up automatically. No special handling required.

Verbatim from 1.13.0 source (`lib/java/com/google/android/material/bottomsheet/BottomSheetDialog.java`):

```java
public BottomSheetDialog(@NonNull Context context) {
  this(context, 0);
  initialize();
}

public BottomSheetDialog(@NonNull Context context, @StyleRes int theme) {
  super(context, getThemeResId(context, theme));
  // We hide the title bar for any style configuration. Otherwise, there will be a gap
  // above the bottom sheet when it is expanded.
  supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
  initialize();
}
```

```java
private static int getThemeResId(@NonNull Context context, int themeId) {
  if (themeId == 0) {
    // If the provided theme is 0, then retrieve the dialogTheme from our theme
    TypedValue outValue = new TypedValue();
    if (context.getTheme().resolveAttribute(R.attr.bottomSheetDialogTheme, outValue, true)) {
      themeId = outValue.resourceId;
    } else {
      // bottomSheetDialogTheme is not provided; we default to our light theme
      themeId = R.style.Theme_Design_Light_BottomSheetDialog;
    }
  }
  return themeId;
}
```

## Behavior
- `BottomSheetDialog(Context)` delegates to `BottomSheetDialog(context, 0)`.
- With themeId == 0, `getThemeResId` calls `context.getTheme().resolveAttribute(R.attr.bottomSheetDialogTheme, ...)`.
- If the context's theme defines `bottomSheetDialogTheme` → that resource is used (your `Theme.MaterialComponents.BottomSheetDialog` subclass).
- If not defined → falls back to `Theme_Design_Light_BottomSheetDialog`.

## Implications for the planned conversion
- The plan (`new BottomSheetDialog(ctx)` where ctx's theme sets `bottomSheetDialogTheme`) works exactly as intended — the custom dialog theme subclass is picked up automatically.
- You do NOT need to pass the theme explicitly to the constructor; but you may pass it explicitly via `BottomSheetDialog(Context, @StyleRes int theme)` if you ever need per-instance override.
- BottomSheetDialog always requests `Window.FEATURE_NO_TITLE` (title bar hidden for any style).
