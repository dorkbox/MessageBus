package dorkbox.util.messagebus.common.simpleq.jctools;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import dorkbox.util.messagebus.common.simpleq.Node;

public final class SimpleQueue extends MpmcArrayQueueConsumerField<Node> {
    public static final int TYPE_EMPTY = 0;
    public static final int TYPE_CONSUMER = 1;
    public static final int TYPE_PRODUCER = 2;

    private static final long ITEM1_OFFSET;
    private static final long THREAD;
    private static final long TYPE;

    static {
        try {
            TYPE = UNSAFE.objectFieldOffset(Node.class.getField("type"));
            ITEM1_OFFSET = UNSAFE.objectFieldOffset(Node.class.getField("item1"));
            THREAD = UNSAFE.objectFieldOffset(Node.class.getField("thread"));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static final void spItem1(Object node, Object item) {
        UNSAFE.putObject(node, ITEM1_OFFSET, item);
    }

    private static final void soItem1(Object node, Object item) {
        UNSAFE.putOrderedObject(node, ITEM1_OFFSET, item);
    }

    private static final Object lpItem1(Object node) {
        return UNSAFE.getObject(node, ITEM1_OFFSET);
    }

    private static final Object lvItem1(Object node) {
        return UNSAFE.getObjectVolatile(node, ITEM1_OFFSET);
    }


    private static final void spType(Object node, int type) {
        UNSAFE.putInt(node, TYPE, type);
    }

    private static final int lpType(Object node) {
        return UNSAFE.getInt(node, TYPE);
    }

    private static final void spThread(Object node, Object thread) {
        UNSAFE.putObject(node, THREAD, thread);
    }

    private static final void soThread(Object node, Object thread) {
        UNSAFE.putOrderedObject(node, THREAD, thread);
    }

    private static final Object lpThread(Object node) {
        return UNSAFE.getObject(node, THREAD);
    }

    private static final Object lvThread(Object node) {
        return UNSAFE.getObjectVolatile(node, THREAD);
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


    /** The number of CPUs */
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin (doing nothing except polling a memory location) before giving up while waiting to eliminate an
     * operation. Should be zero on uniprocessors. On multiprocessors, this value should be large enough so that two threads exchanging
     * items as fast as possible block only when one of them is stalled (due to GC or preemption), but not much longer, to avoid wasting CPU
     * resources. Seen differently, this value is a little over half the number of cycles of an average context switch time on most systems.
     * The value here is approximately the average of those across a range of tested systems.
     */
    private static final int SPINS = MP ? 0 : 512; // orig: 2000

    /**
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    static final int maxTimedSpins = NCPU < 2 ? 0 : 32;

    /**
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     */
    static final int maxUntimedSpins = maxTimedSpins * 16;
    static final int negMaxUntimedSpins = -maxUntimedSpins;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    static final long spinForTimeoutThreshold = 1000L;
    private int size;

    public SimpleQueue(final int size) {
        super(1 << 17);
        this.size = size;
    }

    private final static ThreadLocal<Object> nodeThreadLocal = new ThreadLocal<Object>() {
        @Override
        protected Object initialValue() {
            return new Node();
        }
    };


    /**
     * PRODUCER
     */
    public void put(Object item) throws InterruptedException {

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
                    busySpin_InProgress();
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
                    busySpin_pushConflict();
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
                    busySpin_popConflict();
                    continue;
                }
            }
        }
    }

