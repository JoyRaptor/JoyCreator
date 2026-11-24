# STU-7 blind motion blur review

The C7 visual gate uses at least 20 distinct shots. Use the agreed corpus of authored artwork and footage from the STU-7 spec. Prepare matched PNG crops with the same source frame time, shutter, framing, dimensions, and output colour space. The comparison can use the converged authored-art reference or an exposure integrated from sharp high-speed footage. Include the higher-risk cases from §8, especially cuts, transparent edges, crossings, reversals, and difficult filmed motion. Keep the capture recipe and exact candidate commit with the private evidence. Remove any identifying text rendered into the crop itself.

Create a CSV outside the repo with this header. Paths may be absolute or relative to the CSV.

```csv
shot_id,studio,reference
lamp-pan-01,crops/lamp-pan-studio.png,crops/lamp-pan-reference.png
```

Before preparing a packet, make a private `corpus.json` beside the CSV. It locks the candidate to
one exact commit, records how every reference was produced, and accounts for every fail-first scene
in §8. `kind` is `authored` or `footage`. Tags are checked literally. A shot may carry several
honest tags. The accepted tags are `still`, `translation`, `zoom`, `spoked-wheel`, `crossing`,
`bezier-arm`, `tracked-hand-arc`,
`alternate-drawing`, `flipbook-hold`, `fence-pan`, `person-crossing`, `flash`, `low-light`,
`dolly-zoom`, `whip-pan`, `hard-cut`, `dissolve`, `retime-2x`, `reverse`, `freeze`, `speed-ramp`,
`loop`, `ping-pong`, `step-key`, `highlight-streak`, `transparent-black`, and
`transparent-white`.

```json
{
  "schema": 1,
  "candidate_commit": "0123456789abcdef0123456789abcdef01234567",
  "shots": [
    {
      "shot_id": "lamp-pan-01",
      "kind": "footage",
      "tags": ["highlight-streak", "low-light"],
      "reference_recipe": "240 fps frames 2401-2408, 30 fps, 180 degree shutter"
    }
  ]
}
```

The real file has one record for every CSV row. A passing audit requires both authored and footage
shots and declares coverage of all §8 fixture tags. Confirm that each crop shows its declared cases.
Keep the JSON report with the private evidence.

```text
python3 tools/motionref/blind_review.py audit /evidence/shots.csv /evidence/corpus.json > /evidence/corpus-audit.json
```

Create the packet in a new directory outside the repo.

```text
python3 tools/motionref/blind_review.py prepare /evidence/shots.csv /evidence/c7-blind --corpus /evidence/corpus.json
```

Share only the generated `review/` folder. Keep `private-key.json` and the source CSV private. The packet omits the original filenames, randomizes shot order, balances which side contains Studio, and removes common PNG text, EXIF, and timestamp tags. Keep the private seed to reproduce the assignments for the same corpus. Open the generated `review/index.html` on the review device and download the completed ratings before closing the page.

After the blind review, score the returned ratings with the private key.

```text
python3 tools/motionref/blind_review.py score /evidence/c7-blind/private-key.json /evidence/motion-blur-ratings.json
```

A pass requires at least 80% of Studio crops judged comparable or better and zero severe Studio cut ghosts or alpha fringes. The command prints the counts and affected shot IDs. Exit status 0 means the visual threshold passed, 1 means a completed review failed it, and 2 means the inputs could not be prepared or scored. A failed preparation can leave a partial output directory. Correct the input and use a fresh directory. Full device, playback, and owner approvals are recorded separately.

With `--corpus`, preparation requires a passing coverage audit and includes the audit in the private
key and score report. The candidate commit and coverage remain private. A packet prepared without
the corpus is for calibration.

Run the tooling checks with `python3 -B tools/motionref/test_blind_review.py`. The tests use generated one-pixel examples. The gate needs actual review evidence.
