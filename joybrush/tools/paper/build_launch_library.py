"""Deterministic periodic launch materials. Geometry and pigment are separate channels.
No AI generation or third-party imagery. NumPy/Pillow only.
Run after prepare_test_surfaces.py: python build_launch_library.py <candidate-surfaces-dir>
"""
from pathlib import Path
import sys, json
import numpy as np
from PIL import Image
from pack import pack, slopes
from prepare_test_surfaces import pulp

N=512
Y,X=np.mgrid[:N,:N].astype(float)
ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'assets/paper'
OUT=Path(__file__).resolve().parent/'out'
TAU=2*np.pi

def norm(a):
    span=a.max()-a.min()
    return (a-a.min())/span if span else np.full_like(a,.5)

def blur(a,sigma):
    fy=np.fft.fftfreq(N)[:,None]; fx=np.fft.rfftfreq(N)[None,:]
    return np.fft.irfft2(np.fft.rfft2(a)*np.exp(-2*np.pi**2*sigma**2*(fx*fx+fy*fy)),s=(N,N))

def formation(seed,sigma):
    return norm(blur(np.random.default_rng(seed).standard_normal((N,N)),sigma))

def fibres(seed,count=3500,length=32,width=.55,angle=None):
    rng=np.random.default_rng(seed)
    x,y=rng.uniform(0,N,(2,count)); a=rng.uniform(0,TAU,count) if angle is None else rng.normal(angle,.09,count)
    acc=np.zeros((N,N)); w=rng.uniform(.3,1.,count)
    for step in range(length):
        np.add.at(acc,(y.astype(int)%N,x.astype(int)%N),w)
        a+=rng.normal(0,.015,count); x+=np.cos(a); y+=np.sin(a)
    return norm(blur(acc,width))

def folded_sheet(seed=921):
    """Continuous piecewise planar sheet on a periodic jittered triangular mesh.
    Face normals jump at real straight creases, with narrow pressed crease troughs.
    Vertex heights, jitter and shared crease lines wrap on the same torus.
    """
    rng=np.random.default_rng(seed); cells=8; pitch=N/cells
    jitter=rng.uniform(-.28,.28,(cells,cells,2)); z=rng.normal(0,1,(cells,cells))
    field=np.full((N,N),np.nan); crease=np.zeros((N,N))
    def vertex(i,j):
        q=jitter[j%cells,i%cells]
        return np.array([(i+q[0])*pitch,(j+q[1])*pitch]),z[j%cells,i%cells]
    for j in range(-1,cells+1):
        for i in range(-1,cells+1):
            v=[vertex(i,j),vertex(i+1,j),vertex(i+1,j+1),vertex(i,j+1)]
            triangles=[(0,1,2),(0,2,3)] if (i+j)%2 else [(0,1,3),(1,2,3)]
            for tri in triangles:
                pts=np.array([v[k][0] for k in tri]); heights=np.array([v[k][1] for k in tri])
                lo=np.floor(pts.min(0)).astype(int); hi=np.ceil(pts.max(0)).astype(int)
                yy,xx=np.mgrid[lo[1]:hi[1]+1,lo[0]:hi[0]+1].astype(float)
                dx=xx+.5-pts[0,0]; dy=yy+.5-pts[0,1]
                matrix=np.column_stack((pts[1]-pts[0],pts[2]-pts[0]))
                inv=np.linalg.inv(matrix)
                b=inv[0,0]*dx+inv[0,1]*dy; c=inv[1,0]*dx+inv[1,1]*dy; a=1-b-c
                inside=(a>=-1e-8)&(b>=-1e-8)&(c>=-1e-8)
                values=a*heights[0]+b*heights[1]+c*heights[2]
                # Barycentric edge distance = weight * altitude, in pixels.
                double_area=abs(np.linalg.det(matrix))
                altitudes=np.array([double_area/np.linalg.norm(pts[2]-pts[1]),
                    double_area/np.linalg.norm(pts[2]-pts[0]),double_area/np.linalg.norm(pts[1]-pts[0])])
                dist=np.minimum(np.minimum(a*altitudes[0],b*altitudes[1]),c*altitudes[2])
                trough=np.exp(-.5*(dist/1.25)**2)
                yi=yy[inside].astype(int)%N; xi=xx[inside].astype(int)%N
                field[yi,xi]=values[inside]-.12*trough[inside]
                crease[yi,xi]=trough[inside]
    if np.isnan(field).any(): raise ValueError('fold mesh contains uncovered pixels')
    return norm(field),crease

