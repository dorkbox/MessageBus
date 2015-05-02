package dorkbox.util.messagebus.common.simpleq.jctools;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

import dorkbox.util.messagebus.common.simpleq.Node;

public final class MpmcArrayTransferQueue extends MpmcArrayQueueConsumerField<Node> {

    public static final int TYPE_EMPTY = 0;
    public static final int TYPE_CONSUMER = 1;
    public static final int TYPE_PRODUCER = 2;

    private static final long TYPE;

    static {
        try {
            TYPE = UNSAFE.objectFieldOffset(Node.class.getField("type"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static final int lpType(Object node) {
        return UNSAFE.getInt(node, TYPE);
    }

    /** The number of CPUs */
    private static final boolean MP = Runtime.getRuntime().availableProcessors() > 1;

    /**
     * The number of times to spin (with randomly interspersed calls
     * to Thread.yield) on multiprocessor before blocking when a node
     * is apparently the first waiter in the queue.  See above for
     * explanation. Must be a power of two. The value is empirically
     * derived -- it works pretty well across a variety of processors,
     * numbers of CPUs, and OSes.
     */
    private static final int FRONT_SPINS   = 1 << 7;

    /**
     * The number of times to spin before blocking when a node is
     * preceded by another node that is apparently spinning.  Also
     * serves as an increment to FRONT_SPINS on phase changes, and as
     * base average frequency for yielding during spins. Must be a
     * power of two.
     */
    private static final int CHAINED_SPINS = FRONT_SPINS >>> 1;

    long p40, p41, p42, p43, p44, p45, p46;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    /** Creates a {@code EliminationStack} that is initially empty. */
    public MpmcArrayTransferQueue(final int size) {
        super(size);
//
//        // pre-fill our data structures
//
//        // local load of field to avoid repeated loads after volatile reads
//        final long mask = this.mask;
//        long currentProducerIndex;
//
//        for (currentProducerIndex = 0; currentProducerIndex < size; currentProducerIndex++) {
//            // on 64bit(no compressed oops) JVM this is the same as seqOffset
//            final long elementOffset = calcElementOffset(currentProducerIndex, mask);
//            soElement(elementOffset, new Node());
//        }
    }

    /**
     * Only put an element into the queue if the queue is empty
     * @param item
     * @param timed
     * @param nanos
     * @return the offset that the item was placed into
     */
    public boolean putIfEmpty(final Object item, final boolean timed, final long nanos) {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long capacity = mask + 1;
        final long[] sBuffer = this.sequenceBuffer;

        long producerIndex;
        long pSeqOffset;
        long consumerIndex;

        while (true) {
            // consumer has to be first
            consumerIndex = lvConsumerIndex(); // LoadLoad
            producerIndex = lvProducerIndex(); // LoadLoad

            if (consumerIndex != producerIndex) {
                return false;
            }

            pSeqOffset = calcSequenceOffset(producerIndex, mask);
            final long seq = lvSequence(sBuffer, pSeqOffset); // LoadLoad
            final long delta = seq - producerIndex;

            if (delta == 0) {
                // this is expected if we see this first time around
                if (casProducerIndex(producerIndex, producerIndex + 1)) {
                    // Successful CAS: full barrier

                    // on 64bit(no compressed oops) JVM this is the same as seqOffset
                    final long offset = calcElementOffset(producerIndex, mask);
                    spElement(offset, item);


                    // increment sequence by 1, the value expected by consumer
                    // (seeing this value from a producer will lead to retry 2)
                    soSequence(sBuffer, pSeqOffset, producerIndex + 1); // StoreStore

                    return true;
                }
                // failed cas, retry 1
            } else if (delta < 0 && // poll has not moved this value forward
                    producerIndex - capacity <= consumerIndex && // test against cached cIndex
                    producerIndex - capacity <= (consumerIndex = lvConsumerIndex())) { // test against latest cIndex
                 // Extra check required to ensure [Queue.offer == false iff queue is full]
                 // return false;
            }

            // another producer has moved the sequence by one, retry 2
            busySpin();
        }
    }

    /**
     *
     * @param item
     * @param timed
     * @param nanos
     * @return the offset that the item was placed into
     */
    public void put(final Object item, final boolean timed, final long nanos) {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long capacity = mask + 1;
        final long[] sBuffer = this.sequenceBuffer;

        long producerIndex;
        long pSeqOffset;
        long consumerIndex = Long.MAX_VALUE;// start with bogus value, hope we don't need it

        while (true) {
            producerIndex = lvProducerIndex(); // LoadLoad

            pSeqOffset = calcSequenceOffset(producerIndex, mask);
            final long seq = lvSequence(sBuffer, pSeqOffset); // LoadLoad
            final long delta = seq - producerIndex;

            if (delta == 0) {
                // this is expected if we see this first time around
                if (casProducerIndex(producerIndex, producerIndex + 1)) {
                    // Successful CAS: full barrier

                    // on 64bit(no compressed oops) JVM this is the same as seqOffset
                    final long offset = calcElementOffset(producerIndex, mask);
                    spElement(offset, item);


                    // increment sequence by 1, the value expected by consumer
                    // (seeing this value from a producer will lead to retry 2)
                    soSequence(sBuffer, pSeqOffset, producerIndex + 1); // StoreStore

                    return;
                }
                // failed cas, retry 1
            } else if (delta < 0 && // poll has not moved this value forward
                            producerIndex - capacity <= consumerIndex && // test against cached cIndex
                            producerIndex - capacity <= (consumerIndex = lvConsumerIndex())) { // test against latest cIndex
                // Extra check required to ensure [Queue.offer == false iff queue is full]
                // return false;
            }

            // another producer has moved the sequence by one, retry 2
            busySpin();
        }
    }

    public Object take(final boolean timed, final long nanos) {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long[] sBuffer = this.sequenceBuffer;

        long consumerIndex;
        long cSeqOffset;
        long producerIndex = -1; // start with bogus value, hope we don't need it

        while (true) {
            consumerIndex = lvConsumerIndex(); // LoadLoad
            cSeqOffset = calcSequenceOffset(consumerIndex, mask);
            final long seq = lvSequence(sBuffer, cSeqOffset); // LoadLoad
            final long delta = seq - (consumerIndex + 1);

            if (delta == 0) {
                if (casConsumerIndex(consumerIndex, consumerIndex + 1)) {
                    // Successful CAS: full barrier

                    // on 64bit(no compressed oops) JVM this is the same as seqOffset
                    final long offset = calcElementOffset(consumerIndex, mask);
                    final Object e = lpElementNoCast(offset);
                    spElement(offset, null);

                    // Move sequence ahead by capacity, preparing it for next offer
                    // (seeing this value from a consumer will lead to retry 2)
                    soSequence(sBuffer, cSeqOffset, consumerIndex + mask + 1); // StoreStore

                    return e;
                }
                // failed cas, retry 1
            } else if (delta < 0 && // slot has not been moved by producer
                    consumerIndex >= producerIndex && // test against cached pIndex
                    consumerIndex == (producerIndex = lvProducerIndex())) { // update pIndex if we must
                // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
//                return null;
                busySpin(); // empty, so busy spin
            }

            // another consumer beat us and moved sequence ahead, retry 2
            // only producer will busy spin
        }
    }


//
//    public Object xfer(final Object item, final boolean timed, final long nanos, final int incomingType) throws InterruptedException {
//        // empty or same mode = push+park onto queue
//        // complimentary mode = unpark+pop off queue
//
//        // local load of field to avoid repeated loads after volatile reads
//        final long mask = this.mask;
//        final long capacity = this.mask + 1;
//        final long[] sBuffer = this.sequenceBuffer;
//
//        long currentProducerIndex = -1; // start with bogus value, hope we don't need it
//        long pSeqOffset;
//
//        long currentConsumerIndex = Long.MAX_VALUE; // start with bogus value, hope we don't need it
//        long cSeqOffset;
//
//        int prevElementType;
//
//        while (true) {
//            currentConsumerIndex = lvConsumerIndex(); // LoadLoad
//            currentProducerIndex = lvProducerIndex(); // LoadLoad
//
//
//            // the sequence must be loaded before we get the previous node type, otherwise previousNodeType can be stale/
//            pSeqOffset = calcSequenceOffset(currentProducerIndex, mask);
//            final long pSeq = lvSequence(sBuffer, pSeqOffset); // LoadLoad
//
//            // if the queue is EMPTY, this will return TYPE_FREE
//            // other consumers may have grabbed the element, or queue might be empty
//            // may be FREE or CONSUMER/PRODUCER
//            final long prevElementOffset = calcElementOffset(currentProducerIndex - 1, mask);
//            prevElementType = lpElementType(prevElementOffset);
//
////            if (prevElementType == TYPE_CANCELED) {
////                // pop off and continue.
////
////                cSeqOffset = calcSequenceOffset(currentConsumerIndex, mask);
////                final long seq = lvSequence(sBuffer, cSeqOffset); // LoadLoad
////
////                final long nextConsumerIndex = currentConsumerIndex + 1;
////                final long delta = seq - nextConsumerIndex;
////
////                if (delta == 0) {
////                    if (prevElementType != lpElementType(prevElementOffset)) {
////                        // inconsistent read of type.
////                        continue;
////                    }
////
////                    if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
////                        // Successful CAS: full barrier
////
////                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
////                        final long offset = calcElementOffset(currentConsumerIndex, mask);
////                        spElementType(offset, TYPE_FREE);
////
////                        // Move sequence ahead by capacity, preparing it for next offer
////                        // (seeing this value from a consumer will lead to retry 2)
////                        soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
////                    }
////                    // failed cas, retry 1
////                } else if (delta < 0 && // slot has not been moved by producer
////                        currentConsumerIndex >= currentProducerIndex && // test against cached pIndex
////                        currentConsumerIndex == (currentProducerIndex = lvProducerIndex())) { // update pIndex if we must
////                    // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
////                    // return null;
////
////                    // spin
//////                    busySpin(prevElementType, incomingType);
////                }
////
////                // another consumer beat us and moved sequence ahead, retry 2
////                // only producer busyspins
////                continue;
////            }
//
//
//            // it is possible that two threads check the queue at the exact same time,
//            //      BOTH can think that the queue is empty, resulting in a deadlock between threads
//            // it is ALSO possible that the consumer pops the previous node, and so we thought it was not-empty, when
//            //      in reality, it is.
//
//            if (prevElementType == TYPE_FREE || prevElementType == incomingType) {
//                // empty or same mode = push+park onto queue
//
//                if (timed && nanos <= 0) {
//                    // can't wait
//                    return null;
//                }
//
//                final long delta = pSeq - currentProducerIndex;
//                if (delta == 0) {
//                    if (prevElementType != lpElementType(prevElementOffset)) {
//                        // inconsistent read of type.
//                        continue;
//                    }
//
//                    // this is expected if we see this first time around
//                    final long nextProducerIndex = currentProducerIndex + 1;
//                    if (casProducerIndex(currentProducerIndex, nextProducerIndex)) {
//                        // Successful CAS: full barrier
//
//                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
//                        final long offset = calcElementOffset(currentProducerIndex, mask);
//
//                        if (prevElementType == TYPE_FREE && currentProducerIndex != currentConsumerIndex) {
//                            // inconsistent read
//
//                            // try to undo, if possible.
//                            if (!casProducerIndex(nextProducerIndex, currentProducerIndex)) {
////                                spElementType(offset, TYPE_CANCELED);
//
//                                // increment sequence by 1, the value expected by consumer
//                                // (seeing this value from a producer will lead to retry 2)
//                                soSequence(sBuffer, pSeqOffset, nextProducerIndex); // StoreStore
//                            }
//
//                            continue;
//                        }
//
//                        spElementType(offset, incomingType);
//                        spElementThread(offset, Thread.currentThread());
//
//                        if (incomingType == TYPE_CONSUMER) {
//                            // increment sequence by 1, the value expected by consumer
//                            // (seeing this value from a producer will lead to retry 2)
//                            soSequence(sBuffer, pSeqOffset, nextProducerIndex); // StoreStore
//
//                            park(offset, timed, nanos);
//
//                            Object lpElement = lpElementNoCast(offset);
//                            Object lpItem1 = lpItem1(lpElement);
//
//                            return lpItem1;
//                        } else {
//                            // producer
//                            Object lpElement = lpElementNoCast(offset);
//                            spItem1(lpElement, item);
//
//                            // increment sequence by 1, the value expected by consumer
//                            // (seeing this value from a producer will lead to retry 2)
//                            soSequence(sBuffer, pSeqOffset, nextProducerIndex); // StoreStore
//
//                            park(offset, timed, nanos);
//
//                            return null;
//                        }
//                    }
//                    // failed cas, retry 1
//                } else if (delta < 0 && // poll has not moved this value forward
//                        currentProducerIndex - capacity <= currentConsumerIndex && // test against cached cIndex
//                        currentProducerIndex - capacity <= (currentConsumerIndex = lvConsumerIndex())) { // test against latest cIndex
//                    // Extra check required to ensure [Queue.offer == false iff queue is full]
//                    // return false;
//                    // we spin if the queue is full
//                }
//
//                // another producer has moved the sequence by one, retry 2
//
//                // only producer will busySpin
////                busySpin(prevElementType, incomingType);
//            }
//            else {
//                // complimentary mode = unpark+pop off queue
//
//                cSeqOffset = calcSequenceOffset(currentConsumerIndex, mask);
//                final long seq = lvSequence(sBuffer, cSeqOffset); // LoadLoad
//
//                final long nextConsumerIndex = currentConsumerIndex + 1;
//                final long delta = seq - nextConsumerIndex;
//
//                if (delta == 0) {
//                    if (prevElementType != lpElementType(prevElementOffset)) {
//                        // inconsistent read of type.
//                        continue;
//                    }
//
//                    if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
//                        // Successful CAS: full barrier
//
//                        if (prevElementType != lpElementType(prevElementOffset)) {
//                            System.err.println("WAHT");
//
//                            // Move sequence ahead by capacity, preparing it for next offer
//                            // (seeing this value from a consumer will lead to retry 2)
//                            soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
//                            continue;
//                        }
//
//                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
//                        final long offset = calcElementOffset(currentConsumerIndex, mask);
//                        spElementType(offset, TYPE_FREE);
//
//                        final Object thread = lpElementThread(offset);
//                        final Object lpElement = lpElementNoCast(offset);
//
//                        if (incomingType == TYPE_CONSUMER) {
//                            // is already cancelled/fulfilled
//                            if (thread == null ||
//                                !casElementThread(offset, thread, null)) { // FULL barrier
//
//                                // Move sequence ahead by capacity, preparing it for next offer
//                                // (seeing this value from a consumer will lead to retry 2)
//                                soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
//
//                                continue;
//                            }
//
//                            Object returnItem = lpItem1(lpElement);
//                            spItem1(lpElement, null);
//
//                            UNSAFE.unpark(thread);
//
//                            // Move sequence ahead by capacity, preparing it for next offer
//                            // (seeing this value from a consumer will lead to retry 2)
//                            soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
//
//                            return returnItem;
//                        } else {
//                            // producer
//                            spItem1(lpElement, item);
//
//                            // is already cancelled/fulfilled
//                            if (thread == null ||
//                                !casElementThread(offset, thread, null)) { // FULL barrier
//
//                                // Move sequence ahead by capacity, preparing it for next offer
//                                // (seeing this value from a consumer will lead to retry 2)
//                                soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
//
//                                continue;
//                            }
//
//                            UNSAFE.unpark(thread);
//
//                            // Move sequence ahead by capacity, preparing it for next offer
//                            // (seeing this value from a consumer will lead to retry 2)
//                            soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
//
//                            return null;
//                        }
//                    }
//                    // failed cas, retry 1
//                } else if (delta < 0 && // slot has not been moved by producer
//                        currentConsumerIndex >= currentProducerIndex && // test against cached pIndex
//                        currentConsumerIndex == (currentProducerIndex = lvProducerIndex())) { // update pIndex if we must
//                    // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
//                    // return null;
//
//                    // spin
////                    busySpin(prevElementType, incomingType);
//                }
//
//                // another consumer beat us and moved sequence ahead, retry 2
//                // only producer busyspins
//            }
//        }
//    }

    private static final void busySpin() {
        ThreadLocalRandom randomYields = null; // bound if needed
        randomYields = ThreadLocalRandom.current();

        // busy spin for the amount of time (roughly) of a CPU context switch
        int spins = spinsFor();
//        int spins = 512;
        for (;;) {
            if (spins > 0) {
                --spins;
                if (randomYields.nextInt(CHAINED_SPINS) == 0) {
                    LockSupport.parkNanos(1); // occasionally yield
                }
            } else {
                break;
            }
        }
    }

    /**
     * Returns spin/yield value for a node with given predecessor and
     * data mode. See above for explanation.
     */
    private final static int spinsFor() {
//        if (MP && pred != null) {
//            if (previousNodeType != currentNodeType) {
//                // in the process of changing modes
//                return FRONT_SPINS + CHAINED_SPINS;
//            }
//            if (pred.isMatched()) {
                // at the front of the queue
                return FRONT_SPINS;
//            }
//            if (pred.waiter == null) {
//                // previous is spinning
//                return CHAINED_SPINS;
//            }
//        }
//
//        return 0;
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

    public int peekLast() {
        long currConsumerIndex;
        long currProducerIndex;

        while (true) {
            currConsumerIndex = lvConsumerIndex();
            currProducerIndex = lvProducerIndex();

            if (currConsumerIndex == currProducerIndex) {
                return TYPE_EMPTY;
            }

            final Object lpElementNoCast = lpElementNoCast(calcElementOffset(currConsumerIndex));
            if (lpElementNoCast == null) {
                continue;
            }

            return lpType(lpElementNoCast);
        }
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
}
