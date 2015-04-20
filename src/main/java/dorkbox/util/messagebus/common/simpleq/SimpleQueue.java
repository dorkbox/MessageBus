package dorkbox.util.messagebus.common.simpleq;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import dorkbox.util.messagebus.common.simpleq.jctools.MpmcArrayQueueConsumerField;

public final class SimpleQueue extends MpmcArrayQueueConsumerField<Node> {

    private static final long ITEM1_OFFSET;

    static {
        try {
            ITEM1_OFFSET = UNSAFE.objectFieldOffset(Node.class.getField("item1"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
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

        final Boolean isConsumer = Boolean.valueOf(item == null);
        final boolean consumerBoolValue = isConsumer.booleanValue();

        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long capacity = this.mask + 1;
        final long[] sBuffer = this.sequenceBuffer;

        // values we shouldn't reach
        long currentConsumerIndex = -1;
        long currentProducerIndex = Long.MAX_VALUE;

        long cSeqOffset;
        long pSeqOffset;

        long pSeq;
        long pDelta = -1;

        boolean sameMode = false;
        boolean empty = false;

        while (true) {
            currentProducerIndex = lvProducerIndex(); // LoadLoad

            // empty or same mode
            // push+park onto queue

            // we add ourselves to the queue and check status (maybe we park OR we undo, pop-consumer, and unpark)
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
                    empty = currentProducerIndex == currentConsumerIndex;

                    if (!empty) {
                        final long previousProducerIndex = currentProducerIndex - 1;

                        final long ppSeqOffset = calcSequenceOffset(previousProducerIndex, mask);
                        final long ppSeq = lvSequence(sBuffer, ppSeqOffset); // LoadLoad
                        final long ppDelta = ppSeq - previousProducerIndex;

                        if (ppDelta == 1) {
                            // same mode check

                            // on 64bit(no compressed oops) JVM this is the same as seqOffset
                            final long offset = calcElementOffset(previousProducerIndex, mask);
                            sameMode = lpElementType(offset) == isConsumer;
                        }
                    }

                    if (empty || sameMode) {
                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(currentProducerIndex, mask);
                        spElementType(offset, isConsumer);
                        spElementThread(offset, Thread.currentThread());

                        final Object element = lpElement(offset);
                        if (consumerBoolValue) {
                            // increment sequence by 1, the value expected by consumer
                            // (seeing this value from a producer will lead to retry 2)
                            soSequence(sBuffer, pSeqOffset, nextProducerIndex); // StoreStore

                            // now we wait
                            park(element, offset, timed, nanos);
                            return lvItem1(element);
                        } else {
                            spItem1(element, item);

                            // increment sequence by 1, the value expected by consumer
                            // (seeing this value from a producer will lead to retry 2)
                            soSequence(sBuffer, pSeqOffset, nextProducerIndex); // StoreStore

                            // now we wait
                            park(element, offset, timed, nanos);
                            return null;
                        }
                    } else {
                        // complimentary mode

                        // undo my push, since we will be poping off the queue instead
                        casProducerIndex(nextProducerIndex, currentProducerIndex);

                        while (true) {
                            // get item
                            cSeqOffset = calcSequenceOffset(currentConsumerIndex, mask);
                            final long cSeq = lvSequence(sBuffer, cSeqOffset); // LoadLoad
                            final long nextConsumerIndex = currentConsumerIndex + 1;
                            final long cDelta = cSeq - nextConsumerIndex;

                            if (cDelta == 0) {
                                // on 64bit(no compressed oops) JVM this is the same as seqOffset
                                final long offset = calcElementOffset(currentConsumerIndex, mask);
                                final Thread thread = lpElementThread(offset);

                                if (consumerBoolValue) {
                                    if (thread == null ||                      // is cancelled/fulfilled already
                                        !casElementThread(offset, thread, null)) {   // failed cas state

                                        // pop off queue
                                        if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
                                            // Successful CAS: full barrier
                                            soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
                                        }

                                        continue;
                                    }

                                    // success
                                    final Object element = lpElement(offset);
                                    Object item1 = lpItem1(element);

                                    // pop off queue
                                    if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
                                        // Successful CAS: full barrier
                                        LockSupport.unpark(thread);

                                        soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
                                        return item1;
                                    }

                                    busySpin();
                                    // failed CAS
                                    continue;
                                } else {
                                    final Object element = lpElement(offset);
                                    soItem1(element, item);

                                    if (thread == null ||                   // is cancelled/fulfilled already
                                        !casElementThread(offset, thread, null)) {   // failed cas state

                                        // pop off queue
                                        if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
                                            // Successful CAS: full barrier
                                            soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
                                        }

                                        // lost CAS
                                        busySpin();
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
                            }

                        }
                    }
                }
                // failed cas, retry 1
            } else if (pDelta < 0 && // poll has not moved this value forward
                    currentProducerIndex - capacity <= currentConsumerIndex && // test against cached cIndex
                    currentProducerIndex - capacity <= (currentConsumerIndex = lvConsumerIndex())) { // test against latest cIndex
                 // Extra check required to ensure [Queue.offer == false iff queue is full]
                 // return;
            }

            // contention.
            busySpin();
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

    /**
     * @param myThread
     * @return
     * @return false if we were interrupted, true if we were unparked by another thread
     */
    private final boolean park(Object myNode, long myOffset, boolean timed, long nanos) throws InterruptedException {
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
            if (lvElementThread(myOffset) == null) {
                return true;
            } else if (spins > 0) {
                --spins;
            } else if (spins > negMaxUntimedSpins) {
                --spins;
                LockSupport.parkNanos(1);
            } else {
                // park can return for NO REASON. Subsequent loops will hit this if it has not been ACTUALLY unlocked.
                LockSupport.park();

                if (myThread.isInterrupted()) {
                    casElementThread(myOffset, myThread, null);
                    return false;
                }
            }
        }
    }

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
