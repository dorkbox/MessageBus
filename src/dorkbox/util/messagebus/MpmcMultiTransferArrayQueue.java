/*
 * Copyright 2015 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.util.messagebus;

import dorkbox.util.messagebus.publication.disruptor.MessageType;
import org.jctools.queues.MpmcArrayQueue;
import org.jctools.util.UnsafeAccess;

import java.util.Random;
import static org.jctools.util.UnsafeRefArrayAccess.lpElement;
import static org.jctools.util.UnsafeRefArrayAccess.spElement;

@SuppressWarnings("Duplicates")
final
class MpmcMultiTransferArrayQueue extends MpmcArrayQueue<Object> {
    private static final int TYPE_EMPTY = 0;
    private static final int TYPE_CONSUMER = 1;
    private static final int TYPE_PRODUCER = 2;

    /**
     * Is it multi-processor?
     */
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

    private static final ThreadLocal<Object> nodeThreadLocal = new ThreadLocal<Object>() {
        @Override
        protected
        Object initialValue() {
            return new MultiNode();
        }
    };

    private static final ThreadLocal<Random> randomThreadLocal = new ThreadLocal<Random>() {
        @Override
        protected
        Random initialValue() {
            return new Random();
        }
    };

    private final int consumerCount;

    public
    MpmcMultiTransferArrayQueue(final int consumerCount) {
        super(1024); // must be power of 2
        this.consumerCount = consumerCount;
    }


    /**
     * PRODUCER method
     * <p>
     * Place an item on the queue, and wait as long as necessary for a corresponding consumer to take it.
     * <p>
     * The item can be a single object (MessageType.ONE), or an array object (MessageType.ARRAY)
     * </p>
     */
    public
    void transfer(final Object item, final int messageType) throws InterruptedException {
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
            }
            else {
                final Object previousElement = lpElement(buffer, calcElementOffset(consumerIndex, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = MultiNode.lpType(previousElement);
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

                        MultiNode.spType(node, TYPE_PRODUCER);
                        MultiNode.spThread(node, myThread);

                        MultiNode.spMessageType(node, messageType);
                        MultiNode.spItem1(node, item);


                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(producerIndex, mask);
                        spElement(buffer, offset, node);


                        // increment sequence by 1, the value expected by consumer
                        // (seeing this value from a producer will lead to retry 2)
                        soSequence(sBuffer, pSeqOffset, newProducerIndex); // StoreStore

                        park(node, myThread);

                        return;
                    }
                    else {
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

                        MultiNode.spMessageType(e, messageType);
                        MultiNode.spItem1(e, item);

                        unpark(e);  // StoreStore

                        return;
                    }
                    else {
                        busySpin(CONSUMER_CAS_FAIL_SPINS);
                    }
                }
            }
        }
    }


    /**
     * PRODUCER method
     * <p/>
     * Place two items in the same slot on the queue, and wait as long as necessary for a corresponding consumer to take it.
     */
    public
    void transfer(final Object item1, final Object item2) throws InterruptedException {
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
            }
            else {
                final Object previousElement = lpElement(buffer, calcElementOffset(consumerIndex, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = MultiNode.lpType(previousElement);
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

                        MultiNode.spType(node, TYPE_PRODUCER);
                        MultiNode.spThread(node, myThread);

                        MultiNode.spMessageType(node, MessageType.TWO);
                        MultiNode.spItem1(node, item1);
                        MultiNode.spItem2(node, item2);


                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(producerIndex, mask);
                        spElement(buffer, offset, node);


                        // increment sequence by 1, the value expected by consumer
                        // (seeing this value from a producer will lead to retry 2)
                        soSequence(sBuffer, pSeqOffset, newProducerIndex); // StoreStore

                        park(node, myThread);

                        return;
                    }
                    else {
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

                        MultiNode.spMessageType(e, MessageType.TWO);
                        MultiNode.spItem1(e, item1);
                        MultiNode.spItem2(e, item2);

                        unpark(e); // StoreStore

                        return;
                    }
                    else {
                        busySpin(CONSUMER_CAS_FAIL_SPINS);
                    }
                }
            }
        }
    }

    /**
     * PRODUCER method
     * <p/>
     * Place three items in the same slot on the queue, and wait as long as necessary for a corresponding consumer to take it.
     */
    public
    void transfer(final Object item1, final Object item2, final Object item3) throws InterruptedException {
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
            }
            else {
                final Object previousElement = lpElement(buffer, calcElementOffset(consumerIndex, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = MultiNode.lpType(previousElement);
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

                        MultiNode.spType(node, TYPE_PRODUCER);
                        MultiNode.spThread(node, myThread);

                        MultiNode.spMessageType(node, MessageType.THREE);
                        MultiNode.spItem1(node, item1);
                        MultiNode.spItem2(node, item2);
                        MultiNode.spItem3(node, item3);


                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(producerIndex, mask);
                        spElement(buffer, offset, node);


                        // increment sequence by 1, the value expected by consumer
                        // (seeing this value from a producer will lead to retry 2)
                        soSequence(sBuffer, pSeqOffset, newProducerIndex); // StoreStore

                        park(node, myThread);

                        return;
                    }
                    else {
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

                        MultiNode.spMessageType(e, MessageType.THREE);
                        MultiNode.spItem1(e, item1);
                        MultiNode.spItem2(e, item2);
                        MultiNode.spItem3(e, item3);

                        unpark(e); // StoreStore

                        return;
                    }
                    else {
                        busySpin(CONSUMER_CAS_FAIL_SPINS);
                    }
                }
            }
        }
    }


    /**
     * CONSUMER
     * <p/>
     * Remove an item from the queue. If there are no items on the queue, wait for a producer to place an item on the queue. This will
     * as long as necessary.
     * <p/>
     * This method does not depend on thread-local for node information, and so is more efficient.
     * <p/>
     * Also, the node used by this method will contain the data.
     */
    public
    void take(final MultiNode node) throws InterruptedException {
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
            }
            else {
                final Object previousElement = lpElement(buffer, calcElementOffset(consumerIndex, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                lastType = MultiNode.lpType(previousElement);
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
//                        final Object node = nodeThreadLocal.publish();

                        MultiNode.spType(node, TYPE_CONSUMER);
                        MultiNode.spThread(node, myThread);

                        // The unpark thread sets our contents

                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(producerIndex, mask);
                        spElement(buffer, offset, node);


                        // increment sequence by 1, the value expected by consumer
                        // (seeing this value from a producer will lead to retry 2)
                        soSequence(sBuffer, pSeqOffset, newProducerIndex); // StoreStore

                        park(node, myThread);
                        return;
                    }
                    else {
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

                        MultiNode.spMessageType(node, MultiNode.lvMessageType(e)); // LoadLoad
                        MultiNode.spItem1(node, MultiNode.lpItem1(e));
                        MultiNode.spItem2(node, MultiNode.lpItem2(e));
                        MultiNode.spItem3(node, MultiNode.lpItem3(e));

                        unpark(e);

                        return;
                    }
                    else {
                        busySpin(CONSUMER_CAS_FAIL_SPINS);
                    }
                }
            }
        }
    }


    // modification of super implementation, as to include a small busySpin on contention
    @Override
    public
    boolean offer(Object item) {
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
                else {
                    busySpin(PRODUCER_CAS_FAIL_SPINS);
                }
                // failed cas, retry 1
            }
            else if (delta < 0 && // poll has not moved this value forward
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
    public
    Object poll() {
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
                else {
                    busySpin(CONSUMER_CAS_FAIL_SPINS);
                }
                // failed cas, retry 1
            }
            else if (delta < 0 && // slot has not been moved by producer
                     consumerIndex >= producerIndex && // test against cached pIndex
                     consumerIndex == (producerIndex = lvProducerIndex())) { // update pIndex if we must
                // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
                return null;
            }

            // another consumer beat us and moved sequence ahead, retry 2
        }
    }

    @Override
    public
    boolean isEmpty() {
        // Order matters!
        // Loading consumer before producer allows for producer increments after consumer index is read.
        // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
        // nothing we can do to make this an exact method.
        return lvConsumerIndex() == lvProducerIndex();
    }

    @Override
    public
    Object peek() {
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
    public
    int size() {
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
                //noinspection NumericCastThatLosesPrecision
                return (int) (currentProducerIndex - after);
            }
        }
    }

    public
    boolean hasPendingMessages() {
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
            }
            else {
                final Object previousElement = lpElement(buffer, calcElementOffset(consumerIndex, mask));
                if (previousElement == null) {
                    // the last producer hasn't finished setting the object yet
                    busySpin(INPROGRESS_SPINS);
                    continue;
                }

                return MultiNode.lpType(previousElement) != TYPE_CONSUMER || consumerIndex + this.consumerCount != producerIndex;
            }
        }
    }

    private static
    void busySpin(int spins) {
        for (; ; ) {
            if (spins > 0) {
                --spins;
            }
            else {
                return;
            }
        }
    }

    @SuppressWarnings("null")
    private
    void park(final Object node, final Thread myThread) throws InterruptedException {
        int spins = -1; // initialized after first item and cancel checks
        Random randomYields = null; // bound if needed

        for (; ; ) {
            if (MultiNode.lvThread(node) == null) {
                return;
            }
            else if (myThread.isInterrupted()) {
                throw new InterruptedException();
            }
            else if (spins < 0) {
                spins = PARK_UNTIMED_SPINS;
                randomYields = randomThreadLocal.get();
            }
            else if (spins > 0) {
                if (randomYields.nextInt(1024) == 0) {
                    UnsafeAccess.UNSAFE.park(false, 1L);
                }
                --spins;
            }
            else {
                // park can return for NO REASON (must check for thread values)
                UnsafeAccess.UNSAFE.park(false, 0L);
            }
        }
    }

    private
    void unpark(Object node) {
        final Object thread = MultiNode.lpThread(node);
        MultiNode.soThread(node, null);  // StoreStore
        UnsafeAccess.UNSAFE.unpark(thread);
    }
}
