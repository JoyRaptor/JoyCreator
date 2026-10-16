# Captured-domain copy device evidence — 2026-10-15

This records the private `recordedCapturedCopyProbe` run for source `249cdd9d08842b3aa4c70a02afc9bb525d287948`. The artifact receipt identifies the installed APK as 167,572,025 bytes with SHA-256 `2C0ECB39E3597773D624485DADEF9FACCB269DB7E6AB766FDE7A20F11EA659D6`.

Both full captured reports, their nine-row comparison arrays, the independent count JSON, and the working/install/artifact receipts were inspected. Both brushes compared the same nine tile keys (the comparison region, not the four-tile canonical source allocation):

`-8589934590, -8589934589, -8589934588, -4294967294, -4294967293, -4294967292, 2, 3, 4`.

Each row is labeled `placed-vs-independent-expected`, and all nine rows per brush report zero differing channels, zero differing pixels, and zero maximum byte delta. The reports also say the expected and placed key sets match (four tiles each), with no missing or extra keys. Across both brushes this is 18 exact full-tile rows, or 4,718,592 RGBA8 bytes (`18 × 256 × 256 × 4`). The aggregate report confirms zero different channels.

The control fields are nonzero and discriminate the wrong mappings. For `joybrush.ink`, wrong-offset control differs in 1,252 channels / 318 pixels and all-domain control in 1,739 channels / 437 pixels. For `joybrush.watercolour`, those controls differ in 5,581 channels / 2,196 pixels and 3,167 channels / 1,079 pixels respectively. Both reports show 14 feedback checks, zero feedback failures, 12 framebuffer checks, zero incomplete framebuffers, 17 GL error checks, and zero observed GL errors. The independent count JSON totals 24 external readbacks, 24 external framebuffer checks, 34 external GL checks, and 28 external feedback checks.

Both runs used fixture opacity 0.55. The ink fixture reports brush version 6 and size 6; the watercolour fixture reports version 10 and size 300. Their expected zero-alpha pixel counts are 588,954 and 587,691 respectively, but each reports `expectedWhollyTransparentTiles: 0`; the missing-transparent-key control is absent (`present: false`). Therefore the device evidence contains no fully transparent tile key and gives no transparent-key device coverage.

The working receipt says the working tree was present and unchanged, and all three named drawing archives were checked and unchanged. It captured the expected two reports. The install receipt says installation was requested and the update timestamp changed. Its installed SHA matches the APK artifact receipt. The source hash is the full 40-character commit above.

Scope is limited to a **private post-pool captured-domain integer texel copy diagnostic**. The report explicitly excludes source `Scene`, live capture, Select, save/recovery activation, and quality or performance claims. These results do not establish those behaviors.