    /**
     * CONSUMER
     */
    public Object take() throws InterruptedException {
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
                    busySpin_InProgress();
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
                    busySpin_pushConflict();
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
                    busySpin_popConflict();
                    continue;
                }
            }
        }
    }

    /**
     * Spin for when the current thread is waiting for the item to be set. The producer index has incremented, but the
     * item isn't present yet.
     * @param random
     */
    private static final void busySpin_InProgress() {
//        ThreadLocalRandom randomYields = ThreadLocalRandom.current();
//
//        if (randomYields.nextInt(1) != 0) {
//////      LockSupport.parkNanos(1); // occasionally yield
//      Thread.yield();
//////      break;
//        }

        // busy spin for the amount of time (roughly) of a CPU context switch
        int spins = 128;
        for (;;) {
            if (spins > 0) {
//                if (randomYields.nextInt(CHAINED_SPINS) == 0) {
////                  LockSupport.parkNanos(1); // occasionally yield
//                  Thread.yield();
////                  break;
//                }
                --spins;
            } else {
                break;
            }
        }
    }

    private static final void busySpin_pushConflict() {
//        ThreadLocalRandom randomYields = ThreadLocalRandom.current();

        // busy spin for the amount of time (roughly) of a CPU context switch
        int spins = 256;
        for (;;) {
            if (spins > 0) {
//                if (randomYields.nextInt(1) != 0) {
//////                    LockSupport.parkNanos(1); // occasionally yield
//                    Thread.yield();
////                    break;
//                }
                --spins;
            } else {
                break;
            }
        }
    }

    private static final void busySpin_popConflict() {
//        ThreadLocalRandom randomYields = ThreadLocalRandom.current();

        // busy spin for the amount of time (roughly) of a CPU context switch
        int spins = 64;
        for (;;) {
            if (spins > 0) {
//                if (randomYields.nextInt(1) != 0) {
//////                    LockSupport.parkNanos(1); // occasionally yield
//                    Thread.yield();
////                    break;
//                }
                --spins;
            } else {
                break;
            }
        }
    }

    private static final void busySpin2() {
        ThreadLocalRandom randomYields = ThreadLocalRandom.current();

        // busy spin for the amount of time (roughly) of a CPU context switch
        int spins = 64;
        for (;;) {
            if (spins > 0) {
                if (randomYields.nextInt(1) != 0) {
////                    LockSupport.parkNanos(1); // occasionally yield
                    Thread.yield();
//                    break;
                }
                --spins;
            } else {
                break;
            }
        }
    }

    private static final void busySpin(ThreadLocalRandom random) {
        // busy spin for the amount of time (roughly) of a CPU context switch
//        int spins = spinsFor();
        int spins = 128;
        for (;;) {
            if (spins > 0) {
                --spins;
                if (random.nextInt(CHAINED_SPINS) == 0) {
////                    LockSupport.parkNanos(1); // occasionally yield
//                    Thread.yield();
                    break;
                }
            } else {
                break;
            }
        }
    }

//    @Override
//    public boolean isEmpty() {
//        // Order matters!
//        // Loading consumer before producer allows for producer increments after consumer index is read.
//        // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
//        // nothing we can do to make this an exact method.
//        return lvConsumerIndex() == lvProducerIndex();
//    }

    public boolean hasPendingMessages() {
        // count the number of consumers waiting, it should be the same as the number of threads configured
//        return this.consumersWaiting.size() == this.numberConsumerThreads;
//        return false;

        long consumerIndex = lvConsumerIndex();
        long producerIndex = lvProducerIndex();

        if (consumerIndex != producerIndex) {
            final Object previousElement = lpElementNoCast(calcElementOffset(producerIndex-1));
            if (previousElement != null && lpType(previousElement) == TYPE_CONSUMER && consumerIndex + this.size == producerIndex) {
                return false;
            }
        }

        return true;
    }

    public void tryTransfer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
        // TODO Auto-generated method stub
    }

    public final void park(final Object node, final Thread myThread, final boolean timed, final long nanos) throws InterruptedException {
        ThreadLocalRandom randomYields = null; // bound if needed


//          long lastTime = timed ? System.nanoTime() : 0;
//          int spins = timed ? maxTimedSpins : maxUntimedSpins;
//          int spins = maxTimedSpins;
          int spins = 51200;

//                      if (timed) {
//                          long now = System.nanoTime();
//                          nanos -= now - lastTime;
//                          lastTime = now;
//                          if (nanos <= 0) {
////                              s.tryCancel(e);
//                              continue;
//                          }
//                      }

      for (;;) {
          if (lvThread(node) == null) {
              return;
          } else if (spins > 0) {
//              if (randomYields == null) {
//                  randomYields = ThreadLocalRandom.current();
//              } else if (randomYields.nextInt(spins) == 0) {
//                  Thread.yield();  // occasionally yield
//              }
              --spins;
          } else if (myThread.isInterrupted()) {
              Thread.interrupted();
              throw new InterruptedException();
          } else {
              // park can return for NO REASON (must check for thread values)
              UNSAFE.park(false, 0L);
          }
      }
  }

    public void unpark(Object node) {
        final Object thread = lpThread(node);
        soThread(node, null);
        UNSAFE.unpark(thread);
    }

    @Override
    public boolean offer(Node message) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Node poll() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Node peek() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int size() {
        // TODO Auto-generated method stub
        return 0;
    }
}
