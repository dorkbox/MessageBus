package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.atomic.AtomicReference;



public class Node<E> {

    // Improve likelihood of isolation on <= 64 byte cache lines
    // see: http://mechanitis.blogspot.de/2011/07/dissecting-disruptor-why-its-so-fast_22.html
    public long x0, x1, x2, x3, x4, x5, x6, x7;

    public volatile E item;

    public long y0, y1, y2, y3, y4, y5, y6, y7;

    /** The Thread waiting to be signaled to wake up*/
    public AtomicReference<Thread> waiter = new AtomicReference<Thread>();

    public long z0, z1, z2, z3, z4, z5, z6, z7;


    public Node(E item) {
        this.item = item;
    }

    // prevent JIT from optimizing away the padding
    public final long sum() {
        return this.x0 + this.x1 + this.x2 + this.x3 + this.x4 + this.x5 + this.x6 + this.x7 +
               this.y0 + this.y1 + this.y2 + this.y3 + this.y4 + this.y5 + this.y6 + this.y7 +
               this.z0 + this.z1 + this.z2 + this.z3 + this.z4 + this.z5 + this.z6 + this.z7;
    }
}
