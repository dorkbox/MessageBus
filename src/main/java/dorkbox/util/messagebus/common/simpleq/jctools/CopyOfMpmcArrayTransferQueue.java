package dorkbox.util.messagebus.common.simpleq.jctools;

import static dorkbox.util.messagebus.common.simpleq.jctools.UnsafeAccess.UNSAFE;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

import dorkbox.util.messagebus.common.simpleq.Node;

public final class CopyOfMpmcArrayTransferQueue extends MpmcArrayQueueConsumerField<Node> {

    public static final int TYPE_FREE = 0;
    public static final int TYPE_CONSUMER = 1;
    public static final int TYPE_PRODUCER = 2;
    public static final int TYPE_CANCELED = 3;

//    private final static long THREAD;
//    private static final long IS_CONSUMER;
    private static final long ITEM1_OFFSET;

    static {
        try {
//            IS_CONSUMER = UNSAFE.objectFieldOffset(Node.class.getField("isConsumer"));
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

//    static final boolean lpType(Object node) {
//        return UNSAFE.getBoolean(node, IS_CONSUMER);
//    }
//
//    static final void spType(Object node, boolean isConsumer) {
//        UNSAFE.putBoolean(node, IS_CONSUMER, isConsumer);
//    }


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

    long p40, p41, p42, p43, p44, p45, p46;
    long p30, p31, p32, p33, p34, p35, p36, p37;

    /** Creates a {@code EliminationStack} that is initially empty. */
    public CopyOfMpmcArrayTransferQueue(final int size) {
        super(size);

        // pre-fill our data structures

        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        long currentProducerIndex;

        for (currentProducerIndex = 0; currentProducerIndex < size; currentProducerIndex++) {
            // on 64bit(no compressed oops) JVM this is the same as seqOffset
            final long elementOffset = calcElementOffset(currentProducerIndex, mask);
            spElementType(elementOffset, TYPE_FREE);
            soElement(elementOffset, new Node());
        }
    }

    public Object xfer(final Object item, final boolean timed, final long nanos, final int incomingType) throws InterruptedException {
        // empty or same mode = push+park onto queue
        // complimentary mode = unpark+pop off queue

        // local load of field to avoid repeated loads after volatile reads
        final long mask = this.mask;
        final long capacity = this.mask + 1;
        final long[] sBuffer = this.sequenceBuffer;

        long currentProducerIndex = -1; // start with bogus value, hope we don't need it
        long pSeqOffset;

        long currentConsumerIndex = Long.MAX_VALUE; // start with bogus value, hope we don't need it
        long cSeqOffset;

        int prevElementType;

        while (true) {
            currentConsumerIndex = lvConsumerIndex(); // LoadLoad
            currentProducerIndex = lvProducerIndex(); // LoadLoad


            // the sequence must be loaded before we get the previous node type, otherwise previousNodeType can be stale/
            pSeqOffset = calcSequenceOffset(currentProducerIndex, mask);
            final long pSeq = lvSequence(sBuffer, pSeqOffset); // LoadLoad

            // if the queue is EMPTY, this will return TYPE_FREE
            // other consumers may have grabbed the element, or queue might be empty
            // may be FREE or CONSUMER/PRODUCER
            final long prevElementOffset = calcElementOffset(currentProducerIndex - 1, mask);
            prevElementType = lpElementType(prevElementOffset);

            if (prevElementType == TYPE_CANCELED) {
                // pop off and continue.

                cSeqOffset = calcSequenceOffset(currentConsumerIndex, mask);
                final long seq = lvSequence(sBuffer, cSeqOffset); // LoadLoad

                final long nextConsumerIndex = currentConsumerIndex + 1;
                final long delta = seq - nextConsumerIndex;

                if (delta == 0) {
                    if (prevElementType != lpElementType(prevElementOffset)) {
                        // inconsistent read of type.
                        continue;
                    }

                    if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
                        // Successful CAS: full barrier

                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(currentConsumerIndex, mask);
                        spElementType(offset, TYPE_FREE);

                        // Move sequence ahead by capacity, preparing it for next offer
                        // (seeing this value from a consumer will lead to retry 2)
                        soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
                    }
                    // failed cas, retry 1
                } else if (delta < 0 && // slot has not been moved by producer
                        currentConsumerIndex >= currentProducerIndex && // test against cached pIndex
                        currentConsumerIndex == (currentProducerIndex = lvProducerIndex())) { // update pIndex if we must
                    // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
                    // return null;

                    // spin
                    busySpin(prevElementType, incomingType);
                }

                // another consumer beat us and moved sequence ahead, retry 2
                // only producer busyspins
                continue;
            }


            // it is possible that two threads check the queue at the exact same time,
            //      BOTH can think that the queue is empty, resulting in a deadlock between threads
            // it is ALSO possible that the consumer pops the previous node, and so we thought it was not-empty, when
            //      in reality, it is.

            if (prevElementType == TYPE_FREE || prevElementType == incomingType) {
                // empty or same mode = push+park onto queue

                if (timed && nanos <= 0) {
                    // can't wait
                    return null;
                }

                final long delta = pSeq - currentProducerIndex;
                if (delta == 0) {
                    if (prevElementType != lpElementType(prevElementOffset)) {
                        // inconsistent read of type.
                        continue;
                    }

                    // this is expected if we see this first time around
                    final long nextProducerIndex = currentProducerIndex + 1;
                    if (casProducerIndex(currentProducerIndex, nextProducerIndex)) {
                        // Successful CAS: full barrier

                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(currentProducerIndex, mask);

                        if (prevElementType == TYPE_FREE && currentProducerIndex != currentConsumerIndex) {
                            // inconsistent read

                            // try to undo, if possible.
                            if (!casProducerIndex(nextProducerIndex, currentProducerIndex)) {
                                spElementType(offset, TYPE_CANCELED);

                                // increment sequence by 1, the value expected by consumer
                                // (seeing this value from a producer will lead to retry 2)
                                soSequence(sBuffer, pSeqOffset, nextProducerIndex); // StoreStore
                            }

                            continue;
                        }

                        spElementType(offset, incomingType);
                        spElementThread(offset, Thread.currentThread());

                        if (incomingType == TYPE_CONSUMER) {
                            // increment sequence by 1, the value expected by consumer
                            // (seeing this value from a producer will lead to retry 2)
                            soSequence(sBuffer, pSeqOffset, nextProducerIndex); // StoreStore

                            park(offset, timed, nanos);

                            Object lpElement = lpElementNoCast(offset);
                            Object lpItem1 = lpItem1(lpElement);

                            return lpItem1;
                        } else {
                            // producer
                            Object lpElement = lpElementNoCast(offset);
                            spItem1(lpElement, item);

                            // increment sequence by 1, the value expected by consumer
                            // (seeing this value from a producer will lead to retry 2)
                            soSequence(sBuffer, pSeqOffset, nextProducerIndex); // StoreStore

                            park(offset, timed, nanos);

                            return null;
                        }
                    }
                    // failed cas, retry 1
                } else if (delta < 0 && // poll has not moved this value forward
                        currentProducerIndex - capacity <= currentConsumerIndex && // test against cached cIndex
                        currentProducerIndex - capacity <= (currentConsumerIndex = lvConsumerIndex())) { // test against latest cIndex
                    // Extra check required to ensure [Queue.offer == false iff queue is full]
                    // return false;
                    // we spin if the queue is full
                }

                // another producer has moved the sequence by one, retry 2

                // only producer will busySpin
                busySpin(prevElementType, incomingType);
            }
            else {
                // complimentary mode = unpark+pop off queue

                cSeqOffset = calcSequenceOffset(currentConsumerIndex, mask);
                final long seq = lvSequence(sBuffer, cSeqOffset); // LoadLoad

                final long nextConsumerIndex = currentConsumerIndex + 1;
                final long delta = seq - nextConsumerIndex;

                if (delta == 0) {
                    if (prevElementType != lpElementType(prevElementOffset)) {
                        // inconsistent read of type.
                        continue;
                    }

                    if (casConsumerIndex(currentConsumerIndex, nextConsumerIndex)) {
                        // Successful CAS: full barrier

                        if (prevElementType != lpElementType(prevElementOffset)) {
                            System.err.println("WAHT");

                            // Move sequence ahead by capacity, preparing it for next offer
                            // (seeing this value from a consumer will lead to retry 2)
                            soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore
                            continue;
                        }

                        // on 64bit(no compressed oops) JVM this is the same as seqOffset
                        final long offset = calcElementOffset(currentConsumerIndex, mask);
                        spElementType(offset, TYPE_FREE);

                        final Object thread = lpElementThread(offset);
                        final Object lpElement = lpElementNoCast(offset);

                        if (incomingType == TYPE_CONSUMER) {
                            // is already cancelled/fulfilled
                            if (thread == null ||
                                !casElementThread(offset, thread, null)) { // FULL barrier

                                // Move sequence ahead by capacity, preparing it for next offer
                                // (seeing this value from a consumer will lead to retry 2)
                                soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore

                                continue;
                            }

                            Object returnItem = lpItem1(lpElement);
                            spItem1(lpElement, null);

                            UNSAFE.unpark(thread);

                            // Move sequence ahead by capacity, preparing it for next offer
                            // (seeing this value from a consumer will lead to retry 2)
                            soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore

                            return returnItem;
                        } else {
                            // producer
                            spItem1(lpElement, item);

                            // is already cancelled/fulfilled
                            if (thread == null ||
                                !casElementThread(offset, thread, null)) { // FULL barrier

                                // Move sequence ahead by capacity, preparing it for next offer
                                // (seeing this value from a consumer will lead to retry 2)
                                soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore

                                continue;
                            }

                            UNSAFE.unpark(thread);

                            // Move sequence ahead by capacity, preparing it for next offer
                            // (seeing this value from a consumer will lead to retry 2)
                            soSequence(sBuffer, cSeqOffset, nextConsumerIndex + mask); // StoreStore

                            return null;
                        }
                    }
                    // failed cas, retry 1
                } else if (delta < 0 && // slot has not been moved by producer
                        currentConsumerIndex >= currentProducerIndex && // test against cached pIndex
                        currentConsumerIndex == (currentProducerIndex = lvProducerIndex())) { // update pIndex if we must
                    // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
                    // return null;

                    // spin
                    busySpin(prevElementType, incomingType);
                }

                // another consumer beat us and moved sequence ahead, retry 2
                // only producer busyspins
            }
        }
    }

    private static final void busySpin(int previousNodeType, int currentNodeType) {
        ThreadLocalRandom randomYields = null; // bound if needed
        randomYields = ThreadLocalRandom.current();

        // busy spin for the amount of time (roughly) of a CPU context switch
        int spins = spinsFor(previousNodeType, currentNodeType);
//        int spins = 512;
        for (;;) {
            if (spins > 0) {
                --spins;
                if (randomYields.nextInt(CHAINED_SPINS) == 0) {
                    LockSupport.parkNanos(1); // occasionally yield
                }
            } else {
                break;
            }
        }
    }

    /**
     * Returns spin/yield value for a node with given predecessor and
     * data mode. See above for explanation.
     */
    private final static int spinsFor(int previousNodeType, int currentNodeType) {
//        if (MP && pred != null) {
//            if (previousNodeType != currentNodeType) {
//                // in the process of changing modes
//                return FRONT_SPINS + CHAINED_SPINS;
//            }
//            if (pred.isMatched()) {
                // at the front of the queue
                return FRONT_SPINS;
//            }
//            if (pred.waiter == null) {
//                // previous is spinning
//                return CHAINED_SPINS;
//            }
//        }
//
//        return 0;
    }


    private final void park(long offset, final boolean timed, final long nanos) throws InterruptedException {
//      long lastTime = timed ? System.nanoTime() : 0;
//      int spins = timed ? maxTimedSpins : maxUntimedSpins;
      int spins = 2000;
      Thread myThread = Thread.currentThread();

//                  if (timed) {
//                      long now = System.nanoTime();
//                      nanos -= now - lastTime;
//                      lastTime = now;
//                      if (nanos <= 0) {
////                          s.tryCancel(e);
//                          continue;
//                      }
//                  }

      // busy spin for the amount of time (roughly) of a CPU context switch
      // then park (if necessary)
      for (;;) {
          if (lpElementThread(offset) == null) {
              return;
          } else if (spins > 0) {
              --spins;
//          } else if (spins > negMaxUntimedSpins) {
//              --spins;
//              LockSupport.parkNanos(1);
          } else {
              // park can return for NO REASON. Subsequent loops will hit this if it has not been ACTUALLY unlocked.
              LockSupport.park();

              if (myThread.isInterrupted()) {
                  casElementThread(offset, myThread, null);
                  Thread.interrupted();
                  throw new InterruptedException();
              }
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
