package dorkbox.util.messagebus.common.simpleq;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import dorkbox.util.messagebus.common.simpleq.jctools.MpmcArrayTransferQueue;
import dorkbox.util.messagebus.common.simpleq.jctools.Pow2;

public final class SimpleQueue {
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

    private static final void soType(Object node, int type) {
        UNSAFE.putOrderedInt(node, TYPE, type);
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

    private static final boolean casThread(Object node, Object expect, Object newValue) {
        return UNSAFE.compareAndSwapObject(node, THREAD, expect, newValue);
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
    private static final int SPINS = NCPU == 1 ? 0 : 512; // orig: 2000

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
    static final int maxUntimedSpins = maxTimedSpins * 32;
    static final int negMaxUntimedSpins = -maxUntimedSpins;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    static final long spinForTimeoutThreshold = 1000L;

    private MpmcArrayTransferQueue queue;
    private MpmcArrayTransferQueue pool;

    public SimpleQueue(final int size) {
        int roundToPowerOfTwo = Pow2.roundToPowerOfTwo(size);

        this.queue = new MpmcArrayTransferQueue(roundToPowerOfTwo);
        this.pool = new MpmcArrayTransferQueue(roundToPowerOfTwo);

        for (int i=0;i<roundToPowerOfTwo;i++) {
            this.pool.put(new Node(), false, 0);
        }
    }

    /**
     * PRODUCER
     */
    public void put(Object item) throws InterruptedException {
        final MpmcArrayTransferQueue queue = this.queue;
        final MpmcArrayTransferQueue pool = this.pool;
        final Thread myThread = Thread.currentThread();

        while (true) {

            int lastType = queue.peekLast();

            switch (lastType) {
                case TYPE_EMPTY:
                case TYPE_CONSUMER:
//                case TYPE_EMPTY: {
//                    // empty = push+park onto queue
//                    final Object node = pool.take(false, 0);
//
//                    final Thread myThread = Thread.currentThread();
//                    spType(node, TYPE_PRODUCER);
//                    spThread(node, myThread);
//                    spItem1(node, item);
//
////                    queue.put(node, false, 0);
//                    if (!queue.putIfEmpty(node, false, 0)) {
//                        pool.put(node, false, 0);
//                        continue;
//                    }
//                    park(node, myThread, false, 0);
//
//                    return;
//                }
                case TYPE_PRODUCER: {
                    // same mode = push+park onto queue
                    final Object node = pool.take(false, 0);
                    spType(node, TYPE_PRODUCER);
                    spThread(node, myThread);
                    spItem1(node, item);

                    queue.put(node, false, 0);
                    park(node, myThread, false, 0);

                    return;
                }
//                case TYPE_CONSUMER: {
//                  // complimentary mode = unpark+pop off queue
//                  final Object node = queue.take(false, 0);
//
//                  final Object thread = lpThread(node);
//                  spItem1(node, item);
//                  soThread(node, null);
//
//                  pool.put(node, false, 0);
//                  unpark(thread);
//                  return;
//                }
            }
        }
    }


    /**
     * CONSUMER
     */
    public Object take() throws InterruptedException {
        final MpmcArrayTransferQueue queue = this.queue;
        final MpmcArrayTransferQueue pool = this.pool;
        final Thread myThread = Thread.currentThread();

        while (true) {
            int lastType = queue.peekLast();

            switch (lastType) {
                case TYPE_EMPTY:
                case TYPE_CONSUMER:
//                case TYPE_EMPTY: {
//                    // empty = push+park onto queue
//                    final Object node = pool.take(false, 0);
//
//                    final Thread myThread = Thread.currentThread();
//                    spType(node, TYPE_CONSUMER);
//                    spThread(node, myThread);
//
////                    queue.put(node, false, 0);
//                    if (!queue.putIfEmpty(node, false, 0)) {
//                        pool.put(node, false, 0);
//                        continue;
//                    }
//                    park(node, myThread, false, 0);
//
//                    Object lpItem1 = lpItem1(node);
//                    return lpItem1;
//                }
//                case TYPE_CONSUMER: {
//                    // same mode = push+park onto queue
//                    final Object node = pool.take(false, 0);
//
//                    final Thread myThread = Thread.currentThread();
//                    spType(node, TYPE_PRODUCER);
//                    spThread(node, myThread);
//
//                    queue.put(node, false, 0);
//                    park(node, myThread, false, 0);
//
//                    Object lpItem1 = lpItem1(node);
//                    return lpItem1;
//                }
                case TYPE_PRODUCER: {
                    // complimentary mode = unpark+pop off queue
                    final Object node = queue.take(false, 0);

                    final Object thread = lpThread(node);
                    final Object lvItem1 = lpItem1(node);

                    soThread(node, null);
                    unpark(node, thread);

                    pool.put(node, false, 0);
                    return lvItem1;
                }
            }
        }
    }

    private static final void busySpin2() {
        // busy spin for the amount of time (roughly) of a CPU context switch
        int spins = maxUntimedSpins;
        for (;;) {
            if (spins > 0) {
                --spins;
            } else {
                break;
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
        return false;
    }

    public void tryTransfer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
        // TODO Auto-generated method stub
    }

    public final void park(final Object node, final Thread myThread, final boolean timed, final long nanos) throws InterruptedException {
        if (casThread(node, null, myThread)) {
            // we won against the other thread

//          long lastTime = timed ? System.nanoTime() : 0;
//          int spins = timed ? maxTimedSpins : maxUntimedSpins;
          int spins = SPINS;

//                      if (timed) {
//                          long now = System.nanoTime();
//                          nanos -= now - lastTime;
//                          lastTime = now;
//                          if (nanos <= 0) {
////                              s.tryCancel(e);
//                              continue;
//                          }
//                      }

          // busy spin for the amount of time (roughly) of a CPU context switch
          // then park (if necessary)
          for (;;) {
              if (lvThread(node) != myThread) {
                  return;
              } else if (spins > 0) {
                  --spins;
////              } else if (spins > negMaxUntimedSpins) {
////                  --spins;
////                  LockSupport.parkNanos(1);
              } else {
                  // park can return for NO REASON. Subsequent loops will hit this if it has not been ACTUALLY unlocked.
                  LockSupport.park();

//                  if (myThread.isInterrupted()) {
//                      casThread(node, myThread, null);
//                      Thread.interrupted();
//                      throw new InterruptedException();
//                  }
              }
          }
    }
  }

    public void unpark(Object node, Object thread) {
        if (casThread(node, thread, Thread.currentThread())) {
        } else {
            UNSAFE.unpark(thread);
        }
    }
}
