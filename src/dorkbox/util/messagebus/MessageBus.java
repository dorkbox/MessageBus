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

import com.lmax.disruptor.EventBusFactory;
import com.lmax.disruptor.LiteBlockingWaitStrategy;
import com.lmax.disruptor.PublicationExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.Sequencer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.WorkProcessor;
import dorkbox.util.messagebus.common.adapter.StampedLock;
import dorkbox.util.messagebus.common.thread.NamedThreadFactory;
import dorkbox.util.messagebus.error.DefaultErrorHandler;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.publication.Publisher;
import dorkbox.util.messagebus.publication.PublisherExact;
import dorkbox.util.messagebus.publication.PublisherExactWithSuperTypes;
import dorkbox.util.messagebus.publication.PublisherExactWithSuperTypesAndVarity;
import dorkbox.util.messagebus.subscription.Subscriber;
import dorkbox.util.messagebus.subscription.SubscriptionManager;
import dorkbox.util.messagebus.utils.ClassUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The base class for all message bus implementations with support for asynchronous message dispatch
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
class MessageBus implements IMessageBus {
    private final ErrorHandlingSupport errorHandler;
//    private final ArrayBlockingQueue<Object> dispatchQueue;
//    private final LinkedTransferQueue<Object> dispatchQueue;
//    private final Collection<Thread> threads;

    private final ClassUtils classUtils;
    private final SubscriptionManager subscriptionManager;

    private final Publisher publisher;

    /**
     * Notifies the consumers during shutdown, that it's on purpose.
     */
    private volatile boolean shuttingDown = false;
    private WorkProcessor[] workProcessors;
    private MessageHandler[] handlers;
    private RingBuffer<MessageHolder> ringBuffer;
    private Sequence workSequence;

    /**
     * By default, will permit subTypes and Varity Argument matching, and will use half of CPUs available for dispatching async messages
     */
    public
    MessageBus() {
        this(Runtime.getRuntime().availableProcessors()/2);
    }

    /**
     * By default, will permit subTypes and Varity Argument matching
     *
     * @param numberOfThreads how many threads to use for dispatching async messages
     */
    public
    MessageBus(int numberOfThreads) {
        this(PublishMode.ExactWithSuperTypesAndVarity, numberOfThreads);
    }

