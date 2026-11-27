from pathlib import Path
from PIL import Image
p=Path('joybrush/assets/paper/surface_canvas_linen.png')
p.with_suffix('.original').write_bytes(p.read_bytes())
Image.new('RGBA', (512,512), (127,127,128,64)).save(p)
