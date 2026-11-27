from pathlib import Path
p=Path('joybrush/tools/paper/build_launch_library.py'); s=p.read_text(encoding='utf-8')
s=s.replace("('crumpled','Crumpled paper',.55,.035),('crumpled_flattened','Flattened crumpled paper',.12,.012)", "('crumpled','Crumpled paper',.75,.035),('crumpled_flattened','Flattened crumpled paper',.16,.012)")
s=s.replace('span,pitch=1.5,relief=.8','span,pitch=1.5,relief=1.6')
s=s.replace("s,h=surface('papyrus_strips','Papyrus strips',source_height(directory,'papyrus_strips'),.45,pitch=1.2,rotate=False)", """# Flattened plant strips, not cylindrical reeds: broad pressed tops, narrow joins.
    strip_h=source_height(directory,'papyrus_strips')
    pressed=norm(np.minimum(strip_h,.63)+.12*np.maximum(strip_h-.63,0))
    long_fibres=.7*fibres(975,1500,140,.45,angle=0)+.3*fibres(976,1200,130,.45,angle=np.pi/2)
    s,h=surface('papyrus_strips','Papyrus strips',np.clip(.94*pressed+.06*long_fibres,0,1),.38,pitch=1.2,rotate=False)""")
p.write_text(s,encoding='utf-8')
p=Path('joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/paper/LaunchMaterialsTest.kt'); s=p.read_text(encoding='utf-8').replace('HexTile.sampleLook(look,x,y,l.hexTexels','HexTile.sampleLook(look,x/l.texelPx,y/l.texelPx,l.hexTexels').replace('HexTile.sampleSurface(surface,x,y,s.hexTexels','HexTile.sampleSurface(surface,x/s.texelPx,y/s.texelPx,s.hexTexels'); p.write_text(s,encoding='utf-8')
p=Path('joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/paper/PaperTestSetTest.kt'); s=p.read_text(encoding='utf-8'); s=s.replace('PaperRaster.render(paper, null, tex, RectPx(0, 0, 128, 128))','PaperRaster.render(paper, look.file?.let { texture(SurfaceEntry("preview", "Preview", it, 512, 1f, .099f, 180f, false)) }, tex, RectPx(0, 0, 128, 128))'); s=s.replace('PaperRaster.render(p, null, tex, rect)', 'PaperRaster.render(p, p.look?.file?.let { texture(SurfaceEntry("preview", "Preview", it, 512, 1f, .099f, 180f, false)) }, tex, rect)'); p.write_text(s,encoding='utf-8')
