package dorkbox.util.messagebus.common.simpleq.bakup;

import org.jctools.util.UnsafeAccess;

abstract class CI1 {
    public int type = 0;
    public Object item1 = null;
}

abstract class P0 extends CI1 {
    volatile long y0, y1, y2, y4, y5, y6 = 7L;
}

abstract class HI0 extends P0 {
    public Thread thread;
}

public class Node extends HI0 {
    private static final long TYPE;
    private static final long ITEM;
    private static final long THREAD;

    static {
        try {
            TYPE = UnsafeAccess.UNSAFE.objectFieldOffset(Node.class.getField("type"));

            ITEM = UnsafeAccess.UNSAFE.objectFieldOffset(Node.class.getField("item1"));
            THREAD = UnsafeAccess.UNSAFE.objectFieldOffset(Node.class.getField("thread"));

            // now make sure we can access UNSAFE
            Node node = new Node();
            Object o = new Object();
            spItem(node, o);
            Object lpItem1 = lpItem(node);
            spItem(node, null);

            if (lpItem1 != o) {
                throw new Exception("Cannot access unsafe fields");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static final void spItem(Object node, Object item) {
        UnsafeAccess.UNSAFE.putObject(node, ITEM, item);
    }

    static final void soItem(Object node, Object item) {
        UnsafeAccess.UNSAFE.putOrderedObject(node, ITEM, item);
    }

    // only used by the single take() method. Not used by the void take(node)
    static final Object lvItem(Object node) {
        return UnsafeAccess.UNSAFE.getObjectVolatile(node, ITEM);
    }

    public static final Object lpItem(Object node) {
        return UnsafeAccess.UNSAFE.getObject(node, ITEM);
    }


    //////////////
    static final void spType(Object node, int type) {
        UnsafeAccess.UNSAFE.putInt(node, TYPE, type);
    }

    static final int lpType(Object node) {
        return UnsafeAccess.UNSAFE.getInt(node, TYPE);
    }

    ///////////
    static final void spThread(Object node, Object thread) {
        UnsafeAccess.UNSAFE.putObject(node, THREAD, thread);
    }

    static final void soThread(Object node, Object thread) {
        UnsafeAccess.UNSAFE.putOrderedObject(node, THREAD, thread);
    }

    static final Object lpThread(Object node) {
        return UnsafeAccess.UNSAFE.getObject(node, THREAD);
    }

    static final Object lvThread(Object node) {
        return UnsafeAccess.UNSAFE.getObjectVolatile(node, THREAD);
    }


    // post-padding
    volatile long z0, z1, z2, z4, z5, z6 = 7L;

    public Node() {
    }
}