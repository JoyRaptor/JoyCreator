# Watercolour tween replay: admitted envelope and rigid-pose proposal

## Source map and consumer requirement

Isolated branch `codex/tween-wash-fidelity-20261026`, base `f7ed34f16`.
New core entry: `WatercolourTween.betweenForReplay(a, b, t, frozenBrushLookup)`.
No persisted fields, codec, shader, GPU or engine/UI source is changed. Legacy `Tween.between`
is unchanged. The new entry is not yet wired to TweenScene or live/export GPU consumers.

**Consumer must supply the immutable drawing/snapshot frozen brush closure and use this entry
for replay.** The current BrushLibrary is not that closure. Both endpoint definitions must be
present, valid and have IDs matching their records; inconsistent same-ID definitions or different
engines are refused. Same-engine distinct ordinary Ink presets retain T7's source brush behavior.
Brush IDs and the presence of washFrame do not identify watercolour: the preset engine does.

## Implemented metadata envelope (source; not yet executed)

- Existing captured-source refusal remains unchanged. Fractions must be finite in0..1.
- Ordinary Ink retains existing math. Watercolour with an unchanged wash frame also retains it.
- For watercolour with different frames, both frames must have literal affine bottom row0,0,1
  and identical linear2x2 coefficients. Their translation-column difference is the proposed move.
- Both endpoints must use the same frozen brush definition/id and source recipe. The target must
  equal actual `LineTransform.apply(A, translate(dx,dy), KEEP)` except permitted colour, width,
  lock and group metadata. Raw sample/sensor/time bits and smoothing/zoom bits are checked.
- Intermediate geometry AND wash frame use the same LineTransform translation convention.
  Colour/width use the existing tween math. At0 and1, admitted translation endpoints are exact
  source/target values, including a legacy null wash frame. Intermediate lock/group stay source's.
- A differing-frame rotation/scale/shear/projective map, nonrigid edit or inconsistent recipe is
  explicitly refused with DocException. This is a bounded translation implementation, not complete
  support for watercolour vector tween transformations. Refusal must reach the UI/export caller;
  do not substitute static frames, discard the wash frame or fall back to anchored sampling.

Metadata tests cannot establish native pixels, grain/pooling quality, GL context safety or latency.
Actual engine replay, cache identity and save/reopen/context loss remain separate acceptance gates.

## Later rigid-pose proposal — for lead review, not implemented

For a compatible rigid move, derive a relative pose H between the source and target wash frames,
validate it against the same endpoint line transform, and interpolate the pose. Compose
`wash(t) = wash(A).then(H(t))`; do not interpolate the nine wash-matrix coefficients.

- Require an orientation-preserving unit-scale rotation plus translation; reject shear,
  perspective and reflection. Keep the existing group pivot semantics (JB-5.40 T3/T4).
- For nonzero rotation, solve `(I - R) p = translation` for the shared pivot, and interpolate the
  shortest-arc angle about p. A pure translation is its own branch. A180° tie needs an explicit
  deterministic sign rule agreed with existing Tween; do not let floating noise choose direction.
- An already transformed source frame stays intact: transport it through the relative pose,
  rather than decomposing/rebuilding its internal texture axes. Target compatibility must be
  established before admission. Nonrigid or incompatible target frames remain explicit refusals.
- Uniform scale, if later admitted, requires log-scale interpolation and the same source zoom
  convention as geometry, plus native wash sampling and brush-width checks. It is outside this slice.

### Why matrix lerp fails at180°

Rotation about the origin has matrices I and diag(-1,-1,1). Their halfway coefficient average is
diag(0,0,1), singular: every point collapses to the origin and there is no inverse wash frame.
At an arbitrary pivot the same average collapses to that pivot. The intended halfway pose is a
90° rotation, determinant1, preserving every distance. Translation/rotation endpoint tests plus
GPU wash-local sampling checks must demonstrate the pose approach before broader activation.

## Remaining work

- [ ] Execute the new bounded metadata checks on exact committed source under lead admission.
- [ ] Review the rigid-pose proposal and its numerical acceptance/tie policies separately.
- [ ] Bind replay-aware scene/engine/export consumers with immutable source/asset/context leases.
- [ ] Prove native rendered parity and failure/refusal UX; no native look claim from this source.
