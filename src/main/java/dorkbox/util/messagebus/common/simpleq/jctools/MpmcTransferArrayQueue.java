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

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;

import org.jctools.queues.MpmcArrayQueue;
import org.jctools.util.UnsafeAccess;


public final class MpmcTransferArrayQueue extends MpmcArrayQueue<Object> implements TransferQueue<Object> {
    private static final int TYPE_EMPTY = 0;
    private static final int TYPE_CONSUMER = 1;
    private static final int TYPE_PRODUCER = 2;

    /** Is it multi-processor? */
    private static final boolean MP = Runtime.getRuntime().availableProcessors() > 1;

    private static final int INPROGRESS_SPINS = MP ? 32 : 0;
    private static final int PUSH_SPINS = MP ? 32 : 0;
    private static final int POP_SPINS = MP ? 512 : 0;


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

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    private static final long SPIN_THRESHOLD = 1000L;

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
    @Override
    public final void transfer(final Object item) {
        producerXfer(item, false, 0L);
    }

    /**
     * CONSUMER
     * <p>
     * Remove an item from the queue. If there are no items on the queue, wait for a producer to place an item on the queue. This will
     * as long as necessary
     */
    @Override
    public final Object take() {
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
                final Object previousElement = lpElement(buffer, calcElementOffset(producerIndex-1, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = lpType(previousElement);
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

                        spType(node, TYPE_CONSUMER);
                        spThread(node, myThread);


                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(producerIndex, mask);
                        spElement(buffer, offset, node);


                        // increment sequence by 1, the value expected by consumer
                        // (seeing this value from a producer will lead to retry 2)
                        soSequence(sBuffer, pSeqOffset, newProducerIndex); // StoreStore

                        park(node, myThread, false, 0L);
                        Object item1 = lvItem1(node);

                        return item1;
                    }
                }

                // whoops, inconsistent state
                busySpin(PUSH_SPINS);
            } else {
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

                        final Object lvItem1 = lpItem1(e);
                        unpark(e);

                        return lvItem1;
                    }
                }

                // whoops, inconsistent state
                busySpin(POP_SPINS);
            }
        }
    }

    /**
     * CONSUMER
     * <p>
     * Remove an item from the queue. If there are no items on the queue, wait for a producer to place an item on the queue. This will
     * as long as necessary
     */
    public final Object take(final Node node) {
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
                final Object previousElement = lpElement(buffer, calcElementOffset(producerIndex-1, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = lpType(previousElement);
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
//                        final Object node = nodeThreadLocal.get();

                        spType(node, TYPE_CONSUMER);
                        spThread(node, myThread);


                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(producerIndex, mask);
                        spElement(buffer, offset, node);


                        // increment sequence by 1, the value expected by consumer
                        // (seeing this value from a producer will lead to retry 2)
                        soSequence(sBuffer, pSeqOffset, newProducerIndex); // StoreStore

                        park(node, myThread, false, 0L);
                        Object item1 = lvItem1(node);

                        return item1;
                    }
                }

                // whoops, inconsistent state
                busySpin(PUSH_SPINS);
            } else {
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

                        final Object lvItem1 = lpItem1(e);
                        unpark(e);

                        return lvItem1;
                    }
                }

                // whoops, inconsistent state
                busySpin(POP_SPINS);
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
                }
                // failed cas, retry 1
            } else if (delta < 0 && // poll has not moved this value forward
                            producerIndex - capacity <= consumerIndex && // test against cached cIndex
                            producerIndex - capacity <= (consumerIndex = lvConsumerIndex())) { // test against latest cIndex
                // Extra check required to ensure [Queue.offer == false iff queue is full]
                return false;
            }

            // another producer has moved the sequence by one, retry 2
            busySpin(PUSH_SPINS);
        }
    }

    /**
     * Will busy spin until timeout
     */
    @Override
    public boolean offer(Object item, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long lastTime = System.nanoTime();

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
                }
                // failed cas, retry 1
            } else if (delta < 0 && // poll has not moved this value forward
                            producerIndex - capacity <= consumerIndex && // test against cached cIndex
                            producerIndex - capacity <= (consumerIndex = lvConsumerIndex())) { // test against latest cIndex
                // Extra check required to ensure [Queue.offer == false iff queue is full]

                long now = System.nanoTime();
                long remaining = nanos -= now - lastTime;
                lastTime = now;

                if (remaining > 0) {
                    if (remaining < SPIN_THRESHOLD) {
                        busySpin(PARK_UNTIMED_SPINS);
                    } else {
                        UnsafeAccess.UNSAFE.park(false, 1L);
                    }
                } else {
                    return false;
                }
            }

            // another producer has moved the sequence by one, retry 2
            busySpin(PUSH_SPINS);
        }
    }


    @Override
    public void put(Object item) throws InterruptedException {
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

                    return;
                }
                // failed cas, retry 1
            } else if (delta < 0 && // poll has not moved this value forward
                            producerIndex - capacity <= consumerIndex && // test against cached cIndex
                            producerIndex - capacity <= (consumerIndex = lvConsumerIndex())) { // test against latest cIndex
                // Extra check required to ensure [Queue.offer == false iff queue is full]
                // return false;
                busySpin(PUSH_SPINS);
            }

            // another producer has moved the sequence by one, retry 2
            busySpin(PUSH_SPINS);
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
                }
                // failed cas, retry 1
            } else if (delta < 0 && // slot has not been moved by producer
                            consumerIndex >= producerIndex && // test against cached pIndex
                            consumerIndex == (producerIndex = lvProducerIndex())) { // update pIndex if we must
                 // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
                 return null;
            }

            // another consumer beat us and moved sequence ahead, retry 2
            busySpin(POP_SPINS);
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

    @Override
    public boolean tryTransfer(Object item) {
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
                final Object previousElement = lpElement(buffer, calcElementOffset(producerIndex-1, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = lpType(previousElement);
            }

            if (lastType != TYPE_CONSUMER) {
                // TYPE_EMPTY, TYPE_PRODUCER
                // empty or same mode = push+park onto queue
                long pSeqOffset = calcSequenceOffset(producerIndex, mask);
                final long seq = lvSequence(sBuffer, pSeqOffset); // LoadLoad
                final long delta = seq - producerIndex;

                if (delta == 0) {
                    // don't add to queue, abort!!
                    return false;
                }

                // whoops, inconsistent state
                busySpin(PUSH_SPINS);
            } else {
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

                        soItem1(e, item);
                        unpark(e);

                        return true;
                    }
                }

                // whoops, inconsistent state
                busySpin(POP_SPINS);
            }
        }
    }

    @Override
    public boolean tryTransfer(Object item, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        return producerXfer(item, true, nanos);
    }

    @Override
    public Object poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long lastTime = System.nanoTime();

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
                }
                // failed cas, retry 1
            } else if (delta < 0 && // slot has not been moved by producer
                            consumerIndex >= producerIndex && // test against cached pIndex
                            consumerIndex == (producerIndex = lvProducerIndex())) { // update pIndex if we must
                 // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]

                long now = System.nanoTime();
                long remaining = nanos -= now - lastTime;
                lastTime = now;

                if (remaining > 0) {
                    if (remaining < SPIN_THRESHOLD) {
                        busySpin(PARK_UNTIMED_SPINS);
                    } else {
                        UnsafeAccess.UNSAFE.park(false, 1L);
                    }
                    // make sure to continue here (so we don't spin twice)
                    continue;
                } else {
                    return null;
                }
            }

            // another consumer beat us and moved sequence ahead, retry 2
            busySpin(POP_SPINS);
        }
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
    public boolean hasWaitingConsumer() {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final Object[] buffer = this.buffer;

        long consumerIndex;
        long producerIndex;

        while (true) {
            consumerIndex = lvConsumerIndex();
            producerIndex = lvProducerIndex();

            if (consumerIndex == producerIndex) {
                return false;
            } else {
                final Object previousElement = lpElement(buffer, calcElementOffset(producerIndex-1, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                return lpType(previousElement) == TYPE_CONSUMER;
            }
        }
    }

    @Override
    public int getWaitingConsumerCount() {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final Object[] buffer = this.buffer;

        long consumerIndex;
        long producerIndex;

        while (true) {
            consumerIndex = lvConsumerIndex();
            producerIndex = lvProducerIndex();

            if (consumerIndex == producerIndex) {
                return 0;
            } else {
                final Object previousElement = lpElement(buffer, calcElementOffset(producerIndex-1, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                if (lpType(previousElement) == TYPE_CONSUMER) {
                    return (int) (producerIndex - consumerIndex);
                } else {
                    return 0;
                }
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
                final Object previousElement = lpElement(buffer, calcElementOffset(producerIndex-1, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                return lpType(previousElement) != TYPE_CONSUMER || consumerIndex + this.consumerCount != producerIndex;
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


    /**
     * @return true if the park was correctly unparked. false if there was an interruption or a timed-park expired
     */
    @SuppressWarnings("null")
    private final boolean park(final Object node, final Thread myThread, final boolean timed, long nanos) {
        long lastTime = System.nanoTime();
        int spins = -1; // initialized after first item and cancel checks
        ThreadLocalRandom randomYields = null; // bound if needed

        for (;;) {
            if (lvThread(node) == null) {
                return true;
            } else if (myThread.isInterrupted() || timed && nanos <= 0) {
                return false;
            } else if (spins < 0) {
                if (timed) {
                    spins = PARK_TIMED_SPINS;
                } else {
                    spins = PARK_UNTIMED_SPINS;
                }

                randomYields = ThreadLocalRandom.current();
            } else if (spins > 0) {
                if (randomYields.nextInt(1024) == 0) {
                    UnsafeAccess.UNSAFE.park(false, 1L);
                }
                --spins;
            } else if (timed) {
                long now = System.nanoTime();
                long remaining = nanos -= now - lastTime;
                lastTime = now;
                if (remaining > 0) {
                    if (remaining < SPIN_THRESHOLD) {
                        // a park is too slow for this time, so just busy spin instead
                        busySpin(PARK_UNTIMED_SPINS);
                    } else {
                        UnsafeAccess.UNSAFE.park(false, nanos);
                    }
                } else {
                    return false;
                }
            } else {
                // park can return for NO REASON (must check for thread values)
                UnsafeAccess.UNSAFE.park(false, 0L);
            }
        }
    }

    private final void unpark(Object node) {
        final Object thread = lpThread(node);
        soThread(node, null);
        UnsafeAccess.UNSAFE.unpark(thread);
    }

    /**
     * @return true if the producer successfully transferred an item (either through waiting for a consumer that took it, or transferring
     *               directly to an already waiting consumer). false if the thread was interrupted, or it was timed and has expired.
     */
    private final boolean producerXfer(final Object item, final boolean timed, final long nanos) {
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
                final Object previousElement = lpElement(buffer, calcElementOffset(producerIndex-1, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = lpType(previousElement);
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

                        spType(node, TYPE_PRODUCER);
                        spThread(node, myThread);
                        spItem1(node, item);


                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(producerIndex, mask);
                        spElement(buffer, offset, node);


                        // increment sequence by 1, the value expected by consumer
                        // (seeing this value from a producer will lead to retry 2)
                        soSequence(sBuffer, pSeqOffset, newProducerIndex); // StoreStore

                        return park(node, myThread, timed, nanos);
                    }
                }

                // whoops, inconsistent state
                busySpin(PUSH_SPINS);
            } else {
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

                        soItem1(e, item);
                        unpark(e);

                        return true;
                    }
                }

                // whoops, inconsistent state
                busySpin(POP_SPINS);
            }
        }
    }
}
