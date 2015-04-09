package dorkbox.util.messagebus.common.simpleq.bakup;

import java.util.concurrent.atomic.AtomicReference;

public class NodeORG<E> {
    // Improve likelihood of isolation on <= 64 byte cache lines
    public long z0, z1, z2, z3, z4, z5, z6, z7, z8, z9, za, zb, zc, zd, ze;


    public volatile E item;

    /** The Thread waiting to be signaled to wake up*/
    public volatile Thread waiter;
    public AtomicReference<Object> state = new AtomicReference<Object>();

    public volatile NodeORG<E> next;
    public volatile short ID = 0;

    public final boolean isConsumer;

    private NodeORG(boolean isConsumer, E item) {
        this.isConsumer = isConsumer;
        this.item = item;
    }

    // prevent JIT from optimizing away the padding
    public final long sum() {
        return this.z0 + this.z1 + this.z2 + this.z3 + this.z4 + this.z5 + this.z6 + this.z7 + this.z8 + this.z9 + this.za + this.zb + + this.zc + this.zd + this.ze;
    }

    public static <E> NodeORG<E> newProducer(E item) {
        // producers VARY which node is used on which thread. (so the waiter is set in the put method)
        return new NodeORG<E>(false, item);
    }

    public static <E> NodeORG<E> newConsumer(E item) {
        // consumers will always use the SAME node in the SAME thread
        NodeORG<E> node = new NodeORG<E>(true, item);
        node.waiter = Thread.currentThread();
        return node;
    }
}
