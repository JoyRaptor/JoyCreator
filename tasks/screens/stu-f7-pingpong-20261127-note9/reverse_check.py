import cv2,numpy as np,sys
c=cv2.VideoCapture(sys.argv[1]);fr=[]
while True:
    ok,f=c.read()
    if not ok:break
    fr.append(cv2.resize(cv2.cvtColor(f,cv2.COLOR_BGR2GRAY),(90,160)).astype(float))
print("frames",len(fr),"fps",c.get(cv2.CAP_PROP_FPS))
d=lambda a,b:round(float(np.abs(fr[a]-fr[b]).mean()),2)
print("580 vs 499:",d(580,499),"| 580 vs 40:",d(580,40),"| 580 vs 498:",d(580,498),"| 580 vs 500:",d(580,500))
for k in (545,560,580,600,640):
    mir=539-(k-540)
    print(k,"mirror",mir,"err",d(k,mir),"forward",k-540,"err",d(k,k-540))
# does return leg end where it began (continuity at 540)
print("539 vs 540:",d(539,540),"| 539 vs 541:",d(539,541))
