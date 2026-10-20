# Held D4 integration patch

Apply/reconcile UNIBRUSH_D4_LEAD.patch only in the Lead lane, after source review. It is based
on 107748a78 and passes `git apply --check` against the untouched Lead files in codex/unibrush.
It has not been applied or compiled in a combined app. Follow the no-conflict-resolution rule.

The patch wires the frozen primary/secondary palette and codec v5 capture, supplies fixed RGB
to StampPass, creates per-stroke RGB moment targets, draws MRT, binds commit moments and resets
the selector on every legacy commit. All companion names/bytes join the protected, cold-load,
promotion and working inventories; end/cancel deletes current names, context loss abandons them.
After colour dynamics, mask dabs convert to the canvas's byte-domain Rec.709 gray.

StampColorTargets is a new owned helper, covered by four JVM tests for format latching, exact
memory limits, transactional allocation/init failures, alias refusal and context abandonment.
Actual engine attachment, context-loss and replay publication acceptance remain Lead checks.
The existing replay reservation of 32 bytes/source texel is conservative for R16F mask + RGBA16F
moments + RGBA8 unit output (14 bytes); actual companion storage is additionally inventoried.
The live companion allowance uses boardSnapshotAllowanceBytes and refuses before allocation.

Do not lift the image-tip or line-shaping guards with this patch. D5/D7 supply their resource
and field-preservation adapters separately. No phone/install/main merge has been performed.
