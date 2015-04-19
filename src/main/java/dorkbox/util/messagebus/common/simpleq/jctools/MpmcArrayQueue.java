/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.util.messagebus.common.simpleq.jctools;



/**
 * A Multi-Producer-Multi-Consumer queue based on a {@link ConcurrentCircularArrayQueue}. This implies that
 * any and all threads may call the offer/poll/peek methods and correctness is maintained. <br>
 * This implementation follows patterns documented on the package level for False Sharing protection.<br>
 * The algorithm for offer/poll is an adaptation of the one put forward by D. Vyukov (See <a
 * href="http://www.1024cores.net/home/lock-free-algorithms/queues/bounded-mpmc-queue">here</a>). The original
 * algorithm uses an array of structs which should offer nice locality properties but is sadly not possible in
 * Java (waiting on Value Types or similar). The alternative explored here utilizes 2 arrays, one for each
 * field of the struct. There is a further alternative in the experimental project which uses iteration phase
 * markers to achieve the same algo and is closer structurally to the original, but sadly does not perform as
 * well as this implementation.<br>
 * Tradeoffs to keep in mind:
 * <ol>
 * <li>Padding for false sharing: counter fields and queue fields are all padded as well as either side of
 * both arrays. We are trading memory to avoid false sharing(active and passive).
 * <li>2 arrays instead of one: The algorithm requires an extra array of longs matching the size of the
 * elements array. This is doubling/tripling the memory allocated for the buffer.
 * <li>Power of 2 capacity: Actual elements buffer (and sequence buffer) is the closest power of 2 larger or
 * equal to the requested capacity.
 * </ol>
 */
public class MpmcArrayQueue<T> extends MpmcArrayQueueConsumerField<T> {
    /** The number of CPUs */
    private static final int NCPU = Runtime.getRuntime().availableProcessors();


    /**
     * The number of times to spin (doing nothing except polling a memory location) before giving up while waiting to eliminate an
     * operation. Should be zero on uniprocessors. On multiprocessors, this value should be large enough so that two threads exchanging
     * items as fast as possible block only when one of them is stalled (due to GC or preemption), but not much longer, to avoid wasting CPU
     * resources. Seen differently, this value is a little over half the number of cycles of an average context switch time on most systems.
     * The value here is approximately the average of those across a range of tested systems.
     */
    private static final int SPINS = NCPU == 1 ? 0 : 600;

    long p40, p41, p42, p43, p44, p45, p46;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    public MpmcArrayQueue(final int capacity) {
        super(validateCapacity(capacity));
    }

    private static int validateCapacity(int capacity) {
        if(capacity < 2) {
            throw new IllegalArgumentException("Minimum size is 2");
        }
        return capacity;
    }

    @Override
    public boolean offer(final T e) {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long capacity = mask + 1;
        final long[] sBuffer = this.sequenceBuffer;

        long currentProducerIndex;
        long pSeqOffset;
        long cIndex = Long.MAX_VALUE;// start with bogus value, hope we don't need it

        while (true) {
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
                    spElement(offset, e);

                    // increment sequence by 1, the value expected by consumer
                    // (seeing this value from a producer will lead to retry 2)
                    soSequence(sBuffer, pSeqOffset, currentProducerIndex + 1); // StoreStore

                    return true;
                }
                // failed cas, retry 1
            } else if (delta < 0 && // poll has not moved this value forward
                    currentProducerIndex - capacity <= cIndex && // test against cached cIndex
                    currentProducerIndex - capacity <= (cIndex = lvConsumerIndex())) { // test against latest cIndex
                 // Extra check required to ensure [Queue.offer == false iff queue is full]
                 return false;
            }

            // another producer has moved the sequence by one, retry 2
            busySpin();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Because return null indicates queue is empty we cannot simply rely on next element visibility for poll
     * and must test producer index when next element is not visible.
     */
    @Override
    public T poll() {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long[] sBuffer = this.sequenceBuffer;

        long currentConsumerIndex;
        long cSeqOffset;
        long pIndex = -1; // start with bogus value, hope we don't need it

        while (true) {
            currentConsumerIndex = lvConsumerIndex(); // LoadLoad
            cSeqOffset = calcSequenceOffset(currentConsumerIndex, mask);
            final long seq = lvSequence(sBuffer, cSeqOffset); // LoadLoad
            final long delta = seq - (currentConsumerIndex + 1);

            if (delta == 0) {
                if (casConsumerIndex(currentConsumerIndex, currentConsumerIndex + 1)) {
                    // Successful CAS: full barrier

                    // on 64bit(no compressed oops) JVM this is the same as seqOffset
                    final long offset = calcElementOffset(currentConsumerIndex, mask);
                    final T e = lpElement(offset);
                    spElement(offset, null);

                    // Move sequence ahead by capacity, preparing it for next offer
                    // (seeing this value from a consumer will lead to retry 2)
                    soSequence(sBuffer, cSeqOffset, currentConsumerIndex + mask + 1);// StoreStore

                    return e;
                }
                // failed cas, retry 1
            } else if (delta < 0 && // slot has not been moved by producer
                    currentConsumerIndex >= pIndex && // test against cached pIndex
                    currentConsumerIndex == (pIndex = lvProducerIndex())) { // update pIndex if we must
                // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
                return null;
            }

            // another consumer beat us and moved sequence ahead, retry 2
            // only producer will busy spin
        }
    }

    @Override
    public T peek() {
        long currConsumerIndex;
        T e;
        do {
            currConsumerIndex = lvConsumerIndex();
            // other consumers may have grabbed the element, or queue might be empty
            e = lpElement(calcElementOffset(currConsumerIndex));
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
    public boolean isEmpty() {
        // Order matters!
        // Loading consumer before producer allows for producer increments after consumer index is read.
        // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
        // nothing we can do to make this an exact method.
        return lvConsumerIndex() == lvProducerIndex();
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
}
