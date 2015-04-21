package dorkbox.util.messagebus.common.simpleq;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

public class LinkedArrayList {

    private static final long HEAD;
    private static final long TAIL;

    private static final long NEXT;
    private static final long IS_CONSUMER;
    private static final long IS_READY;
    private static final long THREAD;

    private static final long ITEM1;

    static {
        try {
            HEAD = UNSAFE.objectFieldOffset(LinkedArrayList.class.getField("head"));
            TAIL = UNSAFE.objectFieldOffset(LinkedArrayList.class.getField("tail"));

            NEXT = UNSAFE.objectFieldOffset(Node.class.getField("next"));
            IS_CONSUMER = UNSAFE.objectFieldOffset(Node.class.getField("isConsumer"));
            IS_READY = UNSAFE.objectFieldOffset(Node.class.getField("isReady"));
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
        if (expected == lvTail()) {
            return UNSAFE.compareAndSwapObject(this, TAIL, expected, newTail);
        }
        return false;
    }

    final boolean advanceHead(Object expected, Object newHead) {
        if (expected == lvHead()) {
            return UNSAFE.compareAndSwapObject(this, HEAD, expected, newHead);
        }
        return false;
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

    final boolean lpIsReady(Object node) {
        return UNSAFE.getBoolean(node, IS_READY);
    }

    final void spIsReady(Object node, boolean isReady) {
        UNSAFE.putBoolean(node, IS_READY, isReady);
    }



    public Node head;
    public Node tail;

    private Node[] buffer;

    public LinkedArrayList(int size) {
        this.buffer = new Node[size];

        // pre-fill our data structures. This just makes sure to have happy memory. we use linkedList style iteration
        for (int i=0; i < size; i++) {
            this.buffer[i] = new Node();
            if (i > 0) {
                this.buffer[i-1].next = this.buffer[i];
            }
        }

        this.buffer[size-1].next = this.buffer[0];

        this.tail = this.buffer[0];
        this.head = this.tail.next;
    }
}
