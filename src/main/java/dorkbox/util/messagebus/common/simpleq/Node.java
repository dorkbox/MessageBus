package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.atomic.AtomicInteger;


// mpmc sparse.shift = 2, for this to be fast.

abstract class PrePad {
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class ColdItems {
    private static AtomicInteger count = new AtomicInteger();
    public final int ID = count.getAndIncrement();

    public boolean isReady = false;
    public boolean isConsumer = false;
//    public short type = MessageType.ONE;
    public Object item1 = null;
//    public Object item2 = null;
//    public Object item3 = null;
//    public Object[] item4 = null;
}

abstract class Pad0 extends ColdItems {
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class HotItem1 extends Pad0 {
    public volatile Thread thread;
}

abstract class Pad1 extends HotItem1 {
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class HotItem2 extends Pad1 {
    public volatile Node next;
}

public class Node extends HotItem2 {
    // post-padding
    volatile long z0, z1, z2, z4, z5, z6 = 7L;

    public Node() {
    }

    @Override
    public String toString() {
        return "[" + this.ID + "]";
    }
}
