package cc.joycreator.avatar.face;

import cc.joycreator.avatar.perform.Walker;

/** The phone's walker as {@link FaceWalk} needs it: left and right only, until walking toward the camera is ported. */
public final class WalkerLegs implements FaceWalk.Legs {
    private final Walker w;

    public WalkerLegs(Walker w) {
        this.w = w;
    }

    @Override
    public boolean travels() {
        return w.travels();
    }

    @Override
    public boolean held() {
        return w.held;
    }

    @Override
    public double tx() {
        return w.tx;
    }

    @Override
    public void setTx(double v) {
        w.tx = v;
    }
}
