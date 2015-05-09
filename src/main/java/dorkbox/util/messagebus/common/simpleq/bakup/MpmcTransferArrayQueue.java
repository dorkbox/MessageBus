package dorkbox.util.messagebus.common.simpleq.bakup;

import java.util.concurrent.ThreadLocalRandom;

import org.jctools.queues.MpmcArrayQueue;
import org.jctools.util.UnsafeAccess;

public final class MpmcTransferArrayQueue extends MpmcArrayQueue<Object> {
    private static final int TYPE_EMPTY = 0;
    private static final int TYPE_CONSUMER = 1;
    private static final int TYPE_PRODUCER = 2;

    /** Is it multi-processor? */
    private static final boolean MP = Runtime.getRuntime().availableProcessors() > 1;

    private static final int INPROGRESS_SPINS = MP ? 32 : 0;
    private static final int PRODUCER_CAS_FAIL_SPINS = MP ? 512 : 0;
    private static final int CONSUMER_CAS_FAIL_SPINS = MP ? 512 : 0;


    /**
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    private static final int PARK_TIMED_SPINS = MP ? 32 : 0;

    /**
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     */
    private static final int PARK_UNTIMED_SPINS = PARK_TIMED_SPINS * 16;

    private final int consumerCount;

    public MpmcTransferArrayQueue(final int consumerCount) {
        super(1024); // must be power of 2
        this.consumerCount = consumerCount;
    }

    private final static ThreadLocal<Object> nodeThreadLocal = new ThreadLocal<Object>() {
        @Override
        protected Object initialValue() {
            return new Node();
        }
    };


