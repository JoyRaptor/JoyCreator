# STU-7 blind motion blur review

The C7 visual gate uses at least 20 distinct shots. Use the agreed corpus of authored artwork and footage from the STU-7 spec. Prepare matched PNG crops with the same source frame time, shutter, framing, dimensions, and output colour space. The comparison can use the converged authored-art reference or an exposure integrated from sharp high-speed footage. Include the higher-risk cases from §8, especially cuts, transparent edges, crossings, reversals, and difficult filmed motion. Keep the capture recipe and exact candidate commit with the private evidence. Remove any identifying text rendered into the crop itself.

Create a CSV outside the repo with this header. Paths may be absolute or relative to the CSV.

```csv
shot_id,studio,reference
lamp-pan-01,crops/lamp-pan-studio.png,crops/lamp-pan-reference.png
```

Create the packet in a new directory outside the repo.

```text
python3 tools/motionref/blind_review.py prepare /evidence/shots.csv /evidence/c7-blind
```

Share only the generated `review/` folder. Keep `private-key.json` and the source CSV private. The packet omits the original filenames, randomizes shot order, balances which side contains Studio, and removes common PNG text, EXIF, and timestamp tags. Keep the private seed to reproduce the assignments for the same corpus. Open the generated `review/index.html` on the review device and download the completed ratings before closing the page.

After the blind review, score the returned ratings with the private key.

```text
python3 tools/motionref/blind_review.py score /evidence/c7-blind/private-key.json /evidence/motion-blur-ratings.json
```

A pass requires at least 80% of Studio crops judged comparable or better and zero severe Studio cut ghosts or alpha fringes. The command prints the counts and affected shot IDs. Exit status 0 means the visual threshold passed, 1 means a completed review failed it, and 2 means the inputs could not be prepared or scored. A failed preparation can leave a partial output directory. Correct the input and use a fresh directory. Full device, playback, and owner approvals are recorded separately.

Run the tooling checks with `python3 -B tools/motionref/test_blind_review.py`. The tests use generated one-pixel examples. The gate needs actual review evidence.
