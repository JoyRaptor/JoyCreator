from pathlib import Path
import numpy as np
from PIL import Image
import json
root=Path('joybrush/assets/paper'); c=json.loads((root/'catalogue.json').read_text())
for s in c['surfaces']:
    a=np.asarray(Image.open(root/s['file']))[:,:,2].astype(float)
    ordinary=np.concatenate((np.diff(a,axis=0).ravel(),np.diff(a,axis=1).ravel()))
    edge=np.concatenate((a[0]-a[-1],a[:,0]-a[:,-1]))
    print(s['id'], 'std',round(a.std(),2),'wrap95/interior95',round(np.percentile(abs(edge),95)/max(1,np.percentile(abs(ordinary),95)),2),'maxwrap',abs(edge).max())
