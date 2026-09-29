# D.02c — One colour history for the whole app, and the recent-colours bar (shared in `:studiokit`)

| | |
|---|---|
| **Tier** | T2-V (T1 review) + T3 phone check |
| **Status** | see ROADMAP.md |
| **Depends on** | D.02 (`:studiokit` exists, `ColorPickerDialog` lives there) |
| **Owner area** | NEW `studiokit/src/main/java/com/fadcam/ui/faditor/tools/ColorRecents.java`, NEW `studiokit/.../tools/RecentColorsBar.java`; EDIT `studiokit/.../tools/ColorPickerDialog.java` (its private recents code → calls `ColorRecents`; no visible change); EDIT `joybrush-android/.../JoyBrushActivity.kt` (show the bar); NEW `tools/jvm-harness/ColorRecentsTest.java` + `run-colorrecents.sh` |
| **Estimated size** | ~260 lines + ~100 lines of tests |

## Goal (owner, 2026-09-29 — "a must have")
"Infinite Painter has swatches of previous colours in a discreet place by your tools for a quick
return to a previous colour. The swatches look like a thin bar that gets longer and divides with the
colours in it by history up to a max amount. Tapping that bar gives you back the colour you tapped."

And the owner's standing rule: build it ONCE, shared, so improving it in one place improves it
everywhere. The Studio's colour picker already keeps app-wide RECENTS (8, in SharedPreferences
`faditor_color_picker` / `recents`). This spec makes that history a small shared class, so the
picker's recents row, Joy Brush's bar and any future Studio bar are the same list.

## Contract
```java
package com.fadcam.ui.faditor.tools;

/** The app-wide colour history. Most recent first. Thread: main. */
public final class ColorRecents {
    public static final int MAX = 12;
    public static List<Integer> get(Context ctx);            // copy, most recent first, ≤ MAX
    public static void push(Context ctx, int argb);          // move-to-front; dedupe; trim to MAX
    public interface Listener { void onRecentsChanged(); }
    public static void addListener(Listener l);               // weak-free: caller removes it
    public static void removeListener(Listener l);
}

/** A thin bar of recent colours that grows with the history and divides into equal segments. */
public final class RecentColorsBar extends View {
    public interface OnPick { void onPick(int argb); }
    public void setOnPick(OnPick p);
    public void setOrientation(boolean vertical);             // vertical beside a side rail, else horizontal
}
```

## Decisions
0. `ColorPickerDialog.loadRecents(ctx)` and `pushRecent(ctx, c)` are public today; keep them as
   one-line delegates to `ColorRecents` (their callers keep compiling). `loadRecents` still returns at
   most 8 (the row's slots), `ColorRecents.get` up to 12.
1. **Storage stays where it is** (`faditor_color_picker` / `recents`, comma-separated
   `Integer.toHexString` ARGB, most recent first) and keeps its format, so the
   owner's existing recents appear in Joy Brush on day one. `MAX` rises from 8 to 12; the picker's
   recents ROW still shows its first 8 (its layout is unchanged).
2. **Dedupe:** a colour equal to one already in the list (same RGB, alpha ignored) moves to the front
   rather than being added twice.
3. **When a colour is pushed:** when a stroke is FINISHED with it (not on every live picker change —
   dragging across the hue ring must not flood the history), when an eyedropper result is taken, and
   when the picker's Set is pressed (as today). Joy Brush calls `push` from its stroke-finished hook.
4. **The bar:** thickness 6 dp (a hair, "discreet"), rounded ends; its LENGTH grows with the count —
   16 dp per colour — up to 12 × 16 dp, then stays that long; it is divided into equal segments, most
   recent at the end nearest the tools. The current colour's segment has a 1 dp `Studio.INK` outline.
   Touch target: the bar's hit area is 32 dp thick even though it draws 6 dp. Tap a segment → `onPick`.
   Press and slide along the bar → the segment under the finger highlights and is picked on lift
   (so a thin bar is still easy to hit). Colours from `Studio` tokens only for chrome (D.01 rule).
5. Listeners: the bar redraws when the history changes from anywhere (picker, eyedropper, another
   screen). `ColorPickerDialog` notifies after its own pushes.
6. **Joy Brush placement (for now):** a horizontal bar directly above the colour pill; JB-2.01's real
   chrome will move it beside the tools (same View, re-hosted).

## Tests (`ColorRecentsTest`, plain Java with a fake preferences map — no Android runtime)
Put the list logic in a pure static helper (`ColorRecents.pushInto(List<Integer>, int)`) so it is
testable: push 13 distinct → 12, oldest gone; push an existing colour → moves to front, no duplicate;
alpha-only difference → treated as the same colour; round-trip of the stored string format with an
old 8-colour value from the current app.

## Verification
- Harness test green; watcher compiles `:studiokit`, `:app`, `:joybrush-android` green.
- **Owner check:** in the Studio, open a colour picker — the recents row shows the same colours as
  before. In Joy Brush, draw with three colours → the bar shows three segments, newest by the pill;
  tap the oldest → the next stroke uses it. Pick a colour in the Studio → it appears on Joy Brush's bar.

## Do not
No visible change to the Studio's picker except recents now also include colours used in Joy Brush.

## Definition of done
Tests + watcher green (paste) · owner check noted · commit `D.02c: shared colour recents and bar` ·
ROADMAP row → 🟧 Built.

## Questions
