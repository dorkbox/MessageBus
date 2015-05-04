package dorkbox.util.messagebus.common.simpleq.jctools;

import java.util.concurrent.ThreadLocalRandom;

public final class MpmcArrayTransferQueue extends MpmcArrayQueueConsumerField<Node> {

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
                // return null;
                busySpin(); // empty, so busy spin
            }

            // another consumer beat us and moved sequence ahead, retry 2
            busySpin();
        }
    }

    private static final void busySpin() {
        ThreadLocalRandom randomYields = ThreadLocalRandom.current();

        // busy spin for the amount of time (roughly) of a CPU context switch
//        int spins = spinsFor();
        int spins = CHAINED_SPINS;
        for (;;) {
            if (spins > 0) {
                if (randomYields.nextInt(CHAINED_SPINS) == 0) {
//                    LockSupport.parkNanos(1); // occasionally yield
//                    Thread.yield();
                    break;
                }
                --spins;
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

//    public int peekLast() {
//        long currConsumerIndex;
//        long currProducerIndex;
//
//        while (true) {
//            currConsumerIndex = lvConsumerIndex();
//            currProducerIndex = lvProducerIndex();
//
//            if (currConsumerIndex == currProducerIndex) {
//                return TYPE_EMPTY;
//            }
//
//            final Object lpElementNoCast = lpElementNoCast(calcElementOffset(currConsumerIndex));
//            if (lpElementNoCast == null) {
//                continue;
//            }
//
//            return lpType(lpElementNoCast);
//        }
//    }

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
