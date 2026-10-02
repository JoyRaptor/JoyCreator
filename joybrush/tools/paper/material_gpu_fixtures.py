"""Export measured CPU pixels from the Kotlin-generated contact sheet, not a second renderer."""
from pathlib import Path
import json
import numpy as np
from PIL import Image
root=Path('joybrush/assets/paper'); out=Path('joybrush/tools/paper/out')
c=json.loads((root/'catalogue.json').read_text(encoding='utf-8'))
sheet=np.asarray(Image.open(out/'launch-contact.png').convert('RGB'))
fixtures=[]
for row,l in enumerate(c['looks']):
    s=next((s for s in c['surfaces'] if s['id']==l['defaultSurface']),None) if l.get('defaultSurface') else None
    rgb=sheet[row*156:row*156+16,216:216+16,:]
    expected=np.dstack([rgb,np.full((16,16),255,dtype=np.uint8)]).ravel().tolist()
    first=np.asarray(Image.open(root/s['file']))[0,0].tolist() if s else None
    fixtures.append(dict(look=l,surface=s,expected=expected,surfaceFirst=first))
(out/'material-gpu-fixtures.json').write_text(json.dumps(fixtures),encoding='utf-8')
