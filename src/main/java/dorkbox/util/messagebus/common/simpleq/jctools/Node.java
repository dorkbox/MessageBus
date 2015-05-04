package dorkbox.util.messagebus.common.simpleq.jctools;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

import com.lmax.disruptor.MessageType;

abstract class ColdItems {
    public int type = 0;

    public int messageType = MessageType.ONE;
    public Object item1 = null;
    public Object item2 = null;
    public Object item3 = null;
    public Object[] item4 = null;
}

abstract class Pad0 extends ColdItems {
//    volatile long y0, y1, y2, y4, y5, y6 = 7L;
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class HotItem1 extends Pad0 {
    public Thread thread;
}

public class Node extends HotItem1 {
    private static final long ITEM1;
    private static final long THREAD;
    private static final long TYPE;

    static {
        try {
            TYPE = UNSAFE.objectFieldOffset(Node.class.getField("type"));
            ITEM1 = UNSAFE.objectFieldOffset(Node.class.getField("item1"));
            THREAD = UNSAFE.objectFieldOffset(Node.class.getField("thread"));

            // now make sure we can access UNSAFE
            Node node = new Node();
            Object o = new Object();
            spItem1(node, o);
            Object lpItem1 = lpItem1(node);
            spItem1(node, null);

            if (lpItem1 != o) {
                throw new Exception("Cannot access unsafe fields");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static final void spItem1(Object node, Object item) {
        UNSAFE.putObject(node, ITEM1, item);
    }

    static final void soItem1(Object node, Object item) {
        UNSAFE.putOrderedObject(node, ITEM1, item);
    }

    static final Object lpItem1(Object node) {
        return UNSAFE.getObject(node, ITEM1);
    }

    static final Object lvItem1(Object node) {
        return UNSAFE.getObjectVolatile(node, ITEM1);
    }

    //////////////
    static final void spType(Object node, int type) {
        UNSAFE.putInt(node, TYPE, type);
    }

    static final int lpType(Object node) {
        return UNSAFE.getInt(node, TYPE);
    }

    ///////////
    static final void spThread(Object node, Object thread) {
        UNSAFE.putObject(node, THREAD, thread);
    }

    static final void soThread(Object node, Object thread) {
        UNSAFE.putOrderedObject(node, THREAD, thread);
    }

    static final Object lpThread(Object node) {
        return UNSAFE.getObject(node, THREAD);
    }

    static final Object lvThread(Object node) {
        return UNSAFE.getObjectVolatile(node, THREAD);
    }


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
