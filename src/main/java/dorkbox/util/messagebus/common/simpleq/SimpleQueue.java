package dorkbox.util.messagebus.common.simpleq;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import dorkbox.util.messagebus.common.simpleq.jctools.MpmcArrayQueueConsumerField;

public final class SimpleQueue extends MpmcArrayQueueConsumerField<Node> {

    private final static long THREAD;
    private static final long ITEM1_OFFSET;
    private static final long TYPE;
    private static final long CONSUMER;

    static {
        try {
            CONSUMER = UNSAFE.objectFieldOffset(Node.class.getField("isConsumer"));
            THREAD = UNSAFE.objectFieldOffset(Node.class.getField("thread"));
            TYPE = UNSAFE.objectFieldOffset(Node.class.getField("type"));
            ITEM1_OFFSET = UNSAFE.objectFieldOffset(Node.class.getField("item1"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static final void spIsConsumer(Object node, boolean value) {
        UNSAFE.putBoolean(node, CONSUMER, value);
    }

    private static final boolean lpIsConsumer(Object node) {
        return UNSAFE.getBoolean(node, CONSUMER);
    }

    private static final boolean lvIsConsumer(Object node) {
        return UNSAFE.getBooleanVolatile(node, CONSUMER);
    }


    private static final void spType(Object node, short type) {
        UNSAFE.putShort(node, TYPE, type);
    }

    private static final short lpType(Object node) {
        return UNSAFE.getShort(node, TYPE);
    }

    private static final void soItem1(Object node, Object item) {
        UNSAFE.putOrderedObject(node, ITEM1_OFFSET, item);
    }

    private static final void spItem1(Object node, Object item) {
        UNSAFE.putObject(node, ITEM1_OFFSET, item);
    }

    private static final Object lvItem1(Object node) {
        return UNSAFE.getObjectVolatile(node, ITEM1_OFFSET);
    }

    private static final Object lpItem1(Object node) {
        return UNSAFE.getObject(node, ITEM1_OFFSET);
    }

    private static final Thread lvThread(Object node) {
        return (Thread) UNSAFE.getObjectVolatile(node, THREAD);
    }

    private static final Thread lpThread(Object node) {
        return (Thread) UNSAFE.getObject(node, THREAD);
    }

    private static final Object cancelledMarker = new Object();

    private static final boolean lpIsCanceled(Object node) {
        return cancelledMarker == UNSAFE.getObject(node, THREAD);
    }

    private static final void spIsCancelled(Object node) {
        UNSAFE.putObject(node, THREAD, cancelledMarker);
    }

    private static final void soThread(Object node, Thread newValue) {
        UNSAFE.putOrderedObject(node, THREAD, newValue);
    }

    private static final void spThread(Object node, Thread newValue) {
        UNSAFE.putObject(node, THREAD, newValue);
    }

    private static final boolean casThread(Object node, Object expect, Object newValue) {
        return UNSAFE.compareAndSwapObject(node, THREAD, expect, newValue);
    }



    /** The number of CPUs */
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin (doing nothing except polling a memory location) before giving up while waiting to eliminate an
     * operation. Should be zero on uniprocessors. On multiprocessors, this value should be large enough so that two threads exchanging
     * items as fast as possible block only when one of them is stalled (due to GC or preemption), but not much longer, to avoid wasting CPU
     * resources. Seen differently, this value is a little over half the number of cycles of an average context switch time on most systems.
     * The value here is approximately the average of those across a range of tested systems.
     */
    private static final int SPINS = NCPU == 1 ? 0 : 600; // orig: 2000

    /**
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    static final int maxTimedSpins = NCPU < 2 ? 0 : 32;
    static final int negMaxTimedSpins = -maxTimedSpins;

    /**
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     */
    static final int maxUntimedSpins = maxTimedSpins * 16;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    static final long spinForTimeoutThreshold = 1000L;



    long p40, p41, p42, p43, p44, p45, p46;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    public SimpleQueue(final int size) {
        super(size);

        // pre-fill our data structures

        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        long currentProducerIndex;

        for (currentProducerIndex = 0; currentProducerIndex < size; currentProducerIndex++) {
            // on 64bit(no compressed oops) JVM this is the same as seqOffset
            final long elementOffset = calcElementOffset(currentProducerIndex, mask);
            soElement(elementOffset, new Node());
        }
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

        boolean isConsumer = item == null;

        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long capacity = this.mask + 1;
        final long[] sBuffer = this.sequenceBuffer;

        long currentConsumerIndex;
        long currentProducerIndex;

        long cSeqOffset;
        long pSeqOffset;

        long pSeq;
        long pDelta = -1;

        boolean sameMode = false;
        boolean empty = false;

        while (true) {
            // Order matters!
            // Loading consumer before producer allows for producer increments after consumer index is read.
            // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
            // nothing we can do to make this an exact method.
            currentConsumerIndex = lvConsumerIndex(); // LoadLoad
            currentProducerIndex = lvProducerIndex(); // LoadLoad

            // empty or same mode
            // check what was last placed on the queue
            if (currentProducerIndex == currentConsumerIndex) {
                empty = true;
            } else {
                final long previousProducerIndex = currentProducerIndex - 1;

                final long ppSeqOffset = calcSequenceOffset(previousProducerIndex, mask);
                final long ppSeq = lvSequence(sBuffer, ppSeqOffset); // LoadLoad
                final long ppDelta = ppSeq - previousProducerIndex;

                if (ppDelta == 1) {
                    // same mode check

                    // on 64bit(no compressed oops) JVM this is the same as seqOffset
                    final long offset = calcElementOffset(previousProducerIndex, mask);
                    Object element = lpElement(offset);
                    sameMode = lpIsConsumer(element) == isConsumer;
                } else if (ppDelta < 1 && // slot has not been moved by producer
                           currentConsumerIndex >= currentProducerIndex && // test against cached pIndex
                           currentConsumerIndex == (currentProducerIndex = lvProducerIndex())) { // update pIndex if we must

                    // is empty
                    empty = true;
                } else {
                    // hasn't been moved yet. retry 2
                    busySpin();
                    continue;
                }
            }


            if (empty || sameMode) {
                // push+park onto queue

                // we add ourselves to the queue and wait
                pSeqOffset = calcSequenceOffset(currentProducerIndex, mask);
                pSeq = lvSequence(sBuffer, pSeqOffset); // LoadLoad
                pDelta = pSeq - currentProducerIndex;
                if (pDelta == 0) {
                    // this is expected if we see this first time around
                    final long nextProducerIndex = currentProducerIndex + 1;
                    if (casProducerIndex(currentProducerIndex, nextProducerIndex)) {
                        // Successful CAS: full barrier


                        // it is possible that two threads check the queue at the exact same time,
                        //      BOTH can think that the queue is empty, resulting in a deadlock between threads
                        // it is ALSO possible that the consumer pops the previous node, and so we thought it was not-empty, when
                        //      in reality, it is.
                        currentConsumerIndex = lvConsumerIndex();

                        if (empty && currentProducerIndex != currentConsumerIndex) {
                            // RESET the push of this element.
                            empty = false;
                            casProducerIndex(nextProducerIndex, currentProducerIndex);
                            continue;
                        } else if (sameMode && currentProducerIndex == currentConsumerIndex) {
                            sameMode = false;
                            casProducerIndex(nextProducerIndex, currentProducerIndex);
                            continue;
                        }


                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(currentProducerIndex, mask);
                        final Object element = lpElement(offset);
                        spIsConsumer(element, isConsumer);
                        spThread(element, Thread.currentThread());

                        if (isConsumer) {
                            // increment sequence by 1, the value expected by consumer
                            // (seeing this value from a producer will lead to retry 2)
                            soSequence(sBuffer, pSeqOffset, nextProducerIndex); // StoreStore

                            // now we wait
                            park(element, timed, nanos);
                            return lvItem1(element);
                        } else {
                            spItem1(element, item);

                            // increment sequence by 1, the value expected by consumer
                            // (seeing this value from a producer will lead to retry 2)
                            soSequence(sBuffer, pSeqOffset, nextProducerIndex); // StoreStore

                            // now we wait
                            park(element, timed, nanos);
                            return null;
                        }
                    }
                    // failed cas, retry 1
                } else if (pDelta < 0 && // poll has not moved this value forward
                        currentProducerIndex - capacity <= currentConsumerIndex && // test against cached cIndex
                        currentProducerIndex - capacity <= (currentConsumerIndex = lvConsumerIndex())) { // test against latest cIndex
                     // Extra check required to ensure [Queue.offer == false iff queue is full]
                     // return;
                    busySpin();
                }
            } else {
                // complimentary mode

                // get item
                cSeqOffset = calcSequenceOffset(currentConsumerIndex, mask);
                final long cSeq = lvSequence(sBuffer, cSeqOffset); // LoadLoad
                final long nextConsumerIndex = currentConsumerIndex + 1;
                final long cDelta = cSeq - nextConsumerIndex;

                if (cDelta == 0) {
                    // on 64bit(no compressed oops) JVM this is the same as seqOffset
                    final long offset = calcElementOffset(currentConsumerIndex, mask);
                    final Object element = lpElement(offset);
                    final Thread thread = lpThread(element);

                    if (isConsumer) {
                        if (thread == null ||                      // is cancelled/fulfilled already
                            !casThread(element, thread, null)) {   // failed cas state

                            // pop off queue
                            if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
                                // Successful CAS: full barrier
                                soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
                            }
                            continue;
                        }

                        // success
                        Object item1 = lpItem1(element);

                        // pop off queue
                        if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
                            // Successful CAS: full barrier
                            LockSupport.unpark(thread);

                            soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
                            return item1;
                        }

                        continue;
                    } else {
                        soItem1(element, item);

                        if (thread == null ||                   // is cancelled/fulfilled already
                            !casThread(element, thread, null)) {   // failed cas state

                            // pop off queue
                            if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
                                // Successful CAS: full barrier
                                soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
                            }

                            continue;
                        }

                        // success

                        // pop off queue
                        if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
                            // Successful CAS: full barrier

                            LockSupport.unpark(thread);
                            soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore

                            return null;
                        }

                        // lost CAS
                        busySpin();
                        continue;
                    }

                } else if (cDelta < 0 && // slot has not been moved by producer
                        currentConsumerIndex >= currentProducerIndex && // test against cached pIndex
                        currentConsumerIndex == (currentProducerIndex = lvProducerIndex())) { // update pIndex if we must
                    // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
                    // return null;
                    busySpin();

                }
            }
            // contention.
            busySpin();
        }
    }

