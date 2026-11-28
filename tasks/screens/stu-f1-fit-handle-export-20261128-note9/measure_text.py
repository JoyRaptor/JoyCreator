import cv2,numpy as np,sys
c=cv2.VideoCapture(sys.argv[1]);c.set(cv2.CAP_PROP_POS_MSEC,1500);ok,f=c.read()
h,w=f.shape[:2];print("size",w,h)
g=cv2.cvtColor(f,cv2.COLOR_BGR2GRAY);m=g>200
ys,xs=np.where(m);print("white bbox x %.3f..%.3f  y %.3f..%.3f  width %.3f"%(xs.min()/w,xs.max()/w,ys.min()/h,ys.max()/h,(xs.max()-xs.min())/w))
cv2.imwrite(sys.argv[2],f)
