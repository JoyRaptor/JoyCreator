package cc.joycreator.avatar.face;

import cc.joycreator.avatar.perform.Walker;

/** The phone's walker as {@link FaceWalk} needs it: left and right, and toward the camera and away (its {@code DepthWalk}). */
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

    /** It walks in depth while it has a depth model (the room makes one while depth is in use: DepthWalk.prep). */
    @Override
    public boolean depth() {
        return w.z != null && w.canWalk();
    }

    @Override
    public double[] depthLimits() {
        return w.z == null ? new double[]{0, 0} : new double[]{w.z.lim[0], w.z.lim[1]};
    }

    @Override
    public Double tz() {
        return w.tz;
    }

    @Override
    public void setTz(double v) {
        w.tz = v;
    }

    @Override
    public double base() {
        return w.baseY;
    }
}
