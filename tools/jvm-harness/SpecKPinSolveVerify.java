import com.fadcam.ui.faditor.transform.TransformQuad;

/**
 * SPEC K — verify the IMPLICIT pivot solve (the fix) round-trips exactly.
 * Replica of the proposed TransformQuad.solvePinForQuad + host read math.
 * Must be ALL GREEN; the old explicit version (SpecKPinRoundTripTest) is red.
 */
public class SpecKPinSolveVerify {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static void pivotWeights(float u, float v, float[] o) {
        o[0] = (1f - u) * (1f - v); o[1] = u * (1f - v);
        o[2] = u * v;               o[3] = (1f - u) * v;
    }

    static boolean isFlat(float[] off) {
        for (int i = 0; i < 8; i++) if (Math.abs(off[i]) > 1e-5f) return false;
        return true;
    }

    static float pivDx(float w, float px, float py, float[] pins) {
        if (isFlat(pins)) return (px - 0.5f) * w;
        float[] ww = new float[4]; pivotWeights(px, py, ww);
        return ww[0] * (-w/2f + pins[0]*w) + ww[1] * (w/2f + pins[2]*w)
             + ww[2] * (w/2f + pins[4]*w) + ww[3] * (-w/2f + pins[6]*w);
    }

    static float pivDy(float h, float px, float py, float[] pins) {
        if (isFlat(pins)) return (py - 0.5f) * h;
        float[] ww = new float[4]; pivotWeights(px, py, ww);
        return ww[0] * (-h/2f + pins[1]*h) + ww[1] * (-h/2f + pins[3]*h)
             + ww[2] * (h/2f + pins[5]*h) + ww[3] * (h/2f + pins[7]*h);
    }

