# Studio export of the transitions test project, Note 9 real GPU (11-27, hover-1 lane)
Project "Untitled 7" (two identical 18 s frame-counter clips, Radial transition on the cut), exported fresh as "Untitled 7 H1cutchecku.mp4": High quality, Original resolution, 14 s on the phone, "Export Complete 100%" (screenshot seen), 8,982,667 bytes.

**What the export's own trace shows** (`export_trace_untitled7.txt`): 3 export items, `startMs=0 durMs=17421` / `startMs=17421 durMs=600 [GlTransitionExportEffect, Presentation]` (the transition) / `startMs=18021 durMs=17421`, total 35,442 ms; hardware AVC decoder; audio 18.027 s of sound over 18.027 s of timestamps (+0.0000%); entered item 0 and item 2 at the expected composition times (0 ms, 18,021 ms); no error lines; committed.
**Frame decode (done after the lead's tip; the file is at `/storage/emulated/0/Joy Creator/Untitled 7 H1cutchecku.mp4`, shared storage: my first name search missed it because of the space and a `head` limit):** pulled to the PC, 720x1280, 30 fps, 1062 frames, 35.4 s, AAC audio 35.41 s; every frame decoded with OpenCV (`frame_check.json`).
- **No black frames:** frame brightness stays between 31 and 33 across the whole file (a black frame would read near 0).
- **No glitches:** frame-to-frame change has a median of 0.82 and no spike at all (threshold 25 or six times the median); across the transition window (frames 522 to 540) it stays between 0.6 and 2.0.
- **Time-aligned through the cut:** `cut_montage.png` (frames 516, 522, 528, 534, 540, 543): the first clip reads 17.200, 17.400, 17.600 s; the second clip then reads 0.367 s at 17.8 s of the export and carries on 0.600, 0.700: the incoming clip starts at 17.43 s as the 3-item plan says, nothing jumps or repeats. Frame 528 shows the transition's blend (the counter text is smeared).
- **Limit:** both clips are the same picture, so the Radial wipe itself cannot be seen to differ; this proves timing, no black frames and no glitches, not the wipe's look. Different clips would show that.

**Row marks from this export:**
- **Transition export (STU-1 "play and export it" row, STU-E3 transition piece): PASS for black frames, glitches and time alignment.** STU-E3's missing volume curve or fades on the transition piece was not examined (the audio runs 35.41 s with no gap).
- STU-C1 export vs preview for Bend / corner pin, STU-F1, STU-F6, STU-F7, STU-C6: not run in this session.