    /**
     * By default, will use half of CPUs available for dispatching async messages
     *
     * @param publishMode     Specifies which publishMode to operate the publication of messages.
     */
    public
    MessageBus(final PublishMode publishMode) {
        this(publishMode, Runtime.getRuntime().availableProcessors()/2);
    }
    /**
     * @param publishMode     Specifies which publishMode to operate the publication of messages.
     * @param numberOfThreads how many threads to use for dispatching async messages
     */
    public
    MessageBus(final PublishMode publishMode, int numberOfThreads) {
        // round to the nearest power of 2
        numberOfThreads = 1 << (32 - Integer.numberOfLeadingZeros(getMinNumberOfThreads(numberOfThreads)));

        this.errorHandler = new DefaultErrorHandler();
//        this.dispatchQueue = new ArrayBlockingQueue<Object>(6);
//        this.dispatchQueue = new LinkedTransferQueue<Object>();
        classUtils = new ClassUtils(Subscriber.LOAD_FACTOR);

        final StampedLock lock = new StampedLock();


        final Subscriber subscriber;
            /**
             * Will subscribe and publish using all provided parameters in the method signature (for subscribe), and arguments (for publish)
             */
            subscriber = new Subscriber(errorHandler, classUtils);

        switch (publishMode) {
            case Exact:
                publisher = new PublisherExact(errorHandler, subscriber, lock);
                break;

            case ExactWithSuperTypes:
                publisher = new PublisherExactWithSuperTypes(errorHandler, subscriber, lock);
                break;

            case ExactWithSuperTypesAndVarity:
            default:
                publisher = new PublisherExactWithSuperTypesAndVarity(errorHandler, subscriber, lock);
        }

        this.subscriptionManager = new SubscriptionManager(numberOfThreads, subscriber, lock);


        // Now we setup the disruptor and work handlers

        ExecutorService executor = new ThreadPoolExecutor(numberOfThreads, numberOfThreads,
                                                          0, TimeUnit.NANOSECONDS, // handlers are never idle, so this doesn't matter
                                                          new java.util.concurrent.LinkedTransferQueue<Runnable>(),
                                                          new NamedThreadFactory("MessageBus"));

        final PublicationExceptionHandler<MessageHolder> exceptionHandler = new PublicationExceptionHandler<MessageHolder>(errorHandler);
        EventBusFactory factory = new EventBusFactory();

        // setup the work handlers
        handlers = new MessageHandler[numberOfThreads];
        for (int i = 0; i < handlers.length; i++) {
            handlers[i] = new MessageHandler(publisher);  // exactly one per thread is used
        }


//        final int BUFFER_SIZE = ringBufferSize * 64;
//        final int BUFFER_SIZE = 1024 * 64;
//        final int BUFFER_SIZE = 1024;
        final int BUFFER_SIZE = 8;


        WaitStrategy consumerWaitStrategy;
        consumerWaitStrategy = new LiteBlockingWaitStrategy();
//        consumerWaitStrategy = new BlockingWaitStrategy();
//        consumerWaitStrategy = new YieldingWaitStrategy();
//        consumerWaitStrategy = new BusySpinWaitStrategy();
//        consumerWaitStrategy = new SleepingWaitStrategy();
//        consumerWaitStrategy = new PhasedBackoffWaitStrategy(20, 50, TimeUnit.MILLISECONDS, new SleepingWaitStrategy(0));
//        consumerWaitStrategy = new PhasedBackoffWaitStrategy(20, 50, TimeUnit.MILLISECONDS, new BlockingWaitStrategy());
//        consumerWaitStrategy = new PhasedBackoffWaitStrategy(20, 50, TimeUnit.MILLISECONDS, new LiteBlockingWaitStrategy());


        ringBuffer = RingBuffer.createMultiProducer(factory, BUFFER_SIZE, consumerWaitStrategy);
        SequenceBarrier sequenceBarrier = ringBuffer.newBarrier();


        // setup the WorkProcessors (these consume from the ring buffer -- one at a time) and tell the "handler" to execute the item
        final int numWorkers = handlers.length;
        workProcessors = new WorkProcessor[numWorkers];
        workSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

        for (int i = 0; i < numWorkers; i++) {
            workProcessors[i] = new WorkProcessor<MessageHolder>(ringBuffer,
                                                                 sequenceBarrier,
                                                                 handlers[i],
                                                                 exceptionHandler, workSequence);
        }

        // setup the WorkProcessor sequences (control what is consumed from the ring buffer)
        final Sequence[] sequences = getSequences();
        ringBuffer.addGatingSequences(sequences);


        // configure the start position for the WorkProcessors, and start them
        final long cursor = ringBuffer.getCursor();
        workSequence.set(cursor);

        for (WorkProcessor<?> processor : workProcessors) {
            processor.getSequence()
                     .set(cursor);
            executor.execute(processor);
        }



//        this.threads = new ArrayDeque<Thread>(numberOfThreads);
//        final NamedThreadFactory threadFactory = new NamedThreadFactory("MessageBus");
//        for (int i = 0; i < numberOfThreads; i++) {
//
//            // each thread will run forever and process incoming message publication requests
//            Runnable runnable = new Runnable() {
//                @Override
//                public
//                void run() {
//                    ArrayBlockingQueue<?> IN_QUEUE = MessageBus.this.dispatchQueue;
////                    LinkedTransferQueue<?> IN_QUEUE = MessageBus.this.dispatchQueue;
//
//                    MultiNode node = new MultiNode();
//                    while (!MessageBus.this.shuttingDown) {
//                        try {
//                            //noinspection InfiniteLoopStatement
//                            while (true) {
////                                IN_QUEUE.take(node);
//                                final Object take = IN_QUEUE.take();
////                                Integer type = (Integer) MultiNode.lpMessageType(node);
////                                switch (type) {
////                                    case 1: {
//                                publish(take);
////                                        break;
////                                    }
////                                    case 2: {
////                                        publish(MultiNode.lpItem1(node), MultiNode.lpItem2(node));
////                                        break;
////                                    }
////                                    case 3: {
////                                        publish(MultiNode.lpItem1(node), MultiNode.lpItem2(node), MultiNode.lpItem3(node));
////                                        break;
////                                    }
////                                    default: {
////                                        publish(MultiNode.lpItem1(node));
////                                    }
////                                }
//                            }
//                        } catch (InterruptedException e) {
//                            if (!MessageBus.this.shuttingDown) {
//                                Integer type = (Integer) MultiNode.lpMessageType(node);
//                                switch (type) {
//                                    case 1: {
//                                        errorHandler.handlePublicationError(new PublicationError().setMessage(
//                                                        "Thread interrupted while processing message")
//                                                                                                  .setCause(e)
//                                                                                                  .setPublishedObject(MultiNode.lpItem1(node)));
//                                        break;
//                                    }
//                                    case 2: {
//                                        errorHandler.handlePublicationError(new PublicationError().setMessage(
//                                                        "Thread interrupted while processing message")
//                                                                                                  .setCause(e)
//                                                                                                  .setPublishedObject(MultiNode.lpItem1(node),
//                                                                                                                      MultiNode.lpItem2(node)));
//                                        break;
//                                    }
//                                    case 3: {
//                                        errorHandler.handlePublicationError(new PublicationError().setMessage(
//                                                        "Thread interrupted while processing message")
//                                                                                                  .setCause(e)
//                                                                                                  .setPublishedObject(MultiNode.lpItem1(node),
//                                                                                                                      MultiNode.lpItem2(node),
//                                                                                                                      MultiNode.lpItem3(node)));
//                                        break;
//                                    }
//                                    default: {
//                                        errorHandler.handlePublicationError(new PublicationError().setMessage(
//                                                        "Thread interrupted while processing message")
//                                                                                                  .setCause(e)
//                                                                                                  .setPublishedObject(MultiNode.lpItem1(node)));
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//            };
//
//            Thread runner = threadFactory.newThread(runnable);
//            this.threads.add(runner);
//        }
    }