    private static final void busySpin() {
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

    /**
     * @param myThread
     * @return
     * @return false if we were interrupted, true if we were unparked by another thread
     */
    private static final boolean park(Object myNode, boolean timed, long nanos) throws InterruptedException {
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
        int spin = spins;
        for (;;) {
            if (lvThread(myNode) == null) {
                return true;
            } else if (spin > 0) {
                --spin;
            } else if (spin > negMaxTimedSpins) {
                LockSupport.parkNanos(1);
            } else {
                // park can return for NO REASON. Subsequent loops will hit this if it has not been ACTUALLY unlocked.
                LockSupport.park();

                if (myThread.isInterrupted()) {
                    casThread(myNode, myThread, null);
                    return false;
                }
            }
        }
    }

//    /**
//     * Unparks the other node (if it was waiting)
//     */
//    private static final void unpark(Object otherNode) {
//        Thread myThread = Thread.currentThread();
//        Thread thread;
//
//        for (;;) {
//            thread = getThread(otherNode);
//            if (threadCAS(otherNode, thread, myThread)) {
//                if (thread == null) {
//                    // no parking (UNPARK won the race)
//                    return;
//                } else if (thread != myThread) {
//                    // park will always set the waiter back to null
//                    LockSupport.unpark(thread);
//                    return;
//                } else {
//                    // contention
//                    busySpin();
//                }
//            }
//        }
//    }

    @Override
    public boolean offer(Node message) {
        return false;
    }

    @Override
    public Node poll() {
        return null;
    }

    @Override
    public Node peek() {
        return null;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        // Order matters!
        // Loading consumer before producer allows for producer increments after consumer index is read.
        // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
        // nothing we can do to make this an exact method.
        return lvConsumerIndex() == lvProducerIndex();
    }

    public boolean hasPendingMessages() {
        // count the number of consumers waiting, it should be the same as the number of threads configured
//        return this.consumersWaiting.size() == this.numberConsumerThreads;
        return false;
    }

    public void tryTransfer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
        // TODO Auto-generated method stub

    }
}
