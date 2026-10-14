# Note9 recorded probe — October 14, 2026

The Android replay probe passed on source `3993de74ac6bd11eead6b631158b207d758cc455` for `joybrush.ink` and `joybrush.watercolour`. Each run covered one prepared 256×256 tile and the ordered operations `normal`, `normal`, `erase`, `behind`, `multiply`, and `screen`. The expected identity and ordered results matched exactly at RGBA8: zero differing channels or pixels and zero maximum byte delta. The intentionally wrong-order control differed from the correct result in both runs, so the fixture detects ordering errors.

Each run reported 12 GL error checks with zero errors, 4 framebuffer checks with zero incomplete framebuffers, and 8 feedback checks with zero failures. Both reported complete cleanup. The probe exercised the Android replay adapter and ordered arithmetic on the first actual tile only. It did not exercise live pen capture, the live recorded drawing pipeline, appearance quality, performance, or actual Select interaction.

The built APK receipt identifies source `3993de74ac6bd11eead6b631158b207d758cc455` and SHA-256 `a67b5c411ffc318f68a3c679bf0e6c981f0f7b3c045fa1c9b6acbef58fec2b40`. The later install receipt reports that same installed SHA and names `joybrush.watercolour`; this verifies the installed package bytes against the built APK receipt. The initial ink installation's package update-time change was verified by the install script before its receipt was overwritten by the later watercolour installation receipt. The surviving receipt therefore reports `updateTimeChanged: false` for the later installation; no earlier timestamp is claimed here.

Two fresh unsaved debug bench runs executed. The ink bench returned to Home, which the fresh gate confirmed before the watercolour dispatch; the watercolour log includes the runner's `done` callback. The watercolour Working receipt says the Working file was present and its hash unchanged. The ink run did not check Working, so no ink Working-file claim is made.

The asset receipt reports five packaged WC shader entries and confirms that packaged `joybrush/shaders/jb_recorded_compose.frag` matches its source. It does not establish a count of twenty-five WC assets.

Evidence parsed from `recorded-phone-ink-3993de74.log`, `recorded-phone-probe.log`, `apk-3993de74/receipt.json`, `apk-3993de74/asset-receipt.json`, `recorded-phone-install-receipt.json`, and `recorded-phone-working-receipt.json`. The receipts are time-sensitive snapshots; the install receipt was overwritten after the initial ink installation.
