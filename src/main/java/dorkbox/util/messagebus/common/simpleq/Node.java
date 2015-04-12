package dorkbox.util.messagebus.common.simpleq;


// mpmc sparse.shift = 2, for this to be fast.

abstract class PrePad {
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class ColdItems extends PrePad {
    public short nodeType = NodeType.FREE;

    public Object item1 = null;
    public Object item2 = null;
    public Object item3 = null;
    public Object[] item4 = null;
}

abstract class Pad1 extends ColdItems {
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class HotItem1 extends Pad1 {
    public Thread thread;

}
public class Node extends HotItem1 {
    // post-padding
    volatile long z0, z1, z2, z4, z5, z6 = 7L;

    public Node() {
    }
}
