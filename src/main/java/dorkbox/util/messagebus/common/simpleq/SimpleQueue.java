package dorkbox.util.messagebus.common.simpleq;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import com.lmax.disruptor.MessageHolder;

public final class SimpleQueue<M extends MessageHolder> {

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
    private static final int SPINS = NCPU == 1 ? 0 : 600;


    private final MpmcExchangerQueueAlt<M> consumersWaiting;
    private final MpmcExchangerQueueAlt<M> producersWaiting;

    private final AtomicInteger currentCount = new AtomicInteger(0);

    private final int numberConsumerThreads;

    public SimpleQueue(int numberConsumerThreads, HandlerFactory<M> factory) {

        this.numberConsumerThreads = numberConsumerThreads;
        this.consumersWaiting = new MpmcExchangerQueueAlt<M>(factory, 1<<14);
        this.producersWaiting = new MpmcExchangerQueueAlt<M>(factory, 1<<14);
    }

    public void transfer(Object message1) throws InterruptedException {
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

    public void take(MessageHolder item) throws InterruptedException {
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
        return this.consumersWaiting.size() == this.numberConsumerThreads;
    }

    public void tryTransfer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
    }
}
