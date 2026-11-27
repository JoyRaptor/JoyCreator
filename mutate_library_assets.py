from pathlib import Path
from PIL import Image
import numpy as np
root=Path('joybrush/assets/paper')
files=['look_canvas_linen.png','surface_crumpled_flattened.png','surface_cement.png','surface_sugarcane_pulp.png','catalogue.json']
for file in files:
    path=root/file; path.with_suffix('.original').write_bytes(path.read_bytes())
image=np.asarray(Image.open(root/files[0]))
Image.fromarray(np.roll(image,7,axis=1)).save(root/files[0])
(root/files[1]).write_bytes((root/'surface_crumpled.png').read_bytes())
import sys
sys.path.insert(0,'joybrush/tools/paper')
from pack import pack
h=np.asarray(Image.open(root/files[2]))[:,:,2].copy(); h[:,0]=0
rgba,_=pack(h,.099); Image.fromarray(rgba).save(root/files[2])

# Valid heights with erased slope response: catches both byte twin and inert-direction guard.
path=root/'surface_sugarcane_pulp.png'
a=np.asarray(Image.open(path)).copy(); a[:,:,:2]=127; Image.fromarray(a).save(path)
# Remove one required choice and corrupt only the declared mean, keeping schema/shape valid.
import json
path=root/'catalogue.json'; catalogue=json.loads(path.read_text(encoding='utf-8'))
catalogue['looks']=[l for l in catalogue['looks'] if l['id']!='blueprint']
for l in catalogue['looks']:
    if l['id']=='rice_cool': l['mean']='#010101'
path.write_text(json.dumps(catalogue,indent=2)+'\n',encoding='utf-8')
