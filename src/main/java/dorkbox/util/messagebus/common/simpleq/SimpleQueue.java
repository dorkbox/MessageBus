package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class SimpleQueue extends LinkedArrayList {

    /** The number of CPUs */
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin (doing nothing except polling a memory location) before giving up while waiting to eliminate an
     * operation. Should be zero on uniprocessors. On multiprocessors, this value should be large enough so that two threads exchanging
     * items as fast as possible block only when one of them is stalled (due to GC or preemption), but not much longer, to avoid wasting CPU
     * resources. Seen differently, this value is a little over half the number of cycles of an average context switch time on most systems.
     * The value here is approximately the average of those across a range of tested systems.
     */
    private static final int SPINS = NCPU == 1 ? 0 : 512; // orig: 2000

    /**
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    static final int maxTimedSpins = NCPU < 2 ? 0 : 32;

    /**
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     */
    static final int maxUntimedSpins = maxTimedSpins * 16;
    static final int negMaxUntimedSpins = -maxUntimedSpins;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    static final long spinForTimeoutThreshold = 1000L;

    long p40, p41, p42, p43, p44, p45, p46;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    public SimpleQueue(final int size) {
        super(size);
    }

    /**
     * PRODUCER
     */
    public void put(Object item) throws InterruptedException {
        xfer(item, false, 0);
    }


    /**
     * CONSUMER
     */
    public Object take() throws InterruptedException {
        return xfer(null, false, 0);
    }

    private Object xfer(Object item, boolean timed, long nanos) throws InterruptedException {
        final boolean isConsumer= item == null;

        while (true) {
            // empty or same mode = push+park onto queue
            // complimentary mode = unpark+pop off queue

            Object tail = lvTail(); // LoadLoad
            Object head = lvHead(); // LoadLoad
            Object thread;

            // it is possible that two threads check the queue at the exact same time,
            //      BOTH can think that the queue is empty, resulting in a deadlock between threads
            // it is ALSO possible that the consumer pops the previous node, and so we thought it was not-empty, when
            //      in reality, it is.
            boolean empty = head == lpNext(tail);
            boolean sameMode = lpType(tail) == isConsumer;

            // empty or same mode = push+park onto queue
            if (empty || sameMode) {
                if (timed && nanos <= 0) {
                    // can't wait
                    return null;
                }

                final Object tNext = lpNext(tail);
                if (tail != lvTail()) { // LoadLoad
                    // inconsistent read
                    busySpin();
                    continue;
                }

                thread = lpThread(head);
                if (thread == null) {
                    if (sameMode) {
                        busySpin();
                        continue;
                    }
                } else {
                    if (empty) {
                        busySpin();
                        continue;
                    }
                }

//                if (sameMode && !lpIsReady(tNext)) {
//                    // A "node" is only ready (and valid for a "isConsumer check") once the "isReady" has been set.
//                    continue;
//                } else if (empty && lpIsReady(tNext)) {
//                    // A "node" is only empty (and valid for a "isEmpty check") if the head node "isReady" has not been set (otherwise, head is still in progress)
//                    continue;
//                }


                if (isConsumer) {
                    spType(tNext, isConsumer);
                    spIsReady(tNext, true);

                    spThread(tNext, Thread.currentThread());

                    if (!advanceTail(tail, tNext)) { // FULL barrier
                        // failed to link in
                        busySpin();
                        continue;
                    }

                    park(tNext, timed, nanos);

                    // this will only advance head if necessary
                    advanceHead(tail, tNext);
                    return lvItem1(tNext);
                } else {
                    spType(tNext, isConsumer);
                    spItem1(tNext, item);
                    spIsReady(tNext, true);

                    spThread(tNext, Thread.currentThread());

                    if (!advanceTail(tail, tNext)) { // FULL barrier
                        // failed to link in
                        busySpin();
                        continue;
                    }

                    park(tNext, timed, nanos);

                    // this will only advance head if necessary
                    advanceHead(tail, tNext);
                    return null;
                }
            }
            // complimentary mode = unpark+pop off queue
            else {
                Object next = lpNext(head);

                if (tail != lvTail() || head != lvHead()) { // LoadLoad
                    // inconsistent read
                    continue;
                }

                thread = lpThread(head);
                if (isConsumer) {
                    Object returnVal;

                    while (true) {
                        returnVal = lpItem1(head);

                        // is already cancelled/fulfilled
                        if (thread == null ||
                            !casThread(head, thread, null)) { // FULL barrier

                            // move head forward to look for next "ready" node
                            if (advanceHead(head, next)) { // FULL barrier
                                head = next;
                                next = lpNext(head);
                            }

                            thread = lpThread(head);
                            busySpin();
                            continue;
                        }

                        break;
                    }

                    spIsReady(head, false);
                    LockSupport.unpark((Thread) thread);

                    advanceHead(head, next);
                    return returnVal;
                } else {
                    while (true) {
                        soItem1(head, item); // StoreStore

                        // is already cancelled/fulfilled
                        if (thread == null ||
                            !casThread(head, thread, null)) { // FULL barrier

                            // move head forward to look for next "ready" node
                            if (advanceHead(head, next)) { // FULL barrier
                                head = next;
                                next = lpNext(head);
                            }

                            thread = lpThread(head);
                            busySpin();
                            continue;
                        }

                        break;
                    }

                    spIsReady(head, false);
                    LockSupport.unpark((Thread) thread);

                    advanceHead(head, next);
                    return null;
                }
            }
        }
    }

    private static final void busySpin() {
        // busy spin for the amount of time (roughly) of a CPU context switch
        int spins = SPINS;
        for (;;) {
            if (spins > 0) {
                --spins;
            } else {
                break;
            }
        }
    }

    private final void park(Object myNode, boolean timed, long nanos) throws InterruptedException {
//        long lastTime = timed ? System.nanoTime() : 0;
//        int spins = timed ? maxTimedSpins : maxUntimedSpins;
        int spins = maxUntimedSpins;
        Thread myThread = Thread.currentThread();

//                    if (timed) {
//                        long now = System.nanoTime();
//                        nanos -= now - lastTime;
//                        lastTime = now;
//                        if (nanos <= 0) {
////                            s.tryCancel(e);
//                            continue;
//                        }
//                    }

        // busy spin for the amount of time (roughly) of a CPU context switch
        // then park (if necessary)
        for (;;) {
            if (lvThread(myNode) == null) {
                return;
            } else if (spins > 0) {
                --spins;
            } else if (spins > negMaxUntimedSpins) {
                --spins;
                LockSupport.parkNanos(1);
            } else {
                // park can return for NO REASON. Subsequent loops will hit this if it has not been ACTUALLY unlocked.
                LockSupport.park();

                if (myThread.isInterrupted()) {
                    casThread(myNode, myThread, null);
                    Thread.interrupted();
                    throw new InterruptedException();
                }
            }
        }
    }

//    @Override
//    public boolean isEmpty() {
//        // Order matters!
//        // Loading consumer before producer allows for producer increments after consumer index is read.
//        // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
//        // nothing we can do to make this an exact method.
//        return lvConsumerIndex() == lvProducerIndex();
//    }

    public boolean hasPendingMessages() {
        // count the number of consumers waiting, it should be the same as the number of threads configured
//        return this.consumersWaiting.size() == this.numberConsumerThreads;
        return false;
    }

    public void tryTransfer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
        // TODO Auto-generated method stub

    }
}
