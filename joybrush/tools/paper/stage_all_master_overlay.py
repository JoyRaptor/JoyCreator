"""Stage verified full-resolution evaluation masters without changing their bytes."""
import argparse,hashlib,json,shutil
from pathlib import Path
from PIL import Image

SPECIAL={
 'expanded-handled':'Test Gently handled white paper',
 'expanded-light':'Test Light crumple flattened paper',
 'expanded-firm':'Crumpled paper 2048px',
 'owner-1':'Test Cardboard supplied',
 'owner-2':'Test Black chalkboard supplied',
 'owner-4':'Test Blue construction paper',
 'owner-7':'Test Tan rice paper supplied',
 'owner-8':'Test White rice paper supplied',
 'owner-9':'Test Thai paper supplied 1',
 'owner-10':'Test Thai paper supplied 2',
}
def label(job):
 if job['id'] in SPECIAL:return SPECIAL[job['id']]
 if job['id'].startswith('earlier-'):
  title=job['id'][8:].replace('_',' ').title()
  return 'Test Earlier '+title
 return 'Test '+job['label']

def stage(batch,output):
 jobs=json.loads((batch/'jobs.json').read_text(encoding='utf-8'))
 previous=json.loads((batch/'evaluation-pack/aggregate-receipt.json').read_text(encoding='utf-8'))
 verified={f['file']:f for row in previous['materials'] for f in row['files']}
 assert len(jobs)==45 and len(verified)==135
 output.mkdir(parents=True,exist_ok=False);assets=output/'assets';assets.mkdir()
 additions=dict(format='joybrush-papers',version=1,surfaces=[],looks=[]);files=[]
 for job in jobs:
  receipt=batch/'evaluation-pack'/('receipt_'+job['id']+'.json')
  r=json.loads(receipt.read_text(encoding='utf-8'));name=label(job)
  assert 1<=len(name)<=40,(name,len(name))
  surface=dict(r['surface'],name=name);look=dict(r['look'],name=name)
  assert surface['size']==2048 and surface['packed'] is False and look['defaultSurface']==surface['id']
  additions['surfaces'].append(surface);additions['looks'].append(look)
  for filename in [surface['file'],surface['fluid'],look['file']]:
   src=batch/'evaluation-pack/assets'/filename;dst=assets/filename;old=verified[filename]
   digest=hashlib.sha256(src.read_bytes()).hexdigest()
   assert digest==old['sha256'] and src.stat().st_size==old['bytes']
   with Image.open(src) as im:
    assert im.mode==old['mode'] and im.size==(old['width'],old['height'])
   shutil.copyfile(src,dst);assert hashlib.sha256(dst.read_bytes()).hexdigest()==digest
   files.append(dict(old,source=str(src.resolve()),selectionId=job['id'],stagedFile='assets/'+filename))
 for kind in ['surfaces','looks']:
  assert len({x['id'] for x in additions[kind]})==45
  assert len({x['name'].casefold() for x in additions[kind]})==45
 assert len(files)==135 and sum(f['bytes'] for f in files)==205722497
 (output/'catalogue-additions.json').write_text(json.dumps(additions,indent=2),encoding='utf-8')
 manifest=dict(materials=45,files=files,totalEncodedBytes=205722497,transforms='None; verified original baked bytes copied',merge='Reuse existing eval_expanded_firm entry/files and its installed label; append remaining44 pairs only. Preserve reduced p1k/p512 variants and all shipping entries/defaults.',scope='Temporary full-resolution evaluation only; physical feel/GL/seam acceptance open; not final shipping library.')
 (output/'overlay-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
 return manifest
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('batch',type=Path);p.add_argument('output',type=Path);a=p.parse_args();r=stage(a.batch,a.output);print(json.dumps(dict(materials=r['materials'],files=len(r['files']),bytes=r['totalEncodedBytes'])))
