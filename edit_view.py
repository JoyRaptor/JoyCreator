from pathlib import Path
p=Path('joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt')
s=p.read_text(encoding='utf-8')
s=s.replace('import android.opengl.GLES30','import android.opengl.GLES30\nimport cc.joycreator.joybrush.androidkit.io.PaperResources\nimport cc.joycreator.joybrush.core.paper.ResolvedPaper\nimport cc.joycreator.joybrush.core.paper.PaperState\nimport cc.joycreator.joybrush.core.doc.Paper')
s=s.replace('''    @Volatile var paperArgb: Int = 0xFFFFFFFF.toInt()
        set(v) { field = v; requestRender() }''','''    @Volatile private var documentPaperState = Paper()
    @Volatile var paper: ResolvedPaper = PaperState.resolve(documentPaperState, PaperResources.catalogue)
        set(v) {
            field = v
            documentPaperState = documentPaperState.copy(textureId=v.surface?.id,lookId=v.look?.id,
                textureScale=v.scale,color=paperHex(v.baseArgb),tint=if(v.tintSet)paperHex(v.baseArgb) else null,
                show=v.show,bite=v.bite,light=v.light)
            requestRender()
        }
    var paperArgb: Int
        get() = paper.baseArgb
        set(v) { paper = paper.copy(baseArgb=v or 0xFF000000.toInt(),tintSet=true) }

    /** A missing catalogue id is reported but never prevents opening the drawing. */
    @Volatile var paperWarnings: List<String> = emptyList()
        private set''')
s=s.replace('''                engine.draw(viewW, viewH, drawView.docToClip(viewW, viewH), paperArgb)''','''                engine.setPaper(paper)
                paperWarnings = (PaperState.problems(documentPaperState,PaperResources.catalogue) + engine.paperWarnings).distinct()
                engine.draw(viewW, viewH, drawView.docToClip(viewW, viewH), paperArgb)''')
s=s.replace('val paper = paperArgbOf(doc.paper.color)','val resolved = PaperState.resolve(doc.paper,PaperResources.catalogue)')
s=s.replace('''            paperArgb = paper
            reportHistory()''','''            paper = resolved
            documentPaperState = doc.paper
            engine.setPaper(resolved)
            paperWarnings = PaperState.problems(doc.paper,PaperResources.catalogue) + engine.paperWarnings
            paperWarnings.forEach { android.util.Log.w("JoyBrushPaper",it) }
            reportHistory()''')
s=s.replace('''        if (doc.paper.textureId != null) {
            return "this drawing has a paper texture, which this screen cannot show yet"
        }
''','')
s=s.replace('paper = base.paper.copy(color = paperHex(paperArgb)),','paper = documentPaperState,')
s=s.replace('CanvasSnapshot.merge(retainedContents, JbContents(doc = doc, tiles = tiles, strokes = emptyMap(), thumbnailPng = null))','CanvasSnapshot.merge(retainedContents, JbContents(doc = doc, tiles = tiles, strokes = emptyMap(), thumbnailPng = null), documentPaperState)')
s=s.replace('several boards, a board that is not a canvas, a paper texture, or a tile','several boards, a board that is not a canvas, or a tile')
p.write_text(s,encoding='utf-8')
