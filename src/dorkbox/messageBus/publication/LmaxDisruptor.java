/*
 * Copyright 2016 dorkbox, llc
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
package dorkbox.messageBus.publication;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import com.conversantmedia.util.concurrent.DisruptorBlockingQueue;
import com.conversantmedia.util.concurrent.SpinPolicy;
import com.esotericsoftware.reflectasm.MethodAccess;
import com.lmax.disruptor.LiteBlockingWaitStrategy;
import com.lmax.disruptor.PhasedBackoffWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.Sequencer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.WorkProcessor;

import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.publication.disruptor.EventBusFactory;
import dorkbox.messageBus.publication.disruptor.MessageHandler;
import dorkbox.messageBus.publication.disruptor.MessageHolder;
import dorkbox.messageBus.publication.disruptor.MessageType;
import dorkbox.messageBus.publication.disruptor.PublicationExceptionHandler;
import dorkbox.messageBus.subscription.asm.AsmInvocation;
import dorkbox.messageBus.subscription.reflection.ReflectionInvocation;
import dorkbox.util.NamedThreadFactory;

/**
 * By default, it is the calling thread that has to get the subscriptions, which the sync/async logic then uses.
 *
 * The exception to this rule is when checking/calling DeadMessage publication.
 *
 *
 * @author dorkbox, llc Date: 2/3/16
 */
public
class LmaxDisruptor implements Publisher {

    private final ThreadPoolExecutor threadExecutor;
    private final WorkProcessor[] workProcessors;
    private final MessageHandler[] handlers;

    private final RingBuffer<MessageHolder> ringBuffer;
    private final Sequence workSequence;

