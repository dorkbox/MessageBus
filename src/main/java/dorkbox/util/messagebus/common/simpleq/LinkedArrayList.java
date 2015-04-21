package dorkbox.util.messagebus.common.simpleq;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;


abstract class HotItemA1 extends PrePad {
    public volatile Node head;
}

abstract class PadA0 extends HotItemA1 {
    volatile long y0, y1, y2, y4, y5, y6 = 7L;
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}

abstract class HotItemA2 extends PadA0 {
    public volatile Node tail;
}

abstract class PadA1 extends HotItemA2 {
    volatile long y0, y1, y2, y4, y5, y6 = 7L;
    volatile long z0, z1, z2, z4, z5, z6 = 7L;
}


public class LinkedArrayList extends PadA1 {

    private static final long HEAD;
    private static final long TAIL;

    private static final long NEXT;
    private static final long IS_CONSUMER;
    private static final long THREAD;

    private static final long ITEM1;

    static {
        try {
            HEAD = UNSAFE.objectFieldOffset(LinkedArrayList.class.getField("head"));
            TAIL = UNSAFE.objectFieldOffset(LinkedArrayList.class.getField("tail"));

            NEXT = UNSAFE.objectFieldOffset(Node.class.getField("next"));
            IS_CONSUMER = UNSAFE.objectFieldOffset(Node.class.getField("isConsumer"));
            THREAD = UNSAFE.objectFieldOffset(Node.class.getField("thread"));
            ITEM1 = UNSAFE.objectFieldOffset(Node.class.getField("item1"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    static final void soItem1(Object node, Object item) {
        UNSAFE.putOrderedObject(node, ITEM1, item);
    }

    static final void spItem1(Object node, Object item) {
        UNSAFE.putObject(node, ITEM1, item);
    }

    static final Object lvItem1(Object node) {
        return UNSAFE.getObjectVolatile(node, ITEM1);
    }

    static final Object lpItem1(Object node) {
        return UNSAFE.getObject(node, ITEM1);
    }

    final Object lvHead() {
        return UNSAFE.getObjectVolatile(this, HEAD);
    }

    final Object lpHead() {
        return UNSAFE.getObject(this, HEAD);
    }

    final Object lvTail() {
        return UNSAFE.getObjectVolatile(this, TAIL);
    }

    final Object lpTail() {
        return UNSAFE.getObject(this, TAIL);
    }

    final Object lpNext(Object node) {
        return UNSAFE.getObject(node, NEXT);
    }

    final boolean advanceTail(Object expected, Object newTail) {
//        if (expected == lvTail()) {
            return UNSAFE.compareAndSwapObject(this, TAIL, expected, newTail);
//        }
//        return false;
    }

    final boolean advanceHead(Object expected, Object newHead) {
//        if (expected == lvHead()) {
            return UNSAFE.compareAndSwapObject(this, HEAD, expected, newHead);
//        }
//        return false;
    }

    final Object lvThread(Object node) {
        return UNSAFE.getObjectVolatile(node, THREAD);
    }

    final Object lpThread(Object node) {
        return UNSAFE.getObject(node, THREAD);
    }

    final void spThread(Object node, Thread thread) {
        UNSAFE.putObject(node, THREAD, thread);
    }

    final boolean casThread(Object node, Object expected, Object newThread) {
        return UNSAFE.compareAndSwapObject(node, THREAD, expected, newThread);
    }

    final boolean lpType(Object node) {
        return UNSAFE.getBoolean(node, IS_CONSUMER);
    }

    final void spType(Object node, boolean isConsumer) {
        UNSAFE.putBoolean(node, IS_CONSUMER, isConsumer);
    }

    final static int SPARSE = 1;


    public LinkedArrayList(int size) {
        size = size*SPARSE;

        Node[] buffer = new Node[size];

        // pre-fill our data structures. This just makes sure to have happy memory. we use linkedList style iteration
        int previous = -1;
        int current = 0;
        for (; current < size; current+=SPARSE) {
            buffer[current] = new Node();
            if (current > 0) {
                buffer[previous].next = buffer[current];
            }
            previous = current;
        }

        buffer[previous].next = buffer[0];

        this.tail = buffer[0];
        this.head = this.tail.next;
    }
}
