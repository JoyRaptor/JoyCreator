# Held patches (Lead)

`JoyBrushActivity_savequeue.patch` — the JB-0.08b / JB-2.15 screen wiring (LEAD_RULINGS R26): every save goes
through `core/io/SaveQueue`; the `saveOwed` flag and the `saving` gate are gone; a copy no longer clears the
"unsaved changes" counter. It COMPILES (checked in a throwaway module with android.jar + androidx.core) and the
queue itself is tested (13 tests). It is held only because the JB-1.21 builder was mid-edit on the same file.

Apply after JB-1.21 is committed: `git apply tasks/joybrush/held/JoyBrushActivity_savequeue.patch`
(the Lead does this — agents never resolve conflicts). Delete this patch and this note once it has landed.
