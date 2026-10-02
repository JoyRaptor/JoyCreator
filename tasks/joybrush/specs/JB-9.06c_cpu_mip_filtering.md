# JB-9.06c CPU mip filtering
Fix measured screen/export drift (linen 17 bytes, silk 12) without metadata/shader changes. CPU uses a lazy RGBA8 mip chain and trilinear footprint 1 / texel pitch. Preserve magnification, wrapping, slope decoding and lattice. No hot files/schema changes. Gate: production shader against 21 Kotlin material fixtures, max 3 byte error and exact upload. Test minification, fractional LOD, wrapping and raster hook; removing footprint must fail. Never install on phone.
## Questions
General export compositing remains JB-9.06b, gated by Lead version 7.
