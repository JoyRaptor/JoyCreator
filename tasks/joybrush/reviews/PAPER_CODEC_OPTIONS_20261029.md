# Paper compression options — researched October29,2026

Research/benchmark proposal only. No codec/loader/shader/cache/paper defaults changed;
current three-resolution phone evaluation remains intact. Native app delivery permits
bundled decoders, but their APK bytes, CPU time and memory must count in the result.

## Measured starting point
First2048 crumple master: look903017bytes, height2369455, fluid553153, total3825625.
Height+fluid are76.4% of this set. Current GrainTextures uses BitmapFactory, RGBA8
upload and generated mips, with retained visited textures: smaller image files alone
do not shrink that resident GPU representation. Lossless data fidelity includes
slopes, height and independently filtered height-squared in alpha, not just visible
color. Preserving all raw channels, including RGB beneath alpha0, is required.

## Candidates and primary sources
- [Khronos KTX guide](https://raw.githubusercontent.com/KhronosGroup/3D-Formats-Guidelines/main/KTXDeveloperGuide.md): KTX2 container, Basis transmission formats and GPU block formats are distinct. Investigate for GPU-resident memory; native compressed upload/transcode/capability/fallback path required. Quality/runtime cost is not guaranteed by extension.
- [JPEG XL specification family](https://jpeg.org/jpegxl/): lossy, lossless and reversible JPEG recompression. Benchmark high-fidelity appearance and bit-exact map storage; target decoder/library overhead must be measured.
- [AVIF](https://aomedia.org/specifications/avif/) supports alpha. [Android formats](https://developer.android.com/media/platform/supported-formats) lists platform AVIF decoding fromAndroid12; Note9 targetAndroid10 needs a separately validated decoder path. Disk savings do not imply compressed GPU residency.
- [WebP encoder](https://developers.google.com/speed/webp/docs/cwebp): benchmark lossless with exact preservation of hidden RGB. Lossless defaults may otherwise discard RGB under alpha0, which is unsafe when alpha is numerical data.
- [JPEGli](https://github.com/google/jpegli): improved JPEG encoding; trial conventional8bit JPEG for appearance without a new app decoder. Fine fiber/grain preservation must win actual comparisons.
- [FLIF maintainers](https://github.com/FLIF-hub/FLIF) describe FLIF as superseded byFUIF thenJPEGXL; these may be the older obscure names the owner remembers.
- [JPEG AI](https://jpeg.org/jpegai/) and [neural material texture compression](https://github.com/NVIDIA-RTX/RTXNTC) are real research candidates. Require target-device inference/library/energy/quality measurements; no Note9 suitability or savings claimed here.

Procedural/SDF replacement does not preserve the approved photo-derived material
identity. Hybrid fine grain may be researched separately; neither infinite detail
nor zero computation/memory is established. Generic50–75% savings/instant loads
are not acceptance criteria and must not become marketing claims.

## Bounded next benchmark, not started
1. Freeze full-resolution representative crumple, woven and smooth materials; keep
   current approved masters and shipping IDs unchanged.
2. Compare appearance codecs at matched visible fiber/crease quality, and numerical
   map codecs with complete decoded-channel equality first. Any lossy GPU-map trial
   needs explicit error bounds plus fixed-seed stroke/GL/mip/save/export comparisons.
3. Measure actual APK delta INCLUDING decoder binaries, warm/cold Note9 loading,
   transient CPU RAM, resident GPU bytes, switch latency and thermal behavior.
4. Select format per layer with tested fallback/cache policy. Preserve high-res
   detail where supported; no blanket resolution reduction or unproved loader switch.
All encoding/build/device jobs require the existing per-job resource/phone gates.
