import numpy as np,wave,sys
w=wave.open(sys.argv[1]);x=np.frombuffer(w.readframes(w.getnframes()),np.int16).astype(float);sr=w.getframerate()
print("dur",len(x)/sr)
for t0 in (0.5,2,4):
    s=x[int(t0*sr):int((t0+1)*sr)];S=np.abs(np.fft.rfft(s*np.hanning(len(s))));f=np.fft.rfftfreq(len(s),1/sr)
    out={}
    for n,fr in (("440",440),("1320",1320),("2000",2000)):
        m=(abs(f-fr)<15);out[n]=round(20*np.log10(S[m].max()/ (S.max()+1e-9)+1e-9),1)
    print(t0,"rms",round(np.sqrt((s**2).mean())),out)
