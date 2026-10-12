# Atlas A1 checkpoint — October 12, Codex Lead

Base: origin/joy-creator f58bf202. Isolated worktree C:/Temp/jb-atlas-a1, branch media/atlas-a1.

The new MediaAtlasSlots page table and MediaAtlasResidency adapter keep the previous page visible until incoming load
and dirty-victim write-back succeed. One staging slot is charged to the explicit working budget. All stores share one
layer/cel/tile slot. Pinning protects dependencies; deterministic LRU only evicts an unpinned page.

Actual focused verification: Kotlin 2.2.0/JDK17 compiled both new production files and both test files; JUnit4 passed
12 tests (7 allocator, 5 fake backing). Compiler used a 384 MiB heap, test JVM 128 MiB; admission 3869 MiB free. Run serialized
under jb-gradle.lock, log C:/Temp/jb-lead-ops/atlas-a1-focused.log. The checker used the actual staged source and cached
project test dependencies. Tests cover failed load with corrupt invisible staging, failed atomic write-back, retry,
old-mapping visibility through callbacks, dirty state retention, pins, identity, token misuse and budget boundaries.

Actual module compilation: :androidkit:compileKotlin passed in 2m 23s, including :core:compileKotlinJvm. Offline,
single-use 768 MiB Gradle JVM, one worker, in-process Kotlin, serialized under jb-gradle.lock; log
C:/Temp/jb-lead-ops/atlas-a1-module-compile.log. No full suite, GL, device, crease removal or periodic-painting claim
follows from these checks.

The approved A1 contract is specs/MEDIA_ATLAS_A1_CONTRACT_20261012.md. A2–A6 still owe shader/large-window parity with
broken controls, a concrete transactional GPU/COW backing implementation and phone acceptance. Active wet pages cannot
be evicted through half-float backing without changing their trajectory. Pixel-space Tile wrapping and complete
neighbor admission are explicit later-slice requirements; this checkpoint does not wire new classes into live painting.
