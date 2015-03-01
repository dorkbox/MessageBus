/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>A {@link WaitingWorkProcessor} wraps a single {@link WorkHandler}, effectively consuming the sequence
 * and ensuring appropriate barriers.</p>
 *
 * <p>Generally, this will be used as part of a {@link WaitingWorkerPool}.</p>
 *
 * @param <T> event implementation storing the details for the work to processed.
 */
public final class WaitingWorkProcessor<T>
    implements EventProcessor
{
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final RingBuffer<T> ringBuffer;
    private final SequenceBarrier sequenceBarrier;
    private final WorkHandlerEarlyRelease<? super T> workHandler;
    private final ExceptionHandler exceptionHandler;
    private final Sequence workSequence;

    private final EventReleaser eventReleaser = new EventReleaser()
    {
        @Override
        public void release()
        {
            WaitingWorkProcessor.this.sequence.set(Long.MAX_VALUE);
        }
    };
    private WaitStrategy publisherStrategy;

    /**
     * Construct a {@link WaitingWorkProcessor}.
     *
     * @param ringBuffer to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param sequenceBarrier
     * @param workHandler is the delegate to which events are dispatched.
     * @param exceptionHandler to be called back when an error occurs
     * @param workSequence from which to claim the next event to be worked on.  It should always be initialised
     * as {@link Sequencer#INITIAL_CURSOR_VALUE}
     */
    public WaitingWorkProcessor(final RingBuffer<T> ringBuffer,
                         final WaitStrategy publisherStrategy,
                         final SequenceBarrier sequenceBarrier,
                         final WorkHandlerEarlyRelease<? super T> workHandler,
                         final ExceptionHandler exceptionHandler,
                         final Sequence workSequence)
    {
        this.ringBuffer = ringBuffer;
        this.publisherStrategy = publisherStrategy;
        this.sequenceBarrier = sequenceBarrier;
        this.workHandler = workHandler;
        this.exceptionHandler = exceptionHandler;
        this.workSequence = workSequence;

        if (this.workHandler instanceof EventReleaseAware)
        {
            ((EventReleaseAware)this.workHandler).setEventReleaser(this.eventReleaser);
        }

        workHandler.setProcessor(this);
    }

    @Override
    public Sequence getSequence()
    {
        return this.sequence;
    }

    @Override
    public void halt()
    {
        this.running.set(false);
        this.sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return this.running.get();
    }

    /**
     * It is ok to have another thread re-run this method after a halt().
     *
     * @throws IllegalStateException if this processor is already running
     */
    @Override
    public void run()
    {
        if (!this.running.compareAndSet(false, true))
        {
            throw new IllegalStateException("Thread is already running");
        }
        this.sequenceBarrier.clearAlert();

        notifyStart();

        WaitStrategy publisherStrategy = this.publisherStrategy;
        boolean processedSequence = true;
        long cachedAvailableSequence = Long.MIN_VALUE;
        long nextSequence = this.sequence.get();
        T event = null;
        while (true)
        {
            try
            {
                // if previous sequence was processed - fetch the next sequence and set
                // that we have successfully processed the previous sequence
                // typically, this will be true
                // this prevents the sequence getting too far forward if an exception
                // is thrown from the WorkHandler
                if (processedSequence)
                {
                    processedSequence = false;
                    do
                    {
                        nextSequence = this.workSequence.get() + 1L;
                        // this tells the producer that we are done with our sequence, and that it can reuse it's spot in the ring buffer
                        this.sequence.set(nextSequence - 1L);
                    }
                    while (!this.workSequence.compareAndSet(nextSequence - 1L, nextSequence));
//                    publisherStrategy.signalAllWhenBlocking();
                }

                if (cachedAvailableSequence >= nextSequence)
                {
                    event = this.ringBuffer.get(nextSequence);
                    this.workHandler.onEvent(nextSequence, event);
                    processedSequence = true;
                }
                else
                {
                    cachedAvailableSequence = this.sequenceBarrier.waitFor(nextSequence);
                }
            }
            catch (final AlertException ex)
            {
                if (!this.running.get())
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {
                // handle, mark as processed, unless the exception handler threw an exception
                this.exceptionHandler.handleEventException(ex, nextSequence, event);
                processedSequence = true;
            }
        }

        notifyShutdown();

        this.running.set(false);
    }

    private void notifyStart()
    {
        if (this.workHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware)this.workHandler).onStart();
            }
            catch (final Throwable ex)
            {
                this.exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    private void notifyShutdown()
    {
        if (this.workHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware)this.workHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                this.exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }

    public void release(long sequence) {

    }
}
