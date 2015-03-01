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

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import com.lmax.disruptor.util.Util;

/**
 * WorkerPool contains a pool of {@link WaitingWorkProcessor}s that will consume sequences so jobs can be farmed out across a pool of workers.
 * Each of the {@link WaitingWorkProcessor}s manage and calls a {@link WorkHandler} to process the events.
 *
 * @param <T> event to be processed by a pool of workers
 */
public final class WaitingWorkerPool<T>
{
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Sequence workSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final RingBuffer<T> ringBuffer;
    // WorkProcessors are created to wrap each of the provided WorkHandlers
    private final WaitingWorkProcessor<?>[] workProcessors;

    /**
     * Create a worker pool to enable an array of {@link WorkHandler}s to consume published sequences.
     *
     * This option requires a pre-configured {@link RingBuffer} which must have {@link RingBuffer#addGatingSequences(Sequence...)}
     * called before the work pool is started.
     *
     * @param ringBuffer of events to be consumed.
     * @param sequenceBarrier on which the workers will depend.
     * @param sequenceBarrier2
     * @param exceptionHandler to callback when an error occurs which is not handled by the {@link WorkHandler}s.
     * @param workHandlers to distribute the work load across.
     */
    public WaitingWorkerPool(final RingBuffer<T> ringBuffer,
                      final WaitStrategy publisherStrategy,
                      final SequenceBarrier sequenceBarrier,
                      final ExceptionHandler exceptionHandler,
                      final WorkHandlerEarlyRelease<? super T>... workHandlers)
    {
        this.ringBuffer = ringBuffer;
        final int numWorkers = workHandlers.length;
        this.workProcessors = new WaitingWorkProcessor[numWorkers];

        for (int i = 0; i < numWorkers; i++)
        {
            this.workProcessors[i] = new WaitingWorkProcessor<T>(ringBuffer,
                                                     publisherStrategy,
                                                     sequenceBarrier,
                                                     workHandlers[i],
                                                     exceptionHandler,
                                                     this.workSequence);
        }
    }

    /**
     * Get an array of {@link Sequence}s representing the progress of the workers.
     *
     * @return an array of {@link Sequence}s representing the progress of the workers.
     */
    public Sequence[] getWorkerSequences()
    {
//        final Sequence[] sequences = new Sequence[this.workProcessors.length + 1];
        final Sequence[] sequences = new Sequence[this.workProcessors.length];
        for (int i = 0, size = this.workProcessors.length; i < size; i++)
        {
            sequences[i] = this.workProcessors[i].getSequence();
        }
//        sequences[sequences.length - 1] = this.workSequence;

        return sequences;
    }

    /**
     * Start the worker pool processing events in sequence.
     *
     * @param executor providing threads for running the workers.
     * @return the {@link RingBuffer} used for the work queue.
     * @throws IllegalStateException if the pool has already been started and not halted yet
     */
    public RingBuffer<T> start(final Executor executor)
    {
        if (!this.started.compareAndSet(false, true))
        {
            throw new IllegalStateException("WorkerPool has already been started and cannot be restarted until halted.");
        }

        final long cursor = this.ringBuffer.getCursor();
        this.workSequence.set(cursor);

        for (WaitingWorkProcessor<?> processor : this.workProcessors)
        {
            processor.getSequence().set(cursor);
            executor.execute(processor);
        }

        return this.ringBuffer;
    }

    /**
     * Wait for the {@link RingBuffer} to drain of published events then halt the workers.
     */
    public void drainAndHalt()
    {
        Sequence[] workerSequences = getWorkerSequences();
        while (this.ringBuffer.getCursor() > Util.getMinimumSequence(workerSequences))
        {
            Thread.yield();
        }

        for (WaitingWorkProcessor<?> processor : this.workProcessors)
        {
            processor.halt();
        }

        this.started.set(false);
    }

    /**
     * Halt all workers immediately at the end of their current cycle.
     */
    public void halt()
    {
        for (WaitingWorkProcessor<?> processor : this.workProcessors)
        {
            processor.halt();
        }

        this.started.set(false);
    }

    public boolean isRunning()
    {
        return this.started.get();
    }
}
