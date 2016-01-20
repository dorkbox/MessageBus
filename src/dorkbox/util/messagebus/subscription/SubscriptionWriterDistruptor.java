package dorkbox.util.messagebus.subscription;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.LiteBlockingWaitStrategy;
import com.lmax.disruptor.PhasedBackoffWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.Sequencer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.WorkProcessor;
import dorkbox.util.messagebus.common.thread.NamedThreadFactory;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.publication.disruptor.PublicationExceptionHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;


/**
 * Objective of this class is to conform to the "single writer principle", in order to maintain CLEAN AND SIMPLE concurrency for the
 * subscriptions. Even with concurrent hashMaps, there is still locks happening during contention.
 */
public
class SubscriptionWriterDistruptor {

    private WorkProcessor workProcessor;
    private SubscriptionHandler handler;
    private RingBuffer<SubscriptionHolder> ringBuffer;
    private Sequence workSequence;

    public
    SubscriptionWriterDistruptor(final ErrorHandlingSupport errorHandler, final SubscriptionManager subscriptionManager) {
        // Now we setup the disruptor and work handlers

        ExecutorService executor = new ThreadPoolExecutor(1, 1,
                                                          0, TimeUnit.NANOSECONDS, // handlers are never idle, so this doesn't matter
                                                          new java.util.concurrent.LinkedTransferQueue<Runnable>(),
                                                          new NamedThreadFactory("MessageBus-Subscriber"));

        final PublicationExceptionHandler<SubscriptionHolder> exceptionHandler = new PublicationExceptionHandler<SubscriptionHolder>(errorHandler);
        EventFactory<SubscriptionHolder> factory = new SubscriptionFactory();

        // setup the work handlers
        handler = new SubscriptionHandler(subscriptionManager);


//        final int BUFFER_SIZE = ringBufferSize * 64;
        final int BUFFER_SIZE = 1024 * 64;
//        final int BUFFER_SIZE = 1024;
//        final int BUFFER_SIZE = 32;
//        final int BUFFER_SIZE = 16;
//        final int BUFFER_SIZE = 8;
//        final int BUFFER_SIZE = 4;


        WaitStrategy consumerWaitStrategy;
//        consumerWaitStrategy = new LiteBlockingWaitStrategy(); // good one
//        consumerWaitStrategy = new BlockingWaitStrategy();
//        consumerWaitStrategy = new YieldingWaitStrategy();
//        consumerWaitStrategy = new BusySpinWaitStrategy();
//        consumerWaitStrategy = new SleepingWaitStrategy();
//        consumerWaitStrategy = new PhasedBackoffWaitStrategy(20, 50, TimeUnit.MILLISECONDS, new SleepingWaitStrategy(0));
//        consumerWaitStrategy = new PhasedBackoffWaitStrategy(20, 50, TimeUnit.MILLISECONDS, new BlockingWaitStrategy());
        consumerWaitStrategy = new PhasedBackoffWaitStrategy(2, 5, TimeUnit.MILLISECONDS, new LiteBlockingWaitStrategy());


        ringBuffer = RingBuffer.createMultiProducer(factory, BUFFER_SIZE, consumerWaitStrategy);
        SequenceBarrier sequenceBarrier = ringBuffer.newBarrier();


        // setup the WorkProcessors (these consume from the ring buffer -- one at a time) and tell the "handler" to execute the item
        workSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
        workProcessor = new WorkProcessor<SubscriptionHolder>(ringBuffer, sequenceBarrier, handler, exceptionHandler, workSequence);


        // setup the WorkProcessor sequences (control what is consumed from the ring buffer)
        final Sequence[] sequences = getSequences();
        ringBuffer.addGatingSequences(sequences);


        // configure the start position for the WorkProcessors, and start them
        final long cursor = ringBuffer.getCursor();
        workSequence.set(cursor);

        workProcessor.getSequence()
                     .set(cursor);

        executor.execute(workProcessor);
    }

    /**
     * @param listener is never null
     */
    public
    void subscribe(final Object listener) {
        long seq = ringBuffer.next();

        SubscriptionHolder job = ringBuffer.get(seq);
        job.doSubscribe = true;
        job.listener = listener;

        ringBuffer.publish(seq);
    }

    /**
     * @param listener is never null
     */
    public
    void unsubscribe(final Object listener) {
        long seq = ringBuffer.next();

        SubscriptionHolder job = ringBuffer.get(seq);
        job.doSubscribe = false;
        job.listener = listener;

        ringBuffer.publish(seq);
    }


    // gets the sequences used for processing work
    private
    Sequence[] getSequences() {
        final Sequence[] sequences = new Sequence[2];
        sequences[0] = workProcessor.getSequence();
        sequences[1] = workSequence;   // always add the work sequence
        return sequences;
    }


    public
    void start() {
    }

    public
    void shutdown() {
        workProcessor.halt();

        while (!handler.isShutdown()) {
            LockSupport.parkNanos(100L); // wait 100ms for handlers to quit
        }
    }

    public
    boolean hasPendingMessages() {
        // from workerPool.drainAndHalt()
        Sequence[] workerSequences = getSequences();
        final long cursor = ringBuffer.getCursor();
        for (Sequence s : workerSequences) {
            if (cursor > s.get()) {
                return true;
            }
        }

        return false;
    }
}
