package dorkbox.util.messagebus.common.simpleq;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

// Improve likelihood of isolation on <= 64 byte cache lines

// java classes DO NOT mix their fields, so if we want specific padding, in a specific order, we must
// subclass them. ALl intel CPU L1/2/2 cache line size are all 64 bytes

// see: http://psy-lob-saw.blogspot.de/2013/05/know-thy-java-object-memory-layout.html
// see: http://mechanitis.blogspot.de/2011/07/dissecting-disruptor-why-its-so-fast_22.html

abstract class AtomicPaddedObject<O> {
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
    volatile O object = null;
}

public class PaddedObject<O> extends AtomicPaddedObject<O> {
    private final static long OFFSET;
    static {
        try {
            OFFSET = UNSAFE.objectFieldOffset(AtomicPaddedObject.class.getDeclaredField("object"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    // volatile long c0, c1, c2, c4, c5, c6, c7 = 7L;
    volatile long z0, z1, z2, z4, z5, z6 = 7L;

    public PaddedObject() {
    }

    public O get() {
        return this.object;
    }

    public final void set(Object newValue) {
        UNSAFE.putOrderedObject(this, OFFSET, newValue);
    }

    protected final boolean compareAndSet(Object expect, Object newValue) {
        return UNSAFE.compareAndSwapObject(this, OFFSET, expect, newValue);
    }
}