    public
    LmaxDisruptor(final int numberOfThreads, final ErrorHandler errorHandler) {
        // ALWAYS round to the nearest power of 2
        int minQueueCapacity = 1 << (32 - Integer.numberOfLeadingZeros(numberOfThreads));

//        final int minQueueCapacity = ringBufferSize * 64;
//        final int minQueueCapacity = 1024 * 64;
//        final int minQueueCapacity = 1024;
        //final int minQueueCapacity = 32; // this one was previous. The best is generally based on how many workers there are
//        final int minQueueCapacity = 16;
//        final int minQueueCapacity = 8;
//        final int minQueueCapacity = 4;


        threadExecutor = new ThreadPoolExecutor(numberOfThreads, numberOfThreads,
                                                0L, TimeUnit.MILLISECONDS,
                                                // this doesn't matter, since the LMAX disruptor is managing the produce/consume cycle
                                                new DisruptorBlockingQueue<Runnable>(minQueueCapacity, SpinPolicy.WAITING),
                                                new NamedThreadFactory("MessageBus", Thread.NORM_PRIORITY, true));


        // Now we setup the disruptor and work handlers

        final PublicationExceptionHandler<MessageHolder> exceptionHandler = new PublicationExceptionHandler<MessageHolder>(errorHandler);
        EventBusFactory factory = new EventBusFactory();

        // setup the work handlers
        Publisher syncPublisher = new DirectInvocation();
        handlers = new MessageHandler[numberOfThreads];
        for (int i = 0; i < handlers.length; i++) {
            handlers[i] = new MessageHandler(syncPublisher);  // exactly one per thread is used
        }

        WaitStrategy consumerWaitStrategy;
//        consumerWaitStrategy = new LiteBlockingWaitStrategy(); // good blocking one
//        consumerWaitStrategy = new BlockingWaitStrategy();
//        consumerWaitStrategy = new YieldingWaitStrategy();
//        consumerWaitStrategy = new BusySpinWaitStrategy();  // best for low latency
//        consumerWaitStrategy = new SleepingWaitStrategy();
//        consumerWaitStrategy = new PhasedBackoffWaitStrategy(20, 50, TimeUnit.MILLISECONDS, new SleepingWaitStrategy(0));
//        consumerWaitStrategy = new PhasedBackoffWaitStrategy(10, 50, TimeUnit.MILLISECONDS, new BlockingWaitStrategy());
        consumerWaitStrategy = new PhasedBackoffWaitStrategy(10, 50, TimeUnit.MILLISECONDS, new LiteBlockingWaitStrategy()); // good combo


        ringBuffer = RingBuffer.createMultiProducer(factory, minQueueCapacity, consumerWaitStrategy);
        SequenceBarrier sequenceBarrier = ringBuffer.newBarrier();


        // setup the WorkProcessors (these consume from the ring buffer -- one at a time) and tell the "handler" to execute the item
        workSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

        final int numWorkers = handlers.length;
        workProcessors = new WorkProcessor[numWorkers];

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
            processor.getSequence().set(cursor);
            threadExecutor.execute(processor);
        }
    }

    public
    LmaxDisruptor(final LmaxDisruptor publisher) {
        this.threadExecutor = publisher.threadExecutor;
        this.workProcessors = publisher.workProcessors;
        this.handlers = publisher.handlers;
        this.ringBuffer = publisher.ringBuffer;
        this.workSequence = publisher.workSequence;
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


    // ASM
    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message) {

        long seq = ringBuffer.next();

        MessageHolder job = ringBuffer.get(seq);

        job.type = MessageType.ASM_ONE;

        job.errorHandler = errorHandler;
        job.asmInvocation = invocation;
        job.listener = listener;
        job.handler = handler;
        job.handleIndex = handleIndex;

        job.message1 = message;

        ringBuffer.publish(seq);
    }

    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message1, final Object message2) {

        long seq = ringBuffer.next();

        MessageHolder job = ringBuffer.get(seq);

        job.type = MessageType.ASM_TWO;

        job.errorHandler = errorHandler;
        job.asmInvocation = invocation;
        job.listener = listener;
        job.handler = handler;
        job.handleIndex = handleIndex;

        job.message1 = message1;
        job.message2 = message2;

        ringBuffer.publish(seq);
    }

    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message1, final Object message2, final Object message3) {

        long seq = ringBuffer.next();

        MessageHolder job = ringBuffer.get(seq);

        job.type = MessageType.ASM_THREE;

        job.errorHandler = errorHandler;
        job.asmInvocation = invocation;
        job.listener = listener;
        job.handler = handler;
        job.handleIndex = handleIndex;

        job.message1 = message1;
        job.message2 = message2;
        job.message3 = message3;

        ringBuffer.publish(seq);
    }


    // REFLECTION
    @SuppressWarnings("Duplicates")
    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message) {

        long seq = ringBuffer.next();

        MessageHolder job = ringBuffer.get(seq);

        job.type = MessageType.REFLECT_ONE;

        job.errorHandler = errorHandler;
        job.reflectionInvocation = invocation;
        job.listener = listener;
        job.method = method;

        job.message1 = message;

        ringBuffer.publish(seq);
    }

    @SuppressWarnings("Duplicates")
    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message1, final Object message2) {

        long seq = ringBuffer.next();

        MessageHolder job = ringBuffer.get(seq);

        job.type = MessageType.REFLECT_TWO;

        job.errorHandler = errorHandler;
        job.reflectionInvocation = invocation;
        job.listener = listener;
        job.method = method;

        job.message1 = message1;
        job.message2 = message2;

        ringBuffer.publish(seq);
    }

    @SuppressWarnings("Duplicates")
    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message1, final Object message2, final Object message3) {

        long seq = ringBuffer.next();

        MessageHolder job = ringBuffer.get(seq);

        job.type = MessageType.REFLECT_THREE;

        job.errorHandler = errorHandler;
        job.reflectionInvocation = invocation;
        job.listener = listener;
        job.method = method;

        job.message1 = message1;
        job.message2 = message2;
        job.message3 = message3;

        ringBuffer.publish(seq);
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

    public
    void shutdown() {
        // let some messages finish publishing

        final long timeOutAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (hasPendingMessages()) {
            if (System.currentTimeMillis() > timeOutAt) {
                break;
            }
            LockSupport.parkNanos(100L); // wait 100ms for publication to finish
        }


        for (WorkProcessor<?> processor : workProcessors) {
            processor.halt();
        }


        // now make sure the thread executor is shutdown

        // This uses Thread.interrupt()
        threadExecutor.shutdownNow();
    }
}