    /**
     * PRODUCER method
     * <p>
     * Place an item on the queue, and wait (if necessary) for a corresponding consumer to take it. This will wait as long as necessary.
     */
    public final void transfer(final Object item) throws InterruptedException {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final Object[] buffer = this.buffer;
        final long[] sBuffer = this.sequenceBuffer;

        long consumerIndex;
        long producerIndex;
        int lastType;

        while (true) {
            consumerIndex = lvConsumerIndex();
            producerIndex = lvProducerIndex();

            if (consumerIndex == producerIndex) {
                lastType = TYPE_EMPTY;
            } else {
                final Object previousElement = lpElement(buffer, calcElementOffset(consumerIndex, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = Node.lpType(previousElement);
            }

            if (lastType != TYPE_CONSUMER) {
                // TYPE_EMPTY, TYPE_PRODUCER
                // empty or same mode = push+park onto queue
                long pSeqOffset = calcSequenceOffset(producerIndex, mask);
                final long seq = lvSequence(sBuffer, pSeqOffset); // LoadLoad
                final long delta = seq - producerIndex;

                if (delta == 0) {
                    // this is expected if we see this first time around
                    final long newProducerIndex = producerIndex + 1;
                    if (casProducerIndex(producerIndex, newProducerIndex)) {
                        // Successful CAS: full barrier

                        final Thread myThread = Thread.currentThread();
                        final Object node = nodeThreadLocal.get();

                        Node.spType(node, TYPE_PRODUCER);
                        Node.spThread(node, myThread);
                        Node.spItem(node, item);


                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(producerIndex, mask);
                        spElement(buffer, offset, node);


                        // increment sequence by 1, the value expected by consumer
                        // (seeing this value from a producer will lead to retry 2)
                        soSequence(sBuffer, pSeqOffset, newProducerIndex); // StoreStore

                        park(node, myThread);

                        return;
                    } else {
                        busySpin(PRODUCER_CAS_FAIL_SPINS);
                    }
                }
            }
            else {
                // TYPE_CONSUMER
                // complimentary mode = pop+unpark off queue
                long cSeqOffset = calcSequenceOffset(consumerIndex, mask);
                final long seq = lvSequence(sBuffer, cSeqOffset); // LoadLoad
                final long newConsumerIndex = consumerIndex + 1;
                final long delta = seq - newConsumerIndex;

                if (delta == 0) {
                    if (casConsumerIndex(consumerIndex, newConsumerIndex)) {
                        // Successful CAS: full barrier

                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(consumerIndex, mask);
                        final Object e = lpElement(buffer, offset);
                        spElement(buffer, offset, null);

                        // Move sequence ahead by capacity, preparing it for next offer
                        // (seeing this value from a consumer will lead to retry 2)
                        soSequence(sBuffer, cSeqOffset, mask + newConsumerIndex); // StoreStore

                        Node.spItem(e, item);
                        unpark(e);  // StoreStore

                        return;
                    } else {
                        busySpin(CONSUMER_CAS_FAIL_SPINS);
                    }
                }
            }
        }
    }

    /**
     * CONSUMER
     * <p>
     * Remove an item from the queue. If there are no items on the queue, wait for a producer to place an item on the queue. This will
     * as long as necessary.
     */
    public final Object take() throws InterruptedException {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final Object[] buffer = this.buffer;
        final long[] sBuffer = this.sequenceBuffer;

        long consumerIndex;
        long producerIndex;
        int lastType;

        while (true) {
            consumerIndex = lvConsumerIndex();
            producerIndex = lvProducerIndex();

            if (consumerIndex == producerIndex) {
                lastType = TYPE_EMPTY;
            } else {
                final Object previousElement = lpElement(buffer, calcElementOffset(consumerIndex, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = Node.lpType(previousElement);
            }

            if (lastType != TYPE_PRODUCER) {
                // TYPE_EMPTY, TYPE_CONSUMER
                // empty or same mode = push+park onto queue
                long pSeqOffset = calcSequenceOffset(producerIndex, mask);
                final long seq = lvSequence(sBuffer, pSeqOffset); // LoadLoad
                final long delta = seq - producerIndex;

                if (delta == 0) {
                    // this is expected if we see this first time around
                    final long newProducerIndex = producerIndex + 1;
                    if (casProducerIndex(producerIndex, newProducerIndex)) {
                        // Successful CAS: full barrier

                        final Thread myThread = Thread.currentThread();
                        final Object node = nodeThreadLocal.get();

                        Node.spType(node, TYPE_CONSUMER);
                        Node.spThread(node, myThread);

                        // the unpark thread sets our contents

                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(producerIndex, mask);
                        spElement(buffer, offset, node);


                        // increment sequence by 1, the value expected by consumer
                        // (seeing this value from a producer will lead to retry 2)
                        soSequence(sBuffer, pSeqOffset, newProducerIndex); // StoreStore

                        park(node, myThread);

                        Object item1 = Node.lvItem(node);
                        return item1;
                    } else {
                        busySpin(PRODUCER_CAS_FAIL_SPINS);
                    }
                }
            }
            else {
                // TYPE_PRODUCER
                // complimentary mode = pop+unpark off queue
                long cSeqOffset = calcSequenceOffset(consumerIndex, mask);
                final long seq = lvSequence(sBuffer, cSeqOffset); // LoadLoad
                final long newConsumerIndex = consumerIndex + 1;
                final long delta = seq - newConsumerIndex;

                if (delta == 0) {
                    if (casConsumerIndex(consumerIndex, newConsumerIndex)) {
                        // Successful CAS: full barrier

                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(consumerIndex, mask);
                        final Object e = lpElement(buffer, offset);
                        spElement(buffer, offset, null);

                        // Move sequence ahead by capacity, preparing it for next offer
                        // (seeing this value from a consumer will lead to retry 2)
                        soSequence(sBuffer, cSeqOffset, mask + newConsumerIndex); // StoreStore

                        final Object lvItem1 = Node.lpItem(e);
                        unpark(e); // StoreStore

                        return lvItem1;
                    } else {
                        busySpin(CONSUMER_CAS_FAIL_SPINS);
                    }
                }
            }
        }
    }

    // modification of super implementation, as to include a small busySpin on contention
    @Override
    public final boolean offer(Object item) {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long capacity = mask + 1;
        final Object[] buffer = this.buffer;
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
                final long newProducerIndex = producerIndex + 1;
                if (casProducerIndex(producerIndex, newProducerIndex)) {
                    // Successful CAS: full barrier

                    // on 64bit(no compressed oops) JVM this is the same as seqOffset
                    final long offset = calcElementOffset(producerIndex, mask);
                    spElement(buffer, offset, item);


                    // increment sequence by 1, the value expected by consumer
                    // (seeing this value from a producer will lead to retry 2)
                    soSequence(sBuffer, pSeqOffset, newProducerIndex); // StoreStore

                    return true;
                } else {
                    busySpin(PRODUCER_CAS_FAIL_SPINS);
                }
                // failed cas, retry 1
            } else if (delta < 0 && // poll has not moved this value forward
                            producerIndex - capacity <= consumerIndex && // test against cached cIndex
                            producerIndex - capacity <= (consumerIndex = lvConsumerIndex())) { // test against latest cIndex
                // Extra check required to ensure [Queue.offer == false iff queue is full]
                return false;
            }

            // another producer has moved the sequence by one, retry 2
        }
    }


    // modification of super implementation, as to include a small busySpin on contention
    @Override
    public Object poll() {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final Object[] buffer = this.buffer;
        final long[] sBuffer = this.sequenceBuffer;

        long consumerIndex;
        long cSeqOffset;
        long producerIndex = -1; // start with bogus value, hope we don't need it

        while (true) {
            consumerIndex = lvConsumerIndex(); // LoadLoad
            cSeqOffset = calcSequenceOffset(consumerIndex, mask);
            final long seq = lvSequence(sBuffer, cSeqOffset); // LoadLoad
            final long newConsumerIndex = consumerIndex + 1;
            final long delta = seq - newConsumerIndex;

            if (delta == 0) {
                if (casConsumerIndex(consumerIndex, newConsumerIndex)) {
                    // Successful CAS: full barrier

                    // on 64bit(no compressed oops) JVM this is the same as seqOffset
                    final long offset = calcElementOffset(consumerIndex, mask);
                    final Object e = lpElement(buffer, offset);
                    spElement(buffer, offset, null);

                    // Move sequence ahead by capacity, preparing it for next offer
                    // (seeing this value from a consumer will lead to retry 2)
                    soSequence(sBuffer, cSeqOffset, mask + newConsumerIndex); // StoreStore

                    return e;
                } else {
                    busySpin(CONSUMER_CAS_FAIL_SPINS);
                }
                // failed cas, retry 1
            } else if (delta < 0 && // slot has not been moved by producer
                            consumerIndex >= producerIndex && // test against cached pIndex
                            consumerIndex == (producerIndex = lvProducerIndex())) { // update pIndex if we must
                 // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
                 return null;
            }

            // another consumer beat us and moved sequence ahead, retry 2
        }
    }

    @Override
    public final boolean isEmpty() {
        // Order matters!
        // Loading consumer before producer allows for producer increments after consumer index is read.
        // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
        // nothing we can do to make this an exact method.
        return lvConsumerIndex() == lvProducerIndex();
    }

    @Override
    public Object peek() {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final Object[] buffer = this.buffer;

        long currConsumerIndex;
        Object e;
        do {
            currConsumerIndex = lvConsumerIndex();
            // other consumers may have grabbed the element, or queue might be empty
            e = lpElement(buffer, calcElementOffset(currConsumerIndex, mask));
            // only return null if queue is empty
        } while (e == null && currConsumerIndex != lvProducerIndex());
        return e;
    }

    @Override
    public int size() {
        /*
         * It is possible for a thread to be interrupted or reschedule between the read of the producer and
         * consumer indices, therefore protection is required to ensure size is within valid range. In the
         * event of concurrent polls/offers to this method the size is OVER estimated as we read consumer
         * index BEFORE the producer index.
         */
        long after = lvConsumerIndex();
        while (true) {
            final long before = after;
            final long currentProducerIndex = lvProducerIndex();
            after = lvConsumerIndex();
            if (before == after) {
                return (int) (currentProducerIndex - after);
            }
        }
    }

    public final boolean hasPendingMessages() {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final Object[] buffer = this.buffer;

        long consumerIndex;
        long producerIndex;

        while (true) {
            consumerIndex = lvConsumerIndex();
            producerIndex = lvProducerIndex();

            if (consumerIndex == producerIndex) {
                return true;
            } else {
                final Object previousElement = lpElement(buffer, calcElementOffset(consumerIndex, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                return Node.lpType(previousElement) != TYPE_CONSUMER || consumerIndex + this.consumerCount != producerIndex;
            }
        }
    }

    private static final void busySpin(int spins) {
        for (;;) {
            if (spins > 0) {
                --spins;
            } else {
                return;
            }
        }
    }

    @SuppressWarnings("null")
    private final void park(final Object node, final Thread myThread) throws InterruptedException {
        int spins = -1; // initialized after first item and cancel checks
        ThreadLocalRandom randomYields = null; // bound if needed

        for (;;) {
            if (Node.lvThread(node) == null) {
                return;
            } else if (myThread.isInterrupted()) {
                throw new InterruptedException();
            } else if (spins < 0) {
                spins = PARK_UNTIMED_SPINS;
                randomYields = ThreadLocalRandom.current();
            } else if (spins > 0) {
                if (randomYields.nextInt(1024) == 0) {
                    UnsafeAccess.UNSAFE.park(false, 1L);
                }
                --spins;
            } else {
                // park can return for NO REASON (must check for thread values)
                UnsafeAccess.UNSAFE.park(false, 0L);
            }
        }
    }

    private final void unpark(Object node) {
        final Object thread = Node.lpThread(node);
        Node.soThread(node, null);  // StoreStore
        UnsafeAccess.UNSAFE.unpark(thread);
    }
}
