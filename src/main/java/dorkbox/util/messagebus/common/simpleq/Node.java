package dorkbox.util.messagebus.common.simpleq;




// mpmc sparse.shift = 2, for this to be fast.

abstract class PrePad {
//    volatile long y0, y1, y2, y4, y5, y6 = 7L;
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class ColdItems extends PrePad {
//    private static AtomicInteger count = new AtomicInteger();
//    public final int ID = count.getAndIncrement();

    public int type = 0;

//    public short messageType = MessageType.ONE;
    public Object item1 = null;
//    public Object item2 = null;
//    public Object item3 = null;
//    public Object[] item4 = null;
}

abstract class Pad0 extends ColdItems {
//    volatile long y0, y1, y2, y4, y5, y6 = 7L;
//    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class HotItem1 extends Pad0 {
}

abstract class Pad1 extends HotItem1 {
//    volatile long y0, y1, y2, y4, y5, y6 = 7L;
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class HotItem2 extends Pad1 {
    public Thread thread;
}

abstract class Pad2 extends HotItem2 {
//    volatile long y0, y1, y2, y4, y5, y6 = 7L;
//    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class HotItem3 extends Pad2 {
//    public transient volatile Node next;
}

public class Node extends HotItem3 {
    // post-padding
//    volatile long y0, y1, y2, y4, y5, y6 = 7L;
    volatile long z0, z1, z2, z4, z5, z6 = 7L;

    public Node() {
    }

//    @Override
//    public String toString() {
//        return "[" + this.ID + "]";
//    }
}
