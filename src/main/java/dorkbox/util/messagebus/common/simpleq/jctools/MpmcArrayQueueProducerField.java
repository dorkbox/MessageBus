package dorkbox.util.messagebus.common.simpleq.jctools;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

abstract class MpmcArrayQueueProducerField<E> extends MpmcArrayQueueL1Pad<E> {
    private final static long P_INDEX_OFFSET;
    static {
        try {
            P_INDEX_OFFSET = UNSAFE.objectFieldOffset(MpmcArrayQueueProducerField.class
                    .getDeclaredField("producerIndex"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
    private volatile long producerIndex;

    public MpmcArrayQueueProducerField(int capacity) {
        super(capacity);
    }

    protected final long lvProducerIndex() {
        return this.producerIndex;
    }

    protected final boolean casProducerIndex(long expect, long newValue) {
        return UNSAFE.compareAndSwapLong(this, P_INDEX_OFFSET, expect, newValue);
    }
}