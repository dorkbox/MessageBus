package dorkbox.util.messagebus.common;

import org.jctools.util.UnsafeAccess;

abstract class VarArgPossibility_P0 {
    // pre-padding
    volatile long y0, y1, y2, y4, y5, y6 = 7L;
}

abstract class VarArgPossibility_I0 extends VarArgPossibility_P0 {
    public boolean hasVarArgPossibility = false;
}

public class VarArgPossibility extends VarArgPossibility_I0 {
    private static final long BOOL;

    static {
        try {
            BOOL = UnsafeAccess.UNSAFE.objectFieldOffset(VarArgPossibility.class.getField("hasVarArgPossibility"));

            // now make sure we can access UNSAFE
            VarArgPossibility bool = new VarArgPossibility();
            boolean o = true;
            bool.set( o);
            boolean lpItem1 = bool.get();
            bool.set(false);

            if (lpItem1 != o) {
                throw new Exception("Cannot access unsafe fields");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public final void set(boolean bool) {
        UnsafeAccess.UNSAFE.putBoolean(this, BOOL, bool);
    }

    public final boolean get() {
        return UnsafeAccess.UNSAFE.getBoolean(this, BOOL);
    }

    // post-padding
    volatile long z0, z1, z2, z4, z5, z6 = 7L;

    public VarArgPossibility() {
    }
}
