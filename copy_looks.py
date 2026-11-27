from pathlib import Path
from PIL import Image
import json,re,shutil,sys,numpy as np
dest=Path('C:/+Projects/Screenrecorder/FadCam/joybrush/tools/paper/candidates')
(dest/'qa').mkdir(parents=True,exist_ok=True)
jobs=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
for j in jobs:
    matches=re.findall(r'C:\\Users\\[^ \r\n]*?exec-[a-f0-9-]+\.png',j['hint'])
    src=Path(matches[-1]); p=dest/'looks'/j['file']
    shutil.copy2(src,p)
    img=Image.open(p).convert('RGB');
    # Required 1024-square export normalization; preserve originals at their generated path.
    img=img.resize((1024,1024), Image.Resampling.LANCZOS)
    from PIL import ImageCms
    profile=ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
    img.save(p,icc_profile=profile)
    a=np.asarray(img).astype(float)
    repeat=Image.new('RGB',(img.width*2,img.height*2))
    for y in range(2):
        for x in range(2):repeat.paste(img,(x*img.width,y*img.height))
    repeat.thumbnail((1024,1024));repeat.save(dest/'qa'/f'{p.stem}_repeat.png')
    seams=[np.abs(a[:,0]-a[:,-1]).mean(),np.abs(a[0]-a[-1]).mean()]
    neighbours=[np.abs(np.diff(a,axis=1)).mean(),np.abs(np.diff(a,axis=0)).mean()]
    with (dest/'NOTES.md').open('a',encoding='utf-8') as f:
        f.write(f'\n### {p.name}\nBuilt-in imagegen, original generated material. Source study only; no reference pixels used. Size {img.size}; RGB PNG. Horizontal/vertical mean seam differences {seams}; ordinary neighbour differences {neighbours}. The 2x2 preview is in qa/{p.stem}_repeat.png; raw candidate needs the specialist’s taste and seam review.\n\nPrompt: {j["prompt"]}\n')
    print(p.name,img.size,'seam',*[round(s,3) for s in seams])
