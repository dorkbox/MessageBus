package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import com.lmax.disruptor.MessageHolder;

import dorkbox.util.messagebus.common.simpleq.jctools.MpmcArrayQueueConsumerField;

public final class SimpleQueue<M extends MessageHolder> extends MpmcArrayQueueConsumerField<Node<M>> {

    static {
        // Prevent rare disastrous classloading in first call to LockSupport.park.
        // See: https://bugs.openjdk.java.net/browse/JDK-8074773
        @SuppressWarnings("unused")
        Class<?> ensureLoaded = LockSupport.class;
        LockSupport.unpark(Thread.currentThread());
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

    private static final int SIZE = 1<<14;

    long p40, p41, p42, p43, p44, p45, p46;
    long p30, p31, p32, p33, p34, p35, p36, p37;


    private final int numberConsumerThreads;

    public SimpleQueue(int numberConsumerThreads, HandlerFactory<M> factory) {
        super(SIZE);
        this.numberConsumerThreads = numberConsumerThreads;

        // pre-fill our data structures

        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long[] sBuffer = this.sequenceBuffer;
        long pSeqOffset;
        long currentProducerIndex;

        for (currentProducerIndex = 0; currentProducerIndex < SIZE; currentProducerIndex++) {
            pSeqOffset = calcSequenceOffset(currentProducerIndex, mask);

            // on 64bit(no compressed oops) JVM this is the same as seqOffset
            final long elementOffset = calcElementOffset(currentProducerIndex, mask);
            spElement(elementOffset, new Node<M>(factory.newInstance()));
        }

        pSeqOffset = calcSequenceOffset(0, mask);
        soSequence(sBuffer, pSeqOffset, 0); // StoreStore
    }

    /**
     * PRODUCER
     */
    public Node<M> put() {
        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long capacity = this.mask + 1;
        final long[] sBuffer = this.sequenceBuffer;

        long currentConsumerIndex;
        long currentProducerIndex;
        long pSeqOffset;
        long cIndex = Long.MAX_VALUE;// start with bogus value, hope we don't need it

        while (true) {
            // Order matters!
            // Loading consumer before producer allows for producer increments after consumer index is read.
            // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
            // nothing we can do to make this an exact method.
            currentConsumerIndex = lvConsumerIndex(); // LoadLoad
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
                    final Node<M> e = lpElement(offset);

                    // if only item on Q, WAIT
                    if (currentConsumerIndex == currentProducerIndex && lvConsumerIndex() == lvProducerIndex()) {

                    } else {

                    }




                    // increment sequence by 1, the value expected by consumer
                    // (seeing this value from a producer will lead to retry 2)
                    soSequence(sBuffer, pSeqOffset, currentProducerIndex + 1); // StoreStore

                    return e;
                }
                // failed cas, retry 1
            } else if (delta < 0 && // poll has not moved this value forward
                    currentProducerIndex - capacity <= cIndex && // test against cached cIndex
                    currentProducerIndex - capacity <= (cIndex = lvConsumerIndex())) { // test against latest cIndex
                 // Extra check required to ensure [Queue.offer == false iff queue is full]
//                 return null;
                busySpin();
            }

            // another producer has moved the sequence by one, retry 2

            // only producer will busySpin if contention
//            busySpin();
        }
    }












    public void putOLD(Object message1) throws InterruptedException {
        // decrement count
        // <0: no consumers available, add to Q, park and wait
        // >=0: consumers available, get one from the parking lot

        Thread myThread = Thread.currentThread();
        for (;;) {
            final int count = this.currentCount.get();
            if (this.currentCount.compareAndSet(count, count - 1)) {
                if (count <= 0) {
                    // <=0: no consumers available (PUSH_P, PARK_P)
                    Node<M> producer = this.producersWaiting.put();
                    if (producer == null || producer.item == null) {
                        System.err.println("KAPOW");
                    }
                    producer.item.message1 = message1;

                    if (!park(producer, myThread)) {
                        throw new InterruptedException();
                    }

                    return;
                } else {
                    // >0: consumers available (TAKE_C, UNPARK_C)
                    Node<M> consumer = this.consumersWaiting.take();
                    while (consumer == null) {
//                            busySpin();
                        consumer = this.consumersWaiting.take();
                    }

                    consumer.item.message1 = message1;

                    unpark(consumer, myThread);
                    return;
                }
            }

            // contention
            busySpin();
        }
    }

    public void takeOLD(MessageHolder item) throws InterruptedException {
        // increment count
        // >=0: no producers available, park and wait
        //  <0: producers available, get one from the Q

        Thread myThread = Thread.currentThread();
        for (;;) {
            final int count = this.currentCount.get();
            if (this.currentCount.compareAndSet(count, count + 1)) {
                if (count >= 0) {
                    // >=0: no producers available (PUT_C, PARK_C)
                    Node<M> consumer = this.consumersWaiting.put();

                    if (!park(consumer, myThread)) {
                        throw new InterruptedException();
                    }
                    if (consumer.item == null || consumer.item.message1 == null) {
                        System.err.println("KAPOW");
                    }
                    item.message1 = consumer.item.message1;

                    return;
                } else {
                    //  <0: producers available (TAKE_P, UNPARK_P)
                    Node<M> producer = this.producersWaiting.take();
                    while (producer == null) {
//                            busySpin();
                        producer = this.producersWaiting.take();
                    }

                    item.message1 = producer.item.message1;
                    unpark(producer, myThread);

                    if (item.message1 == null) {
                        System.err.println("KAPOW");
                    }

                    return;
                }
            }

            // contention
            busySpin();
        }
    }

    /**
     * @param myThread
     * @return false if we were interrupted, true if we were unparked by another thread
     */
    private boolean park(Node<M> myNode, Thread myThread) {
        AtomicReference<Thread> waiter = myNode.waiter;
        Thread thread;

        for (;;) {
            thread = waiter.get();
            if (waiter.compareAndSet(thread, myThread)) {
                if (thread == null) {
                    // busy spin for the amount of time (roughly) of a CPU context switch
                    int spins = SPINS;
                    for (;;) {
                        if (spins > 0) {
                            --spins;
                        } else if (waiter.get() != myThread) {
                            break;
                        } else {
                            // park can return for NO REASON. Subsequent loops will hit this if it has not been ACTUALLY unlocked.
                            LockSupport.park();
                            if (myThread.isInterrupted()) {
                                waiter.set(null);
                                return false;
                            }
                            break;
                        }
                    }

//                    do {
//                        // park can return for NO REASON. Subsequent loops will hit this if it has not been ACTUALLY unlocked.
//                        LockSupport.park();
//                        if (myThread.isInterrupted()) {
//                            myNode.waiter.set(null);
//                            return false;
//                        }
//                    } while (myNode.waiter.get() == myThread);

                    waiter.set(null);
                    return true;
                } else if (thread != myThread) {
                    // no parking
                    return true;
                } else {
                    // contention
                    busySpin();
                }
            }
        }
    }

    /**
     * Unparks the other node (if it was waiting)
     */
    private void unpark(Node<M> otherNode, Thread myThread) {
        AtomicReference<Thread> waiter = otherNode.waiter;
        Thread thread;

        for (;;) {
            thread = waiter.get();
            if (waiter.compareAndSet(thread, myThread)) {
                if (thread == null) {
                    // no parking
                    return;
                } else if (thread != myThread) {
                    // park will always set the waiter back to null
                    LockSupport.unpark(thread);
                    return;
                } else {
                    // contention
                    busySpin();
                }
            }
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

    public boolean hasPendingMessages() {
        // count the number of consumers waiting, it should be the same as the number of threads configured
//        return this.consumersWaiting.size() == this.numberConsumerThreads;
        return false;
    }

    public void tryTransfer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
    }

    @Override
    public boolean offer(Node<M> message) {
        return false;
    }

    @Override
    public Node<M> poll() {
        return null;
    }

    @Override
    public Node<M> peek() {
        return null;
    }

    @Override
    public int size() {
        return 0;
    }
}
