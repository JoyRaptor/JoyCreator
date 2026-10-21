# D7 whole-stroke ending integration

Status: proposed held adapters; no canonical Lead source edited. D7 tests are pending.

`ShapedReplay` repeats the same response, smoother, placed dabs and seeded resource selection in two bounded passes. The first measures exact Float chord length without retaining a dab list. The second applies the existing taper and dash equations, scatter and width scale, admitting each final dab before retaining it. Callers supply four explicit work allowances; refusal returns no partial replay.

Use the six-argument `InkReplay.replayAdmitted` overload only for a shaped stamp recipe. Existing overloads retain their refusal instead of allocating an unbounded shaped list. The primary image set and seed-specific dual mask must already match the frozen recipe.

Apply `UNIBRUSH_D7_LINE.patch`, then `UNIBRUSH_D7_LINE_TESTS.patch` to the Lead-owned LineShaper. These preserve quad/profile/fade fields during procedural densification and avoid inventing extra image, dual or stochastic-colour stamps. They do not change historical procedural interpolation arithmetic.

`StrokeLiftTransaction` supplies an ownership protocol with pending tests, not an installed canvas adapter. The host must supply private preparation and its CURRENT atomic artwork/history publisher. A local native `endStroke` is insufficient. Capture contact/document/context/cel/owner identities at pen-down; recheck them before and after preparation. A refusal preserves the preview; cancellation drops only the private preview. Failed staging/preview retirement remains explicitly retryable without another commit. Preparation must clean its own partial allocations when it throws. Publication cannot throw after ownership has transferred.

Outstanding: execute core and combined adapter tests, bind the actual current Lead publication owner, then prove pen-up raster/editable parity and one undo entry on Note9. No phone/performance/owner-acceptance claim.
