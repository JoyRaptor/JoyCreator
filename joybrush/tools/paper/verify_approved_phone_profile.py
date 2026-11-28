"""Protected runner target: derive one profile and verify serialized channels."""
import argparse, hashlib, json
from pathlib import Path
import numpy as np
from PIL import Image
from approved_phone_profile import derive, surface_profile

if __name__ == '__main__':
 p=argparse.ArgumentParser();p.add_argument('master_root',type=Path);p.add_argument('receipt',type=Path);p.add_argument('output',type=Path);p.add_argument('--size',type=int,choices=(512,1024),required=True);a=p.parse_args()
 r=derive(a.master_root,a.receipt,a.output,a.size)
 master=json.loads(a.receipt.read_text()); s=r['surface']; look=r['look']; ratio=master['surface']['size']//a.size
 decoded=np.asarray(Image.open(a.output/s['file']))
 original=np.asarray(Image.open(a.master_root/master['surface']['file']))
 expected,_=surface_profile(original,ratio,master['surface']['slopeRange'])
 assert s['packed'] is True and decoded.shape==(a.size,a.size,4)
 assert Image.open(a.output/s['file']).mode=='RGBA'
 assert np.array_equal(decoded,expected),'Serialized packed channels changed'
 assert s['texelPx']==look['texelPx'] and s['hexTexels']==look['hexTexels']
 assert abs(s['fluidTexelPx']*s['fluidHexTexels']-s['texelPx']*s['hexTexels'])<1e-8
 assert Image.open(a.output/s['fluid']).size==(a.size//4,a.size//4)
 assert Image.open(a.output/ look['file']).size==(a.size,a.size)
 assert Image.open(a.output/s['fluid']).mode=='RGBA'
 inputs=[a.receipt,Path(__file__),Path(__file__).with_name('approved_phone_profile.py'),Path(__file__).with_name('pack.py')]+[a.master_root/master[k][f] for k,f in [('surface','file'),('surface','fluid'),('look','file')]]
 r['inputs']=[dict(path=str(x.resolve()),sha256=hashlib.sha256(x.read_bytes()).hexdigest()) for x in inputs]
 r['decodedAssertions']={'packedRgbaExact':True,'independentSecondMomentRetained':True,'physicalPitchesMatched':True,'dimensionsMatched':True,'androidDecodeVerified':False}
 (a.output/'profile-receipt.json').write_text(json.dumps(r,indent=2),encoding='utf-8')
 print(json.dumps({'size':a.size,'bytes':r['encodedBytes'],'assertions':r['decodedAssertions']}))
