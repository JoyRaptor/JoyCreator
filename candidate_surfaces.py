"""Orthographic height renders of periodic parametric sheet/thread geometry, not noise textures."""
from pathlib import Path
import numpy as np
from PIL import Image
N=1024
out=Path('C:/+Projects/Screenrecorder/FadCam/joybrush/tools/paper/candidates')
(out/'surfaces').mkdir(parents=True,exist_ok=True)
(out/'geometry').mkdir(exist_ok=True)
(out/'qa').mkdir(exist_ok=True)
y,x=np.mgrid[:N,:N]/N
tau=2*np.pi
def periodic_delta(a,b): return (a-b+.5)%1-.5
def save(name,z,note):
    z=(z-z.min())/(z.max()-z.min())
    q=np.rint(z*65535).astype(np.uint16)
    Image.fromarray(q).save(out/'surfaces'/f'{name}.png')
    # Store the actual top surface mesh used for the orthographic height render.
    step=8; zs=z[::step,::step]; m=zs.shape[0]
    with (out/'geometry'/f'{name}.obj').open('w',encoding='utf-8') as f:
        f.write('# Periodic 3D surface; top-down orthographic z is the height texture\n')
        for j in range(m+1):
            for i in range(m+1): f.write(f'v {i/m:.8f} {j/m:.8f} {zs[j%m,i%m]*.02:.8f}\n')
        for j in range(m):
            for i in range(m):
                a=j*(m+1)+i+1;b=a+1;c=a+m+1;d=c+1
                f.write(f'f {a} {b} {d}\nf {a} {d} {c}\n')
    # Neutral preview and 2x2 wrap inspection; never used to pack/catalogue.
    preview=Image.fromarray((z*255).astype(np.uint8))
    repeat=Image.new('L',(N*2,N*2))
    for j in range(2):
        for i in range(2): repeat.paste(preview,(i*N,j*N))
    repeat.resize((1024,1024)).save(out/'qa'/f'{name}_repeat.png')
    edge=float(max(np.abs(z[:,0]-z[:,-1]).max(),np.abs(z[0,:]-z[-1,:]).max()))
    notes.append(f'- `{name}.png`: {note} 1024² unsigned 16-bit greyscale PNG; normalized top-surface Z. Periodic 128² mesh in geometry/{name}.obj. Largest wrap-neighbour height step {edge:.5f}.')
def woven(nx,ny,radius,twist,slub,seed):
    """Woven crossing centreline geometry; three cylindrical strands form a twisted yarn."""
    rng=np.random.default_rng(seed)
    z=np.full_like(x,-radius)
    for vertical,count,other in [(True,nx,ny),(False,ny,nx)]:
        along=y if vertical else x; across=x if vertical else y
        for i in range(count):
            phase=rng.uniform(0,tau)
            centre=i/count+.08/count*np.sin(tau*along+phase)
            # Alternating over/under crossings of the yarn centrelines.
            zc=.45*radius*np.cos(tau*other*along+np.pi*i+(0 if vertical else np.pi))
            thickness=radius*(1+.12*np.cos(2*tau*along+phase))
            # Slubs are toroidal ellipsoidal bulges, at explicit yarn positions.
            for _ in range(2):
                p=rng.uniform(); width=rng.uniform(.015,.04)
                thickness+=radius*slub*np.exp(-.5*(periodic_delta(along,p)/width)**2)
            for strand in range(3):
                angle=tau*twist*along+phase+strand*tau/3
                tube_centre=centre+.30*radius*np.cos(angle)
                tube_z=zc+.30*radius*np.sin(angle)
                tube_r=.73*thickness
                d=periodic_delta(across,tube_centre)
                cap=np.sqrt(np.maximum(0,tube_r*tube_r-d*d))
                z=np.maximum(z,np.where(np.abs(d)<=tube_r,tube_z+cap,-radius))
            # One small wrapped knot at a selected position per several yarns.
            if i%7==0:
                p=rng.uniform(); da=periodic_delta(along,p)
                dc=periodic_delta(across,centre)
                ell=1-(dc/(radius*1.4))**2-(da/(radius*2.2))**2
                z=np.maximum(z,np.where(ell>0,zc+radius*np.sqrt(np.maximum(0,ell)),-radius))
    return z
notes=[]
for name,args in [('canvas_linen',(28,25,.011,31,.18,701)),('canvas_cotton_duck',(18,16,.018,23,.38,702)),('canvas_jute',(11,10,.030,17,.75,703)),('silk',(40,34,.008,43,.07,704)),('fabric',(22,20,.013,29,.28,705))]:
    save(name,woven(*args),'Analytic 3D yarn geometry: alternating over/under centrelines, three helical cylindrical strands, periodic elliptical slubs and small knots; orthographic maximum-Z ray intersections.')
for variant in (1,2):
    # A sheet folded along periodic crease curves, then flattened by compressing Z.
    # Each ridge is a geometric fold cross-section (piecewise triangular), not value noise.
    z=np.zeros_like(x)
    folds=[(3,1,.16,.4),(1,4,.27,.7),(5,-2,.43,1.1),(-2,3,.64,.6),(2,5,.83,.9)]
    for i,(a,b,phase,amp) in enumerate(folds):
        coord=(a*x+b*y+phase+variant*.117+.045*np.sin(tau*(b*x-a*y)))%1
        ridge=1-2*np.abs(coord-.5)
        z+=amp*(ridge**(4 if variant==1 else 7))
    # Flattened crumples retain sharp crease lines but have broad relaxed facets.
    z=np.tanh(z*(.75 if variant==1 else 1.15))
    save(f'crumpled_{variant:02}',z,'Periodic folded-sheet mesh: five intersecting curved triangular crease families, broad facets, compressed Z to model flattening; variant '+str(variant)+'. Geometric construction, not a cloth-dynamics simulation.')
# Papyrus: crossed flattened stalk-strip layers with fibrous ridges following each strip.
z=np.zeros_like(x)
for vertical,count in [(True,9),(False,8)]:
    across=x if vertical else y; along=y if vertical else x
    strip=across*count
    face=np.sqrt(np.maximum(0,1-((strip%1-.5)/.49)**2))
    stalk=.04*np.cos(tau*(across*count*17+.2*np.sin(tau*along)))
    layer=.15+face*.14+stalk*.035
    z=np.maximum(z,layer+(0 if vertical else .06))
save('papyrus_strips',z,'Two crossed layers of flattened elliptical stalk-strip geometry; narrow longitudinal fibre ridges, upper strips offset in Z; rotation must stay off.')
with (out/'NOTES.md').open('a',encoding='utf-8') as f:
    f.write('\n## JB-9.10 geometry candidates, 2026-10-01\n\nRaw candidates only. No packing, selection or catalogue additions. Rendered by candidate_surfaces.py in the JB-9.10 worktree.\n\n'+'\n'.join(notes)+'\n')
print('8 surfaces saved; 16-bit greyscale, 1024x1024; meshes and 2x2 checks saved.')
