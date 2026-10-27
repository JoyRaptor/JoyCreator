# JB-8.02 — Procreate `.brush` / `.brushset` import

- [x] Read the spec (R40), siblings (AbrImport, MypaintImport), ImportSupport, orphans
- [x] Read the four orphans, judge soundness
- [x] Fix PngChunks.kt budgets (self-contained, IHDR type)
- [ ] Rewrite KeyedArchive.kt bplist reader + add XmlPlist
- [ ] Write ProcreateImport.kt (object + zip reader + pack notes + per-brush builder)
- [ ] KeyedArchiveTest.kt (commonTest)
- [ ] InflateTest.kt (jvmTest)
- [ ] ProcreateImportTest.kt (jvmTest)
- [ ] Build green, mutation evidence, SHA-256 restore
