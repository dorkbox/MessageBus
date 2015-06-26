package dorkbox.util.messagebus;

import org.jctools.util.UnsafeAccess;

abstract class ColdItems {
    private int type = 0;

    private int messageType = MessageType.ONE;
    private Object item1 = null;
    private Object item2 = null;
    private Object item3 = null;
}

abstract class Pad0 extends ColdItems {
    @SuppressWarnings("unused")
    volatile long y0, y1, y2, y4, y5, y6 = 7L;
}

abstract class HotItem1 extends Pad0 {
    private Thread thread;
}

public class MultiNode extends HotItem1 {
    private static final long TYPE;

    private static final long MESSAGETYPE;
    private static final long ITEM1;
    private static final long ITEM2;
    private static final long ITEM3;

    private static final long THREAD;

    static {
        try {
            TYPE = UnsafeAccess.UNSAFE.objectFieldOffset(ColdItems.class.getDeclaredField("type"));

            MESSAGETYPE = UnsafeAccess.UNSAFE.objectFieldOffset(ColdItems.class.getDeclaredField("messageType"));
            ITEM1 = UnsafeAccess.UNSAFE.objectFieldOffset(ColdItems.class.getDeclaredField("item1"));
            ITEM2 = UnsafeAccess.UNSAFE.objectFieldOffset(ColdItems.class.getDeclaredField("item2"));
            ITEM3 = UnsafeAccess.UNSAFE.objectFieldOffset(ColdItems.class.getDeclaredField("item3"));
            THREAD = UnsafeAccess.UNSAFE.objectFieldOffset(HotItem1.class.getDeclaredField("thread"));

            // now make sure we can access UNSAFE
            MultiNode node = new MultiNode();
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

    static final void spMessageType(Object node, int messageType) {
        UnsafeAccess.UNSAFE.putInt(node, MESSAGETYPE, messageType);
    }
    static final void spItem1(Object node, Object item) {
        UnsafeAccess.UNSAFE.putObject(node, ITEM1, item);
    }
    static final void spItem2(Object node, Object item) {
        UnsafeAccess.UNSAFE.putObject(node, ITEM2, item);
    }
    static final void spItem3(Object node, Object item) {
        UnsafeAccess.UNSAFE.putObject(node, ITEM3, item);
    }

    // only used by the single transfer(item) method. Not used by the multi-transfer(*, *, *, *) method
    static final void soItem1(Object node, Object item) {
        UnsafeAccess.UNSAFE.putOrderedObject(node, ITEM1, item);
    }

    // only used by the single take() method. Not used by the void take(node)
    static final Object lvItem1(Object node) {
        return UnsafeAccess.UNSAFE.getObjectVolatile(node, ITEM1);
    }

    static final Object lpMessageType(Object node) {
        return UnsafeAccess.UNSAFE.getObject(node, MESSAGETYPE);
    }

    /**
     * Must call lvMessageType() BEFORE lpItem*() is called, because this ensures a LoadLoad for the data occurs.
     */
    public static final int lvMessageType(Object node) {
        return UnsafeAccess.UNSAFE.getIntVolatile(node, MESSAGETYPE);
    }
    public static final Object lpItem1(Object node) {
        return UnsafeAccess.UNSAFE.getObject(node, ITEM1);
    }
    public static final Object lpItem2(Object node) {
        return UnsafeAccess.UNSAFE.getObject(node, ITEM2);
    }
    public static final Object lpItem3(Object node) {
        return UnsafeAccess.UNSAFE.getObject(node, ITEM3);
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
    @SuppressWarnings("unused")
    volatile long z0, z1, z2, z4, z5, z6 = 7L;

    public MultiNode() {
    }
}
