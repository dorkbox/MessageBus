package dorkbox.util.messagebus.common.simpleq;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

import java.util.concurrent.atomic.AtomicReference;

import dorkbox.util.messagebus.common.simpleq.jctools.MpmcArrayQueueConsumerField;
import dorkbox.util.messagebus.common.simpleq.jctools.Pow2;

public final class MpmcExchangerQueue extends MpmcArrayQueueConsumerField<Node> {

//    private final static long THREAD;
//    private static final long TYPE_OFFSET;
    private static final long ITEM1_OFFSET;

    static {
        try {
//            TYPE_OFFSET = UNSAFE.objectFieldOffset(Node.class.getField("isConsumer"));
            ITEM1_OFFSET = UNSAFE.objectFieldOffset(Node.class.getField("item1"));
//            THREAD = UNSAFE.objectFieldOffset(Node.class.getField("thread"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static final void spItem1(Object node, Object item) {
        UNSAFE.putObject(node, ITEM1_OFFSET, item);
    }

    private static final Object lpItem1(Object node) {
        return UNSAFE.getObject(node, ITEM1_OFFSET);
    }

//    public final Thread get() {
//        return (Thread) UNSAFE.getObject(this, THREAD);
//    }
//
//
//    public final void set(Thread newValue) {
//        UNSAFE.putOrderedObject(this, THREAD, newValue);
//    }
//
//    protected final boolean compareAndSet(Object expect, Object newValue) {
//        return UNSAFE.compareAndSwapObject(this, THREAD, expect, newValue);
//    }

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

    /** The number of slots in the elimination array. */
    private static final int ARENA_LENGTH = Pow2.roundToPowerOfTwo((NCPU + 1) / 2);

    /** The mask value for indexing into the arena. */
    private static int ARENA_MASK = ARENA_LENGTH - 1;

    /** The number of times to step ahead, probe, and try to match. */
    private static final int LOOKAHEAD = Math.min(4, NCPU);

    /** The number of times to spin per lookahead step */
    private static final int SPINS_PER_STEP = SPINS / LOOKAHEAD;

    /** A marker indicating that the arena slot is free. */
    private static final Object FREE = null;

    /** A marker indicating that a thread is waiting in that slot to be transfered an element. */
    private static final Object WAITER = new Object();


    /** The arena where slots can be used to perform an exchange. */
    private final AtomicReference<Object>[] arena;


    long p40, p41, p42, p43, p44, p45, p46;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    /** Creates a {@code EliminationStack} that is initially empty. */
    public MpmcExchangerQueue(final int size) {
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

        this.arena = new PaddedAtomicReference[ARENA_LENGTH];
        for (int i = 0; i < ARENA_LENGTH; i++) {
            this.arena[i] = new PaddedAtomicReference<Object>();
        }
    }

    /**
     * PRODUCER
     */
    public void put(Object item) {
        final Thread thread = Thread.currentThread();

        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long capacity = this.mask + 1;
        final long[] sBuffer = this.sequenceBuffer;

//        long currentConsumerIndex;
        long currentProducerIndex;
        long pSeqOffset;
        long cIndex = Long.MAX_VALUE;// start with bogus value, hope we don't need it

        while (true) {
            // Order matters!
            // Loading consumer before producer allows for producer increments after consumer index is read.
            // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
            // nothing we can do to make this an exact method.
//            currentConsumerIndex = lvConsumerIndex(); // LoadLoad
            currentProducerIndex = lvProducerIndex(); // LoadLoad

            pSeqOffset = calcSequenceOffset(currentProducerIndex, mask);
            final long seq = lvSequence(sBuffer, pSeqOffset); // LoadLoad
            final long delta = seq - currentProducerIndex;

            if (delta == 0) {
                // this is expected if we see this first time around
                if (casProducerIndex(currentProducerIndex, currentProducerIndex + 1)) {
                    // Successful CAS: full barrier

                    // on 64bit(no compressed oops) JVM this is the same as seqOffset
                    final long offset = calcElementOffset(currentProducerIndex, mask);
                    Object lpElement = lpElementNoCast(offset);
                    spItem1(lpElement, item);

                    // increment sequence by 1, the value expected by consumer
                    // (seeing this value from a producer will lead to retry 2)
                    soSequence(sBuffer, pSeqOffset, currentProducerIndex + 1); // StoreStore

                    return;
                }
                // failed cas, retry 1
            } else if (delta < 0 && // poll has not moved this value forward
                    currentProducerIndex - capacity <= cIndex && // test against cached cIndex
                    currentProducerIndex - capacity <= (cIndex = lvConsumerIndex())) { // test against latest cIndex
                 // Extra check required to ensure [Queue.offer == false iff queue is full]
//                 return null;
            }

            // another producer has moved the sequence by one, retry 2

            // only producer will busySpin if contention
            busySpin();
        }
    }

    /**
     * CONSUMER
     */
    public Object take() {
        final Thread thread = Thread.currentThread();

        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long[] sBuffer = this.sequenceBuffer;

        long currentConsumerIndex;
//        long currentProducerIndex;
        long cSeqOffset;
        long pIndex = -1; // start with bogus value, hope we don't need it

        while (true) {
            // Order matters!
            // Loading consumer before producer allows for producer increments after consumer index is read.
            // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
            // nothing we can do to make this an exact method.
            currentConsumerIndex = lvConsumerIndex(); // LoadLoad
//            currentProducerIndex = lvProducerIndex(); // LoadLoad


            cSeqOffset = calcSequenceOffset(currentConsumerIndex, mask);
            final long seq = lvSequence(sBuffer, cSeqOffset); // LoadLoad
            final long delta = seq - (currentConsumerIndex + 1);

            if (delta == 0) {
                if (casConsumerIndex(currentConsumerIndex, currentConsumerIndex + 1)) {
                    // Successful CAS: full barrier

                    // on 64bit(no compressed oops) JVM this is the same as seqOffset
                    final long offset = calcElementOffset(currentConsumerIndex, mask);
                    final Object e = lpElementNoCast(offset);
                    Object item = lpItem1(e);

                    // Move sequence ahead by capacity, preparing it for next offer
                    // (seeing this value from a consumer will lead to retry 2)
                    soSequence(sBuffer, cSeqOffset, currentConsumerIndex + mask + 1); // StoreStore

                    return item;
                }
                // failed cas, retry 1
            } else if (delta < 0 && // slot has not been moved by producer
                    currentConsumerIndex >= pIndex && // test against cached pIndex
                    currentConsumerIndex == (pIndex = lvProducerIndex())) { // update pIndex if we must
                // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
//                return null;

                // contention.
                busySpin();
            }

            // another consumer beat us and moved sequence ahead, retry 2
            // only producer busyspins
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
}
