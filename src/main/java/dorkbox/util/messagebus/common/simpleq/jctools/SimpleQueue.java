package dorkbox.util.messagebus.common.simpleq.jctools;

import static dorkbox.util.messagebus.common.simpleq.jctools.Node.lpItem1;
import static dorkbox.util.messagebus.common.simpleq.jctools.Node.lpThread;
import static dorkbox.util.messagebus.common.simpleq.jctools.Node.lpType;
import static dorkbox.util.messagebus.common.simpleq.jctools.Node.lvItem1;
import static dorkbox.util.messagebus.common.simpleq.jctools.Node.lvThread;
import static dorkbox.util.messagebus.common.simpleq.jctools.Node.soItem1;
import static dorkbox.util.messagebus.common.simpleq.jctools.Node.soThread;
import static dorkbox.util.messagebus.common.simpleq.jctools.Node.spItem1;
import static dorkbox.util.messagebus.common.simpleq.jctools.Node.spThread;
import static dorkbox.util.messagebus.common.simpleq.jctools.Node.spType;
import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;


public final class SimpleQueue extends MpmcArrayQueueConsumerField<Object> implements TransferQueue<Object> {
    private static final int TYPE_EMPTY = 0;
    private static final int TYPE_CONSUMER = 1;
    private static final int TYPE_PRODUCER = 2;

    /** Is it multi-processor? */
    private static final boolean MP = Runtime.getRuntime().availableProcessors() > 1;

    private static int INPROGRESS_SPINS = MP ? 32 : 0;
    private static int PUSH_SPINS = MP ? 512 : 0;
    private static int POP_SPINS = MP ? 512 : 0;


    /**
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    private static int PARK_TIMED_SPINS = MP ? 32 : 0;

    /**
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     */
    private static int PARK_UNTIMED_SPINS = PARK_TIMED_SPINS * 16;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    private static final long SPIN_THRESHOLD = 1000L;

    private final int consumerCount;

