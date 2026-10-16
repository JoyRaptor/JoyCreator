# Studio dialogs audit (haiku agent, static read, 2026-10-15)

- All 14 Studio bottom sheets use the Studio frame (CustomBottomSheetDialogTheme + SheetKit.install).
- 96 dialog/popup sites in FaditorEditorActivity: 93 are plain Material/AppCompat dialogs with no Studio look
  (86 MaterialAlertDialogBuilder, 5 AppCompat AlertDialog.Builder, 2 deprecated ProgressDialog).
- Cause: Theme.FadCam.FaditorEditor (styles.xml ~189-213) sets no materialAlertDialogTheme, so every dialog gets
  Material's default grey surface and default-primary buttons. The pin chooser (editor ~35790) also passes
  CustomBottomSheetDialogTheme, which replaces the Studio chain (purple CANCEL).
- No AlertDialog helper exists. Recommended: SheetKit.dialog(Context) returning a MaterialAlertDialogBuilder on a new
  ThemeOverlay.FadCam.Studio.MaterialAlertDialog, set as materialAlertDialogTheme on Theme.FadCam.FaditorEditor
  (one line restyles ~85 sites). Switch the 11 non-MDC sites by hand: editor 35790, 5651, 5688, 5764, 8765, 25618,
  ProgressDialog 20255 and 44280; ObjectMenuSheet 646 and 794; CanvasPickerBottomSheet 241.
- Owner decision: positive button colour on a plain dialog (proposal: flat ARMED text, INK_DIM for Cancel).
- Hard-coded colour: editor line ~5193 setActionTextColor(0xFF22D3EE) = Studio.ARMED.
- ~27 hard-coded visible strings (AddAsset, CanvasPicker, CropPicker, FaditorSettings, FilterBottomSheet, FlipPicker,
  ObjectMenuSheet "Got it", Speed/Volume "Reset", and ~11 in the editor).
- Unconfirmed on a device: the grey/purple look is inferred from the theme chain.
