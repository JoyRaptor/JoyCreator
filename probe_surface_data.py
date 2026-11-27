from pathlib import Path
import sys
import numpy as np
from PIL import Image
sys.path.insert(0, str(Path('joybrush/tools/paper').resolve()))
from pack import height_bytes, pack
folder=Path('C:/+Projects/Screenrecorder/FadCam/joybrush/tools/paper/candidates/surfaces')
for name in ('crumpled_01.png','canvas_linen.png'):
    with Image.open(folder/name) as image:
        old=np.asarray(image.convert('L'))
        full=height_bytes(image)
        subtle=height_bytes(image,0.2)
    full_pack,_=pack(full,0.099)
    flat_pack,_=pack(subtle,0.099)
    full_s=np.abs(full_pack[:,:,:2].astype(float)-127).mean()
    flat_s=np.abs(flat_pack[:,:,:2].astype(float)-127).mean()
    print(name, 'old_L_std=',round(float(old.std()),6),'height_std=',round(float(full.std()),6),
          'flattened_height_ratio=',round(float(subtle.std()/full.std()),6),
          'flattened_slope_ratio=',round(float(flat_s/full_s),6))