    // gets the sequences used for processing work
    private
    Sequence[] getSequences() {
        final Sequence[] sequences = new Sequence[workProcessors.length + 1];
        for (int i = 0, size = workProcessors.length; i < size; i++) {
            sequences[i] = workProcessors[i].getSequence();
        }
        sequences[sequences.length - 1] = workSequence;   // always add the work sequence
        return sequences;
    }

    /**
     * Always return at least 2 threads
     */
    private static
    int getMinNumberOfThreads(final int numberOfThreads) {
        if (numberOfThreads < 2) {
            return 2;
        }
        return numberOfThreads;
    }

    @Override
    public
    void subscribe(final Object listener) {
        MessageBus.this.subscriptionManager.subscribe(listener);
    }

    @Override
    public
    void unsubscribe(final Object listener) {
        MessageBus.this.subscriptionManager.unsubscribe(listener);
    }

    @Override
    public
    void publish(final Object message) {
        publisher.publish(message);
    }

    @Override
    public
    void publish(final Object message1, final Object message2) {
        publisher.publish(message1, message2);
    }

    @Override
    public
    void publish(final Object message1, final Object message2, final Object message3) {
        publisher.publish(message1, message2, message3);
    }

    @Override
    public
    void publish(final Object[] messages) {
        publisher.publish(messages);
    }

    @Override
    public
    void publishAsync(final Object message) {
        if (message != null) {
            final long seq = ringBuffer.next();

            try {
                MessageHolder job = ringBuffer.get(seq);
                job.type = MessageType.ONE;
                job.message1 = message;
            } catch (Exception e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error while adding an asynchronous message")
                                                                          .setCause(e)
                                                                          .setPublishedObject(message));
            } finally {
                // always publish the job
                ringBuffer.publish(seq);
            }
        }
        else {
            throw new NullPointerException("Message cannot be null.");
        }

//        try {
//            this.dispatchQueue.put(message);
//        } catch (Exception e) {
//            errorHandler.handlePublicationError(new PublicationError().setMessage(
//                            "Error while adding an asynchronous message").setCause(e).setPublishedObject(message));
//        }
    }

    @Override
    public
    void publishAsync(final Object message1, final Object message2) {
//        if (message1 != null && message2 != null) {
//            try {
//                this.dispatchQueue.transfer(message1, message2);
//            } catch (Exception e) {
//                errorHandler.handlePublicationError(new PublicationError().setMessage(
//                                "Error while adding an asynchronous message").setCause(e).setPublishedObject(message1, message2));
//            }
//        }
//        else {
//            throw new NullPointerException("Messages cannot be null.");
//        }
    }

    @Override
    public
    void publishAsync(final Object message1, final Object message2, final Object message3) {
//        if (message1 != null || message2 != null | message3 != null) {
//            try {
//                this.dispatchQueue.transfer(message1, message2, message3);
//            } catch (Exception e) {
//                errorHandler.handlePublicationError(new PublicationError().setMessage(
//                                "Error while adding an asynchronous message").setCause(e).setPublishedObject(message1, message2, message3));
//            }
//        }
//        else {
//            throw new NullPointerException("Messages cannot be null.");
//        }
    }

    @Override
    public
    void publishAsync(final Object[] messages) {
//        if (messages != null) {
//            try {
//                this.dispatchQueue.transfer(messages, MessageType.ARRAY);
//            } catch (Exception e) {
//                errorHandler.handlePublicationError(new PublicationError().setMessage(
//                                "Error while adding an asynchronous message").setCause(e).setPublishedObject(messages));
//            }
//        }
//        else {
//            throw new NullPointerException("Message cannot be null.");
//        }
    }

    @Override
    public final
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

//        return !this.dispatchQueue.isEmpty();
    }

    @Override
    public final
    ErrorHandlingSupport getErrorHandler() {
        return errorHandler;
    }

    @Override
    public
    void start() {
        if (shuttingDown) {
            throw new Error("Unable to restart the MessageBus");
        }
        errorHandler.init();

//        for (Thread t : this.threads) {
//            t.start();
//        }

    }

    @Override
    public
    void shutdown() {
        this.shuttingDown = true;

//        for (Thread t : this.threads) {
//            t.interrupt();
//        }

        for (WorkProcessor<?> processor : workProcessors) {
            processor.halt();
        }

        for (MessageHandler handler : handlers) {
            while (!handler.isShutdown()) {
                LockSupport.parkNanos(100L); // wait 100ms for handlers to quit
            }
        }

        this.subscriptionManager.shutdown();
        this.classUtils.clear();
    }


}