    static class Pose {
        float cx, cy, w, h, th, pivx = 0.5f, pivy = 0.5f, smx = 1f, smy = 1f;
        float[] off = new float[8];
    }
    static Pose copy(Pose p) {
        Pose q = new Pose();
        q.cx=p.cx; q.cy=p.cy; q.w=p.w; q.h=p.h; q.th=p.th;
        q.pivx=p.pivx; q.pivy=p.pivy; q.smx=p.smx; q.smy=p.smy; q.off=p.off.clone();
        return q;
    }
    static boolean neutral(Pose p) {
        return p.pivx == 0.5f && p.pivy == 0.5f && isFlat(p.off);
    }
    static float[] foldedCentre(Pose p) {
        if (neutral(p) || p.th == 0f) return new float[]{p.cx, p.cy};
        double rad = Math.toRadians(p.th);
        float c=(float)Math.cos(rad), s=(float)Math.sin(rad);
        float dX=pivDx(p.w,p.pivx,p.pivy,p.off), dY=pivDy(p.h,p.pivx,p.pivy,p.off);
        float pvx=p.cx+dX, pvy=p.cy+dY, vx=p.cx-pvx, vy=p.cy-pvy;
        return new float[]{pvx+vx*c-vy*s, pvy+vx*s+vy*c};
    }
    static float[] readQuad(Pose p) {
        float[] cf=foldedCentre(p);
        double rad=Math.toRadians(p.th);
        float cs=(float)Math.cos(rad), sn=(float)Math.sin(rad);
        float[] bx={p.cx-p.w/2f,p.cx+p.w/2f,p.cx+p.w/2f,p.cx-p.w/2f};
        float[] by={p.cy-p.h/2f,p.cy-p.h/2f,p.cy+p.h/2f,p.cy+p.h/2f};
        float[] out=new float[8];
        for(int i=0;i<4;i++){
            float px=bx[i]+p.off[i*2]*p.w, py=by[i]+p.off[i*2+1]*p.h;
            float dx=p.smx*(px-p.cx), dy=p.smy*(py-p.cy);
            out[i*2]=cf[0]+cs*dx-sn*dy; out[i*2+1]=cf[1]+sn*dx+cs*dy;
        }
        return out;
    }
    /** Recover stable pose centre C using OLD pins (C never moves in a distort gesture). */
    static float[] recoverPoseCentre(Pose p) {
        float[] cf=foldedCentre(p);
        boolean folded=!neutral(p)&&p.th!=0f;
        if(!folded) return new float[]{cf[0],cf[1]};
        double radU=Math.toRadians(-p.th);
        float uc=(float)Math.cos(radU), us=(float)Math.sin(radU);
        double radP=Math.toRadians(p.th);
        float pc=(float)Math.cos(radP), ps=(float)Math.sin(radP);
        float dX=pivDx(p.w,p.pivx,p.pivy,p.off), dY=pivDy(p.h,p.pivx,p.pivy,p.off);
        float pvx=cf[0]+pc*dX-ps*dY, pvy=cf[1]+ps*dX+pc*dY;
        float dx=cf[0]-pvx, dy=cf[1]-pvy;
        return new float[]{pvx+uc*dx-us*dy, pvy+us*dx+uc*dy};
    }
    /** IMPLICIT solve for new pins given pose centre C. */
    static boolean solvePin(Pose p, float[] qc, float[] quad8, float[] out) {
        float w=p.w,h=p.h,th=p.th;
        double radI=Math.toRadians(-th);
        float ci=(float)Math.cos(radI), si=(float)Math.sin(radI);
        // A_i = M.Ri.(Q_i-C) - b_i
        float[] ax=new float[4], ay=new float[4];
        float[] bx={-w/2f,w/2f,w/2f,-w/2f}, by={-h/2f,-h/2f,h/2f,h/2f};
        for(int i=0;i<4;i++){
            float qx=quad8[i*2]-qc[0], qy=quad8[i*2+1]-qc[1];
            float rx=ci*qx-si*qy, ry=si*qx+ci*qy;
            ax[i]=p.smx*rx-bx[i]; ay[i]=p.smy*ry-by[i];
        }
        float[] ww=new float[4]; pivotWeights(p.pivx,p.pivy,ww);
        float sAx=ww[0]*ax[0]+ww[1]*ax[1]+ww[2]*ax[2]+ww[3]*ax[3];
        float sAy=ww[0]*ay[0]+ww[1]*ay[1]+ww[2]*ay[2]+ww[3]*ay[3];
        float dFx=(p.pivx-0.5f)*w, dFy=(p.pivy-0.5f)*h;
        // K = M.(Ri-I)
        float k00=p.smx*(ci-1f), k01=p.smx*(-si);
        float k10=p.smy*(si),    k11=p.smy*(ci-1f);
        float m00=1f+k00, m01=k01, m10=k10, m11=1f+k11;
        float det=m00*m11-m01*m10;
        if(!Float.isFinite(det)||Math.abs(det)<1e-9f) return false;
        float rx=dFx+sAx, ry=dFy+sAy;
        float dx=(rx*m11-ry*m01)/det, dy=(m00*ry-m10*rx)/det;
        if(!Float.isFinite(dx)||!Float.isFinite(dy)) return false;
        for(int i=0;i<4;i++){
            float ox=(ax[i]-(k00*dx+k01*dy))/w;
            float oy=(ay[i]-(k10*dx+k11*dy))/h;
            if(!Float.isFinite(ox)||!Float.isFinite(oy)) return false;
            out[i*2]=ox; out[i*2+1]=oy;
        }
        return true;
    }
    static float dist(float[] a,float[] b){
        float m=0f;for(int i=0;i<8;i++)m=Math.max(m,Math.abs(a[i]-b[i]));return m;
    }
    static void roundTrip(String n,Pose p,float[] g){
        float[] qc=recoverPoseCentre(p);
        float[] pins=new float[8];
        if(!solvePin(p,qc,g,pins)){check(false,n+": solve refused");return;}
        Pose p2=copy(p);p2.off=pins;
        float d=dist(g,readQuad(p2));
        check(d<=0.5f,n+": drift "+d+"px");
        if(d>0.5f) System.out.println("    pins="+java.util.Arrays.toString(pins));
    }
    public static void main(String[] a){
        Pose top=new Pose();
        top.cx=500f;top.cy=360f;top.w=300f;top.h=200f;top.th=365.2412f;
        top.pivx=0.5f;top.pivy=0.0f;
        float[] q=readQuad(top);
        float[] qs=q.clone();float[] f=new float[2];
        TransformQuad.scaleCorner(qs,q.clone(),0,q[0]-40f,q[1]-30f,f);
        roundTrip("top365 scale TL",top,qs);
        for(int e=0;e<4;e++){
            float[] qf=q.clone();TransformQuad.foldOverEdge(qf,e);
            roundTrip("top365 fold "+e,top,qf);
        }
        float[] qf=q.clone();TransformQuad.freeCorner(qf,3,q[6]+25f,q[7]+30f);
        roundTrip("top365 free BL",top,qf);
        Pose cen=new Pose();
        cen.cx=500f;cen.cy=500f;cen.w=300f;cen.h=200f;cen.th=30f;
        float[] qc=readQuad(cen);
        float[] q2=qc.clone();
        TransformQuad.scaleCorner(q2,qc.clone(),1,qc[2]+30f,qc[3]-20f,new float[2]);
        roundTrip("cen30 scale",cen,q2);
        for(int e=0;e<4;e++){
            float[] qe=qc.clone();TransformQuad.foldOverEdge(qe,e);
            roundTrip("cen30 fold "+e,cen,qe);
        }
        java.util.Random r=new java.util.Random(20260906L);
        float worst=0f;int bad=0;
        float[] pivs={0f,0.5f,1f};
        for(int k=0;k<400;k++){
            Pose p=new Pose();
            p.cx=400f+r.nextFloat()*400f;p.cy=400f+r.nextFloat()*400f;
            p.w=100f+r.nextFloat()*400f;p.h=100f+r.nextFloat()*400f;
            p.th=r.nextFloat()*720f-360f;
            p.pivx=pivs[r.nextInt(3)];p.pivy=pivs[r.nextInt(3)];
            for(int i=0;i<8;i++)p.off[i]=(r.nextFloat()-0.5f)*0.2f;
            float[] qq=readQuad(p);
            float[] g=qq.clone();int op=k%3;
            if(op==0){
                if(!TransformQuad.scaleCorner(g,qq,r.nextInt(4),qq[0]+(r.nextFloat()-0.5f)*80f,qq[1]+(r.nextFloat()-0.5f)*80f,new float[2]))continue;
            }else if(op==1){
                TransformQuad.freeCorner(g,r.nextInt(4),qq[0]+(r.nextFloat()-0.5f)*60f,qq[1]+(r.nextFloat()-0.5f)*60f);
            }else{
                if(!TransformQuad.foldOverEdge(g,r.nextInt(4)))continue;
            }
            float[] cc=recoverPoseCentre(p);float[] pins=new float[8];
            if(!solvePin(p,cc,g,pins))continue;
            boolean range=true;for(float v:pins)if(!Float.isFinite(v)||Math.abs(v)>2.0001f)range=false;
            if(!range)continue;
            Pose p2=copy(p);p2.off=pins;
            float d=dist(g,readQuad(p2));worst=Math.max(worst,d);
            if(d>0.5f){bad++;if(bad<=3)System.out.println("    drift "+d+" k="+k+" th="+p.th);}
        }
        check(bad==0,"fuzz 400 within 0.5px (worst "+worst+", bad "+bad+")");
        System.out.println(fails==0?"ALL GREEN":(fails+" FAILED"));
        if(fails!=0)System.exit(1);
    }
}
