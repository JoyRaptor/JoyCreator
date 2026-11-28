# The room's look as Studio's sibling (AVA look, 11-28)

The owner (11-28), on Perform: "Random blank spots, terrible layout, emoji buttons, off spec round overs etc, dosnt look at all like
the studio". Pictures taken on the cloud emulator by `AvatarCardsTest` (every card of the four stages, the menu, the pin wizard, the
joystick sheet), halved and saved as JPEG.

| Folder | What | Tree |
|---|---|---|
| `before-phone/` | the room before (Pixel 4, portrait) | joy-creator `4c0a7945f` |
| `before-landscape/` | the room before (Pixel 4, landscape) | joy-creator `47fb6a6fa` |
| `studio-reference/` | Studio taken the same way (`explore_studio`, phone and tablet): its library and New Project sheet | joy-creator `47fb6a6fa` |
| `after-phone/` | after looks 1 to 3: Studio's icons, greys and faces | `3131dbf02` (landed in `30fcceab5`) |

The tablet set is owed: `AvatarCardsTest` froze on the tablet emulator about 6 s after the room opened, before its first picture
(run 46150222288); being looked into.

What changed (each its own commit):
1. **Face sheet**: the face's settings in a Studio sheet behind a Face settings chip; no half-width column beside the head pad.
2. **Icons**: the top bar, stage rail and take bar draw Studio's Material icons, borderless, in Studio's ink (no typed marks on grey
   filled circles); on = solid cyan (recording red) with the icon cut out. Ticks and the layers' eye are Studio icons too.
3. **Greys**: every zinc grey written as hex is now the Studio role it plays (`RAISED`, `PANEL`, `SURFACE`, `INK`, `LABEL`,
   `INK_DIM`, `INK_FAINT`, `INK_OFF`, `GUIDE`).
4. **Faces**: body text in Studio's body face, section labels mono and upper case as Studio's sheets, headings and the panel's
   name in its display face (`ui/RoomType`).

Still to do: the tablet set, sizes and spacing card by card against Studio's sheets, Perform trimmed to what performing uses.
