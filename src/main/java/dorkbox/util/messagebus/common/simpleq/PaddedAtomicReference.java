package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.atomic.AtomicReference;

/**
 * An AtomicReference with heuristic padding to lessen cache effects of this heavily CAS'ed location. While the padding adds
 * noticeable space, all slots are created only on demand, and there will be more than one of them only when it would improve throughput
 * more than enough to outweigh using extra space.
 */
public class PaddedAtomicReference<T> extends AtomicReference<T> {
    private static final long serialVersionUID = 1L;

    // Improve likelihood of isolation on <= 64 byte cache lines
    public long z0, z1, z2, z3, z4, z5, z6, z7, z8, z9, za, zb, zc, zd, ze;

    public PaddedAtomicReference() {
    }

    public PaddedAtomicReference(T value) {
        super(value);
    }

    // prevent JIT from optimizing away the padding
    public final long sum() {
        return this.z0 + this.z1 + this.z2 + this.z3 + this.z4 + this.z5 + this.z6 + this.z7 + this.z8 + this.z9 + this.za + this.zb + + this.zc + this.zd + this.ze;
    }
}
