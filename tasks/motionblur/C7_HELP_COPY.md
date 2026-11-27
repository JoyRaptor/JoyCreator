# STU-7 Motion Blur help and Joybot copy

This copy covers the rigid-item Motion Blur sheet, grounded in the landed C2 export and C3 preview. The help topic and Joybot tip landed at `51b838070` behind the same `studio_motion_blur` launch key. [Studio and Joybot](https://github.com/JoyRaptor/JoyCreator/actions/runs/45917532951) passed for the exact help-only source. The host tests verified both gate states. The deployed sheet and help remain hidden.

## Motion Blur help

**“How do I use Motion Blur?”**

In Studio, move the playhead to a part where your clip, picture, title or sprite moves. Select the item and open **Motion Blur** in the tool strip. Tick **Enable motion blur for this project** and **Blur this item’s transform**.

Start with **Natural**. Adjust **Shutter angle** for the length of the streak, **Shutter timing** for where it falls around the playhead, and **Mix** for how much blur you see. Tap **Inspect Frame** to see the current frame with your draft settings. Tap **Apply** to keep them.

This sheet follows the position, scale and rotation you animate on the item. The note in the sheet tells you what can stay sharp during live preview for the selected item.

## Joybot tip

> Did you know Crisp in Motion Blur gives a fast-moving title a shorter streak? Try Inspect Frame to check the edges before you tap Apply.

Use this tip for Studio. The existing picker favours Studio for people who use that wing. In `TipBook`, supply the text after “Did you know” because the greeting adds those words.

## Registration and checks

- Help topic ID `studio_motion_blur`, with lookup phrases such as “motion blur”, “shutter angle”, “shutter timing” and “inspect frame”. Use route `studio` and tool ID `motion_blur` so **Show me how** points at the actual button. Leave the action field empty for this copy. A project-changing Joybot tool is a separate validated EditScript feature.
- Tip ID `studio_motion_blur`, wing `STUDIO`, route `studio`. Use the same feature key for the toolbar, HelpBook and TipBook. HelpBook currently checks `help_<id>` and the route. TipBook checks routes and wings. Each needs an explicit check of `LaunchList.hidden("studio_motion_blur")` for this entry, with the launch list loaded before Joybot's first lookup or greeting. The launch asset remains the single setting for visibility.
- In the host help and tip tests, set the feature key and check that the lookup cannot return this topic and the picker cannot select this tip. Clear the key and check that the same question returns this topic and tool ID. Cover a plain “motion blur” question, which also matches the generic effects and keyframe entries. Check that the tip fallback respects the feature key.
- When released, confirm the control labels and selection behavior in Studio. Check the gated and open toolbar, **Show me how**, and the tip with an offline Joybot session. Update the STATE help mapping after it is wired.

The coverage note and actual control texts are in [strings_studio_motion.xml](../../app/src/main/res/values/strings_studio_motion.xml). The [tool registry](../../app/src/main/java/com/fadcam/ui/faditor/tools/FaditorToolRegistry.java) defines the launch key and tool ID. The [C2 shutter receipt](C2_SHUTTER_CONTROLS_REPORT.md) and [C3 timing receipt](C3_TIMING_REPORT.md) cover specific Snapdragon fixtures.

Add footage, puppets, avatars and effect-layer instructions when their actual entry points and coverage are verified. Filmed-video help should explain that the effect adds blur and that existing blur, compression, reflections, repeating textures, and hidden surfaces can limit the result.
