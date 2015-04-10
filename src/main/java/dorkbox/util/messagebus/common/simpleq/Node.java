package dorkbox.util.messagebus.common.simpleq;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

// also, to increase performance due to false sharing/cache misses -- edit
// the system property "sparse.shift"


// Improve likelihood of isolation on <= 64 byte cache lines

// java classes DO NOT mix their fields, so if we want specific padding, in a specific order, we must
// subclass them. ALl intel CPU L1/2/2 cache line size are all 64 bytes

// see: http://psy-lob-saw.blogspot.de/2013/05/know-thy-java-object-memory-layout.html
// see: http://mechanitis.blogspot.de/2011/07/dissecting-disruptor-why-its-so-fast_22.html

abstract class NodeVal {
    public static final short FREE = 0;
    public static final short INIT = 1;
    public static final short DONE = 2;
}


abstract class NodeState {
    /** item stored in the node */
    public volatile short state = NodeVal.FREE;
}

abstract class NodePad2 extends NodeState {
//    volatile long y0, y1, y2, y3, y4, y5, y6 = 7L;
}

abstract class NodeItem extends NodePad2 {
    /** items stored in the node */
//    public volatile MessageType messageType = MessageType.ONE;

    public volatile Object message1 = null;
//    public Object message2 = null;
//    public Object message3 = null;
//    public Object[] messages = null;

}

abstract class NodePad3 extends NodeItem {
//    volatile long z0, z1, z2, z3, z4, z5, z6 = 7L;
}

abstract class NodeWaiter extends NodePad3 {
    /** The Thread waiting to be signaled to wake up*/
//    public AtomicReference<Thread> waiter = new AtomicReference<Thread>();
}

public class Node extends NodeWaiter {
    private final static long MESSAGE1_OFFSET;
    static {
        try {
            MESSAGE1_OFFSET = UNSAFE.objectFieldOffset(Node.class.getField("message1"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public Node() {
    }

    public void setMessage1(Object item) {
        UNSAFE.putObject(this, MESSAGE1_OFFSET, item);
    }

    public Object getMessage1() {
        return UNSAFE.getObject(this, MESSAGE1_OFFSET);
    }
}
