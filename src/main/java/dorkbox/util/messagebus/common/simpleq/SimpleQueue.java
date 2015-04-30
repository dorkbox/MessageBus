package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.TimeUnit;

import dorkbox.util.messagebus.common.simpleq.jctools.MpmcArrayTransferQueue;
import dorkbox.util.messagebus.common.simpleq.jctools.Pow2;

public final class SimpleQueue {

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
    static final int maxUntimedSpins = maxTimedSpins * 32;
    static final int negMaxUntimedSpins = -maxUntimedSpins;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    static final long spinForTimeoutThreshold = 1000L;

    private MpmcArrayTransferQueue queue;

    public SimpleQueue(final int size) {
        this.queue = new MpmcArrayTransferQueue(Pow2.roundToPowerOfTwo(size));
    }

    /**
     * PRODUCER
     */
    public void put(Object item) throws InterruptedException {
        this.queue.xfer(item, false, 0, MpmcArrayTransferQueue.TYPE_PRODUCER);
    }


    /**
     * CONSUMER
     */
    public Object take() throws InterruptedException {
//        this.queue.xfer(123, false, 0, MpmcExchangerQueue.TYPE_PRODUCER);
//        return 123;
        return this.queue.xfer(null, false, 0, MpmcArrayTransferQueue.TYPE_CONSUMER);
    }

    private Object xfer(Object item, boolean timed, long nanos, byte incomingType) throws InterruptedException {
        return null;
//        while (true) {
//             empty or same mode = push+park onto queue
//             complimentary mode = unpark+pop off queue
//
//            Object thread;
//
            // it is possible that two threads check the queue at the exact same time,
            //      BOTH can think that the queue is empty, resulting in a deadlock between threads
            // it is ALSO possible that the consumer pops the previous node, and so we thought it was not-empty, when
            //      in reality, it is.
//            final boolean empty = this.queue.isEmpty(); // LoadLoad
//            boolean sameMode;
//            if (!empty) {
//                sameMode = this.queue.getLastMessageType() == isConsumer;
//            }
//
//            // check to make sure we are not "double dipping" on our head
////            thread = lpThread(head);
////            if (empty && thread != null) {
////                empty = false;
////            } else if (sameMode && thread == null) {
////                busySpin();
////                continue;
////            }
//
//            // empty or same mode = push+park onto queue
//            if (empty || sameMode) {
//                if (timed && nanos <= 0) {
//                    // can't wait
//                    return null;
//                }
//
//                final Object tNext = lpNext(tail);
//                if (tail != lvTail()) { // LoadLoad
//                    // inconsistent read
//                    busySpin();
//                    continue;
//                }
//
//                if (isConsumer) {
//                    spType(tNext, isConsumer);
//                    spThread(tNext, Thread.currentThread());
//
//                    if (!advanceTail(tail, tNext)) { // FULL barrier
//                        // failed to link in
////                        busySpin();
//                        continue;
//                    }
//
//                    park(tNext, timed, nanos);
//
//                    // this will only advance head if necessary
////                    advanceHead(tail, tNext);
//                    Object lpItem1 = lpItem1(tNext);
//                    spItem1(tNext, null);
//                    return lpItem1;
//                } else {
//                    // producer
//                    spType(tNext, isConsumer);
//                    spItem1(tNext, item);
//                    spThread(tNext, Thread.currentThread());
//
//                    if (!advanceTail(tail, tNext)) { // FULL barrier
//                        // failed to link in
//                        continue;
//                    }
//
//                    park(tNext, timed, nanos);
//
//                    // this will only advance head if necessary
//                    advanceHead(tail, tNext);
//                    return null;
//                }
//            }
//            // complimentary mode = unpark+pop off queue
//            else {
//                Object next = lpNext(head);
//
//                if (tail != lvTail() || head != lvHead()) { // LoadLoad
//                    // inconsistent read
//                    busySpin();
//                    continue;
//                }
//
//                thread = lpThread(head);
//                if (isConsumer) {
//                    Object returnVal = lpItem1(head);
//
//                    // is already cancelled/fulfilled
//                    if (thread == null ||
//                        !casThread(head, thread, null)) { // FULL barrier
//
//                        // head was already used by a different thread
//                        advanceHead(head, next); // FULL barrier
//
//                        continue;
//                    }
//
//                    spItem1(head, null);
//                    LockSupport.unpark((Thread) thread);
//
//                    advanceHead(head, next); // FULL barrier
//
//                    return returnVal;
//                } else {
//                    // producer
//                    spItem1(head, item); // StoreStore
//
//                    // is already cancelled/fulfilled
//                    if (thread == null ||
//                        !casThread(head, thread, null)) { // FULL barrier
//
//                        // head was already used by a different thread
//                        advanceHead(head, next); // FULL barrier
//
//                        continue;
//                    }
//
//                    LockSupport.unpark((Thread) thread);
//
//                    advanceHead(head, next);  // FULL barrier
//                    return null;
//                }
//            }
//        }
    }

    private static final void busySpin2() {
        // busy spin for the amount of time (roughly) of a CPU context switch
        int spins = maxUntimedSpins;
        for (;;) {
            if (spins > 0) {
                --spins;
            } else {
                break;
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

//    private final void park(Object myNode, boolean timed, long nanos) throws InterruptedException {
////        long lastTime = timed ? System.nanoTime() : 0;
////        int spins = timed ? maxTimedSpins : maxUntimedSpins;
//        int spins = maxUntimedSpins;
//        Thread myThread = Thread.currentThread();
//
////                    if (timed) {
////                        long now = System.nanoTime();
////                        nanos -= now - lastTime;
////                        lastTime = now;
////                        if (nanos <= 0) {
//////                            s.tryCancel(e);
////                            continue;
////                        }
////                    }
//
//        // busy spin for the amount of time (roughly) of a CPU context switch
//        // then park (if necessary)
//        for (;;) {
//            if (lpThread(myNode) == null) {
//                return;
////            } else if (spins > 0) {
////                --spins;
//            } else if (spins > negMaxUntimedSpins) {
//                --spins;
////                LockSupport.parkNanos(1);
//            } else {
//                // park can return for NO REASON. Subsequent loops will hit this if it has not been ACTUALLY unlocked.
//                LockSupport.park();
//
//                if (myThread.isInterrupted()) {
//                    casThread(myNode, myThread, null);
//                    Thread.interrupted();
//                    throw new InterruptedException();
//                }
//            }
//        }
//    }

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