def source_height(directory,id):
    with Image.open(Path(directory)/(id+'.png')) as image: h=np.asarray(image,dtype=float)/65535
    return h.reshape(N,2,N,2).mean((1,3))

def colour(rgb): return np.array([int(rgb[k:k+2],16) for k in (1,3,5)],float)

def main(directory):
    OUT.mkdir(exist_ok=True)
    catalogue=json.loads((ASSETS/'catalogue.json').read_text(encoding='utf-8'))
    manifest={}; notes=[]
    def surface(id,name,h,span,pitch=2.,rotate=True,relief=.6):
        hb=np.rint(255*(.5+span*(np.clip(h,0,1)-.5))).astype(np.uint8)
        rgba,_=pack(hb,.099); file='surface_'+id+'.png'
        Image.fromarray(rgba).save(ASSETS/file,optimize=True)
        entry=dict(id=id,name=name,file=file,size=N,texelPx=pitch,slopeRange=.099,hexTexels=180,rotatable=rotate,relief=relief)
        catalogue['surfaces']=[s for s in catalogue['surfaces'] if s['id']!=id]+[entry]
        # Authoring records are diagnostic, never loaded by the app.
        manifest[id]=dict(heightStd=float(hb.std()),heightSpan=span,pitch=pitch,rotation=rotate)
        notes.append(f'- `{id}`: periodic numerical physical model; authored height span {span}, slopeRange0.099, pitch{pitch}, rotation{rotate}.')
        return entry,hb.astype(float)/255
    def look(id,name,base,s,structure=None,strength=.035,pigment=None,pigment_strength=0.):
        rgb=np.broadcast_to(colour(base),(N,N,3)).copy()
        if structure is not None: rgb*=1+strength*(structure-structure.mean())[...,None]
        if pigment is not None: rgb*=1+pigment_strength*(pigment-pigment.mean())[...,None]
        rgb=np.rint(np.clip(rgb,0,255)).astype(np.uint8)
        file='look_'+id+'.png'; Image.fromarray(rgb).save(ASSETS/file,optimize=True)
        mean='#'+''.join(f'{int(v):02X}' for v in np.rint(rgb.mean((0,1))))
        entry=dict(id=id,name=name,base=mean,file=file,mean=mean,texelPx=s['texelPx'],hexTexels=s['hexTexels'],rotatable=s['rotatable'],defaultSurface=s['id'])
        catalogue['looks']=[l for l in catalogue['looks'] if l['id']!=id]+[entry]
        manifest[id]=dict(surface=s['id'],structuralStrength=strength,pigmentStrength=pigment_strength)
    # Give the existing test set a structural pigment channel over its actual shipped heights.
    for id in ('canvas_linen','canvas_cotton_duck','canvas_jute','pulp_factory','pulp_handmade','pulp_artisan'):
        s=next(s for s in catalogue['surfaces'] if s['id']==id)
        old=next(l for l in catalogue['looks'] if l['defaultSurface']==id)
        with Image.open(ASSETS/s['file']) as image: h=np.asarray(image)[:,:,2].astype(float)/255
        look(old['id'],old['name'],old['base'],s,h,.075 if id.startswith('canvas') else .035)
    # Broad facets and narrow real crease troughs; same mesh, physically flattened.
    folds,creases=folded_sheet()
    tooth=formation(922,.6)-.5
    for id,name,span,crease_strength in [('crumpled','Crumpled paper',.55,.035),('crumpled_flattened','Flattened crumpled paper',.12,.012)]:
        s,h=surface(id,name,np.clip(folds+.025*tooth,0,1),span,pitch=1.5,relief=.8)
        look(id,name,'#F1F0EC',s,h,.035,pigment=creases,pigment_strength=-crease_strength)
    # Rice opacity/filaments are independent of gentle relief; dark inclusions are never pits.
    rice=fibres(930,1800,55,.65); rice_relief=.7*formation(931,4)+.3*fibres(932,3200,18,.5)
    s,h=surface('rice_fibres','Rice paper fibres',rice_relief,.14,relief=.5)
    look('rice_cool','Rice paper — cool white','#F1F3F2',s,h,.012,pigment=rice,pigment_strength=.19)
    look('rice_cream','Rice paper — cream','#F0EBDF',s,h,.012,pigment=rice,pigment_strength=.19)
    # Plant chunks are pigment; fibre/tooth formation is physical.
    sugar=fibres(940,5000,17,.55)
    s,h=surface('sugarcane_pulp','Sugarcane pulp',.55*formation(941,5)+.45*sugar,.38,relief=.6)
    bits=formation(942,.8)**5
    look('sugarcane','Sugarcane paper','#DDD2C6',s,h,.06,pigment=bits,pigment_strength=-.4)
    s,h=surface('construction_pulp','Construction paper pulp',.65*formation(944,2)+.35*fibres(945,4000,13,.5),.48)
    look('construction_tan','Tan construction paper','#C4AF92',s,h,.08,pigment=formation(946,1),pigment_strength=.05)
    # Chalkboards share evenly distributed micro-tooth. Old chalk dust is visible residue, not a crater.
    s,h=surface('chalk_grit','Chalkboard grit',.65*formation(950,.55)+.35*formation(951,1.8),.52,pitch=.75,relief=.45)
    for id,name,base,seed in [('chalkboard_black','Black chalkboard','#242B2D',952),('chalkboard_green','Green chalkboard','#29443D',953)]:
        dust=.6*formation(seed,18)+.4*blur(fibres(seed+10,400,110,1.6,angle=.1),3)
        look(id,name,base,s,h,.16,pigment=dust,pigment_strength=.42)
    factory=next(s for s in catalogue['surfaces'] if s['id']=='pulp_factory')
    with Image.open(ASSETS/factory['file']) as image: h=np.asarray(image)[:,:,2].astype(float)/255
    look('blueprint','Blueprint','#245B7C',factory,h,.045,pigment=formation(961,35),pigment_strength=.07)
    s,h=surface('parchment_skin','Parchment skin',.7*formation(970,2.5)+.3*formation(971,12),.16,relief=.45)
    look('parchment','Aged parchment','#DCD1BC',s,h,.03,pigment=formation(972,22),pigment_strength=.17)
    s,h=surface('papyrus_strips','Papyrus strips',source_height(directory,'papyrus_strips'),.45,pitch=1.2,rotate=False)
    strip=(np.cos(TAU*X*9/N)+np.cos(TAU*Y*8/N))*.25+.5
    look('papyrus','Papyrus','#CEBE9C',s,h,.1,pigment=strip,pigment_strength=.08)
    for id,name,span,pitch,base in [('silk','Silk',.16,.7,'#EAEAE8'),('fabric','Woven fabric',.42,1.,'#DFE0DE')]:
        s,h=surface(id,name,source_height(directory,id),span,pitch=pitch,rotate=False,relief=.5)
        look(id,name,base,s,h,.08)
    s,h=surface('cement','Cement',.55*formation(980,.8)+.3*formation(981,3)+.15*formation(982,12),.7,pitch=1.25)
    look('cement','Cement','#B8B9B6',s,h,.15,pigment=formation(983,17),pigment_strength=.055)
    (ASSETS/'catalogue.json').write_text(json.dumps(catalogue,indent=2)+'\n',encoding='utf-8')
    (OUT/'material_authoring.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    sources=ASSETS/'SOURCES.md'
    text=sources.read_text(encoding='utf-8').split('\n## Launch material authoring')[0]
    sources.write_text(text+'\n## Launch material authoring\n\nAll additions are original numerical geometry/pigment, deterministic seeds in `build_launch_library.py`. Rebuild after the small test-set tool with the same candidate-surfaces directory. Creases are a wrapped jittered triangle sheet, full and flattened from the SAME geometry and smaller physical height/slope span. Rice opacity and plant chunks are separate pigment fields. Chalk dust is residue over common grit. Woven materials use the original geometry candidates. Every pictured look shares size, physical pitch, hex size, rotation and hash with its default surface; no baked light/shadows in albedo.\n\n'+'\n'.join(notes)+'\n',encoding='utf-8')
    budget=sum(p.stat().st_size for p in ASSETS.glob('*.png'))
    if budget>25*1024*1024: raise ValueError('paper library exceeds 25 MiB')
    print(len(catalogue['surfaces']),'surfaces',len(catalogue['looks']),'looks',budget,'bytes')

if __name__=='__main__': main(sys.argv[1])