    public SimpleQueue(final int consumerCount) {
        super(Pow2.roundToPowerOfTwo(consumerCount*Runtime.getRuntime().availableProcessors()));
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
    @Override
    public final void transfer(final Object item) {

        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long[] sBuffer = this.sequenceBuffer;

        long consumerIndex;
        long producerIndex;
        int lastType;

        while (true) {
            consumerIndex = lvConsumerIndex();
            producerIndex = lvProducerIndex();

            final Object previousElement;
            if (consumerIndex == producerIndex) {
                lastType = TYPE_EMPTY;
                previousElement = null;
            } else {
                previousElement = lpElementNoCast(calcElementOffset(producerIndex-1));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = lpType(previousElement);
            }

            switch (lastType) {
                case TYPE_EMPTY:
                case TYPE_PRODUCER: {
                    // empty or same mode = push+park onto queue
                    long pSeqOffset = calcSequenceOffset(producerIndex, mask);
                    final long seq = lvSequence(sBuffer, pSeqOffset); // LoadLoad
                    final long delta = seq - producerIndex;

                    if (delta == 0) {
                        // this is expected if we see this first time around
                        if (casProducerIndex(producerIndex, producerIndex + 1)) {
                            // Successful CAS: full barrier

                            final Thread myThread = Thread.currentThread();
                            final Object node = nodeThreadLocal.get();

                            spType(node, TYPE_PRODUCER);
                            spThread(node, myThread);
                            spItem1(node, item);


                            // on 64bit(no compressed oops) JVM this is the same as seqOffset
                            final long offset = calcElementOffset(producerIndex, mask);
                            spElement(offset, node);


                            // increment sequence by 1, the value expected by consumer
                            // (seeing this value from a producer will lead to retry 2)
                            soSequence(sBuffer, pSeqOffset, producerIndex + 1); // StoreStore

                            park(node, myThread, false, 0);
                            return;
                        }
                    }

                    // whoops, inconsistent state
                    busySpin(PUSH_SPINS);
                    continue;
                }
                case TYPE_CONSUMER: {
                    // complimentary mode = pop+unpark off queue
                    long cSeqOffset = calcSequenceOffset(consumerIndex, mask);
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

                            soItem1(e, item);
                            unpark(e);

                            return;
                        }
                    }

                    // whoops, inconsistent state
                    busySpin(POP_SPINS);
                    continue;
                }
            }
        }
    }

    /**
     * CONSUMER
     */
    @Override
    public final Object take() {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long[] sBuffer = this.sequenceBuffer;

        long consumerIndex;
        long producerIndex;
        int lastType;

        while (true) {
            consumerIndex = lvConsumerIndex();
            producerIndex = lvProducerIndex();

            final Object previousElement;
            if (consumerIndex == producerIndex) {
                lastType = TYPE_EMPTY;
                previousElement = null;
            } else {
                previousElement = lpElementNoCast(calcElementOffset(producerIndex-1));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = lpType(previousElement);
            }


            switch (lastType) {
                case TYPE_EMPTY:
                case TYPE_CONSUMER: {
                    // empty or same mode = push+park onto queue
                    long pSeqOffset = calcSequenceOffset(producerIndex, mask);
                    final long seq = lvSequence(sBuffer, pSeqOffset); // LoadLoad
                    final long delta = seq - producerIndex;

                    if (delta == 0) {
                        // this is expected if we see this first time around
                        if (casProducerIndex(producerIndex, producerIndex + 1)) {
                            // Successful CAS: full barrier

                            final Thread myThread = Thread.currentThread();
                            final Object node = nodeThreadLocal.get();

                            spType(node, TYPE_CONSUMER);
                            spThread(node, myThread);


                            // on 64bit(no compressed oops) JVM this is the same as seqOffset
                            final long offset = calcElementOffset(producerIndex, mask);
                            spElement(offset, node);


                            // increment sequence by 1, the value expected by consumer
                            // (seeing this value from a producer will lead to retry 2)
                            soSequence(sBuffer, pSeqOffset, producerIndex + 1); // StoreStore

                            park(node, myThread, false, 0);
                            Object item1 = lvItem1(node);

                            return item1;
                        }
                    }

                    // whoops, inconsistent state
                    busySpin(PUSH_SPINS);
                    continue;
                }
                case TYPE_PRODUCER: {
                    // complimentary mode = pop+unpark off queue
                    long cSeqOffset = calcSequenceOffset(consumerIndex, mask);
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

                            final Object lvItem1 = lpItem1(e);
                            unpark(e);

                            return lvItem1;
                        }
                    }

                    // whoops, inconsistent state
                    busySpin(POP_SPINS);
                    continue;
                }
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
    private final void park(final Object node, final Thread myThread, final boolean timed, long nanos) {
        long lastTime = timed ? System.nanoTime() : 0L;
        int spins = -1; // initialized after first item and cancel checks
        ThreadLocalRandom randomYields = null; // bound if needed

        for (;;) {
            if (lvThread(node) == null) {
                return;
            } else if (myThread.isInterrupted() || timed && nanos <= 0) {
                return;
            } else if (spins < 0) {
                if (timed) {
                    spins = PARK_TIMED_SPINS;
                } else {
                    spins = PARK_UNTIMED_SPINS;
                }

                if (spins > 0) {
                    randomYields = ThreadLocalRandom.current();
                }
            } else if (spins > 0) {
                if (randomYields.nextInt(256) == 0) {
                    Thread.yield();  // occasionally yield
                }
                --spins;
            } else if (timed) {
                long now = System.nanoTime();
                long remaining = nanos -= now - lastTime;
                if (remaining > 0) {
                    if (remaining < SPIN_THRESHOLD) {
                        busySpin(PARK_UNTIMED_SPINS);
                    } else {
                        UNSAFE.park(false, nanos);
                    }
                }
                lastTime = now;
            } else {
                // park can return for NO REASON (must check for thread values)
                UNSAFE.park(false, 0L);
            }
        }
    }

    private final void unpark(Object node) {
        final Object thread = lpThread(node);
        soThread(node, null);
        UNSAFE.unpark(thread);
    }

    @Override
    public final boolean isEmpty() {
        // Order matters!
        // Loading consumer before producer allows for producer increments after consumer index is read.
        // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
        // nothing we can do to make this an exact method.
        return lvConsumerIndex() == lvProducerIndex();
    }

    public final boolean hasPendingMessages() {
        // count the number of consumers waiting, it should be the same as the number of threads configured
        long consumerIndex = lvConsumerIndex();
        long producerIndex = lvProducerIndex();

        if (consumerIndex != producerIndex) {
            final Object previousElement = lpElementNoCast(calcElementOffset(producerIndex-1));
            if (previousElement != null && lpType(previousElement) == TYPE_CONSUMER && consumerIndex + this.consumerCount == producerIndex) {
                return false;
            }
        }

        return true;
    }








    public void tryTransfer(Runnable runnable, long timeout, TimeUnit unit) {
    }


    @Override
    public boolean offer(Object message) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Object poll() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Object peek() {
        long currConsumerIndex;
        Object e;
        do {
            currConsumerIndex = lvConsumerIndex();
            // other consumers may have grabbed the element, or queue might be empty
            e = lpElementNoCast(calcElementOffset(currConsumerIndex));
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












    @Override
    public void put(Object e) throws InterruptedException {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean offer(Object e, long timeout, TimeUnit unit) throws InterruptedException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Object poll(long timeout, TimeUnit unit) throws InterruptedException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int remainingCapacity() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int drainTo(Collection c) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int drainTo(Collection c, int maxElements) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public Object[] toArray(Object[] a) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean containsAll(Collection c) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean addAll(Collection c) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean removeAll(Collection c) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean retainAll(Collection c) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean tryTransfer(Object e) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean tryTransfer(Object e, long timeout, TimeUnit unit) throws InterruptedException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean hasWaitingConsumer() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public int getWaitingConsumerCount() {
        // TODO Auto-generated method stub
        return 0;
    }
}
