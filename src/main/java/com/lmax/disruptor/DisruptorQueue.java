package com.lmax.disruptor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dorkbox.util.messagebus.MultiMBassador;
import dorkbox.util.messagebus.common.NamedThreadFactory;



public class DisruptorQueue {


    private static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();

    private final ExecutorService executor;

    // must be power of 2.
    private final int ringBufferSize = 1;

    private final RingBuffer<MessageHolder> ringBuffer;
    private WaitingWorkerPool<MessageHolder> workerPool;

    public DisruptorQueue(MultiMBassador mbassador, int numberOfThreads) {
//        numberOfThreads = 4;

        // only used to startup threads, can be replaced with static list of threads
        this.executor = new ThreadPoolExecutor(numberOfThreads, numberOfThreads, 0,
                        TimeUnit.NANOSECONDS,
                        new LinkedTransferQueue<Runnable>(),
                        new NamedThreadFactory("disruptor"));




        EventBusFactory factory = new EventBusFactory();
        PublicationExceptionHandler loggingExceptionHandler = new PublicationExceptionHandler(mbassador);

        WorkHandlerEarlyRelease<MessageHolder> handlers[] = new EventProcessor2[numberOfThreads];
        for (int i = 0; i < handlers.length; i++) {
            handlers[i] = new EventProcessor2(mbassador);
        }

        WaitStrategy consumerWaitStrategy = new BlockingWaitStrategy();
        WaitingMultiProducerSequencer sequencer = new WaitingMultiProducerSequencer(4, loggingExceptionHandler, consumerWaitStrategy);
        this.ringBuffer = new RingBuffer<MessageHolder>(factory, sequencer);


        SequenceBarrier sequenceBarrier = this.ringBuffer.newBarrier();
        this.workerPool = new WaitingWorkerPool<MessageHolder>(this.ringBuffer,
                                                        sequencer,
                                                        sequenceBarrier,
                                                        loggingExceptionHandler,
                                                        handlers);

        sequencer.addGatingSequences(this.workerPool.getWorkerSequences()); // to notify our consumers (if they are blocking) of a new element
        this.workerPool.start(this.executor);
    }

    public void transfer(Object message1) throws InterruptedException {
        // put this on the disruptor ring buffer
        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;

        // setup the job
        final long seq = ringBuffer.next();
        try {
//            System.err.println("+(" + seq + ") " + message1);
            MessageHolder eventJob = ringBuffer.get(seq);
            eventJob.messageType = MessageTypeOLD.ONE;
            eventJob.message1 = message1;
//            eventJob.message2 = message2;
//            eventJob.message3 = message3;
        } catch (Exception e) {
            e.printStackTrace();
//            handlePublicationError(new PublicationError()
//            .setMessage("Error while adding an asynchronous message")
//            .setCause(e)
//            .setPublishedObject(message1));
        } finally {
            // always publish the job
            ringBuffer.publish(seq);
        }
    }

    public boolean hasPendingMessages() {
        final long cursor = this.ringBuffer.getCursor();
        Sequence[] workerSequences = this.workerPool.getWorkerSequences();
        for (Sequence s : workerSequences) {
            if (cursor > s.get())
            {
                return true;
            }
        }

        return false;
    }

    public void tryTransfer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
        // TODO Auto-generated method stub

    }
}
