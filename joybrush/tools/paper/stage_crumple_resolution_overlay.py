"""Stage existing three-resolution crumpled-paper bytes; never edits a live catalogue."""
import argparse,hashlib,json,shutil
from pathlib import Path
from PIL import Image

def stage(root,output):
 output.mkdir(parents=True,exist_ok=False); assets=output/'assets';assets.mkdir()
 fragment=dict(format='joybrush-papers',version=1,surfaces=[],looks=[]); files=[]
 for size in (2048,1024,512):
  source=root/'assets' if size==2048 else root/f'profile-firm-{size}'
  receipt=root/'receipt_expanded-firm.json' if size==2048 else source/'profile-receipt.json'
  r=json.loads(receipt.read_text());s=dict(r['surface']);look=dict(r['look']);label=f'Crumpled paper {size}px'
  s['name']=label;look['name']=label
  assert len(label)<=40 and s['size']==size and s['id']==look['defaultSurface']
  fragment['surfaces'].append(s);fragment['looks'].append(look)
  for name in (s['file'],s['fluid'],look['file']):
   src=source/name;dst=assets/name;shutil.copyfile(src,dst)
   sha=hashlib.sha256(src.read_bytes()).hexdigest();assert hashlib.sha256(dst.read_bytes()).hexdigest()==sha
   with Image.open(dst) as im: mode=im.mode;dims=im.size;fmt=im.format
   files.append(dict(file=name,source=str(src.resolve()),sha256=sha,bytes=dst.stat().st_size,mode=mode,width=dims[0],height=dims[1],format=fmt))
 (output/'catalogue-additions.json').write_text(json.dumps(fragment,indent=2),encoding='utf-8')
 report=dict(files=files,encodedBytes=sum(x['bytes'] for x in files),entries=3,modeledGpuRgba8WithMipsBytes=60555252,modeledDecodedBaseRgbaBytes=45416448,limits='Models exclude existing cache, other app resources, driver allocation/padding and CPU upload/mipmap temporaries. Not measured residency.',addedRuntimeAssetBytes=0,apkDeltaMeasured=False)
 assert report['encodedBytes']==7662021
 (output/'overlay-manifest.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
 return report
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('output',type=Path);a=p.parse_args();r=stage(a.root,a.output);print(json.dumps({'files':len(r['files']),'bytes':r['encodedBytes']}))
