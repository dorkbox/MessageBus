/*
 * Copyright 2019 dorkbox, llc
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
package dorkbox.messageBus.synchrony;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.LockSupport;

import org.vibur.objectpool.ConcurrentPool;
import org.vibur.objectpool.PoolService;
import org.vibur.objectpool.util.MultithreadConcurrentQueueCollection;

import dorkbox.messageBus.dispatch.Dispatch;
import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.error.PublicationError;
import dorkbox.messageBus.common.MessageType;
import dorkbox.util.NamedThreadFactory;

/**
 * By default, it is the calling thread that has to get the subscriptions, which the sync/async logic then uses.
 *
 * The exception to this rule is when checking/calling DeadMessage publication.
 *
 * This is similar in behavior to the disruptor in that it does not generate garbage, however the downside of this implementation is it is
 * slow, but faster than other messagebus implementations.
 *
 * Basically, the disruptor is fast + noGC.
 *
 * @author dorkbox, llc Date: 2/3/16
 */
public final
class AsyncZeroGC implements SynchronyZeroGC {

    private final Dispatch dispatch;
    private final BlockingQueue<MessageHolderZeroGC> dispatchQueue;

    // we use a pool to prevent garbage creation.
    private final PoolService<MessageHolderZeroGC> pool;

    private final Collection<Thread> threads;
    private final Collection<Boolean> shutdown;
    private final ErrorHandler errorHandler;

    /**
     * Notifies the consumers during shutdown that it's on purpose.
     */
    private volatile boolean shuttingDown = false;


    public
    AsyncZeroGC(final int numberOfThreads, final Dispatch dispatch, final BlockingQueue<MessageHolderZeroGC> dispatchQueue, final ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
        this.dispatch = dispatch;

        this.dispatchQueue = dispatchQueue;

        this.pool = new ConcurrentPool<MessageHolderZeroGC>(new MultithreadConcurrentQueueCollection<>(1024),
                                                            new MessageHolderZeroGCClassFactory(),
                                                            16, 1024, true);


        // each thread will run forever and process incoming message publication requests
        Runnable runnable = new Runnable() {
            @Override
            public
            void run() {
                AsyncZeroGC outsideThis = AsyncZeroGC.this;

                final Dispatch dispatch = outsideThis.dispatch;
                final BlockingQueue<MessageHolderZeroGC> queue = outsideThis.dispatchQueue;
                final PoolService<MessageHolderZeroGC> pool = outsideThis.pool;
                final ErrorHandler errorHandler = outsideThis.errorHandler;

                while (!outsideThis.shuttingDown) {
                    process(dispatch, queue, pool, errorHandler);
                }

                synchronized (shutdown) {
                    shutdown.add(Boolean.TRUE);
                }
            }
        };

        this.threads = new ArrayDeque<Thread>(numberOfThreads);
        this.shutdown = new ArrayList<Boolean>();

        final NamedThreadFactory threadFactory = new NamedThreadFactory("MessageBus");
        for (int i = 0; i < numberOfThreads; i++) {
            Thread thread = threadFactory.newThread(runnable);
            this.threads.add(thread);
            thread.start();
        }
    }

    @SuppressWarnings({"Duplicates", "unchecked"})
    private
    void process(final Dispatch dispatch, final BlockingQueue<MessageHolderZeroGC> queue,
                 final PoolService<MessageHolderZeroGC> pool, final ErrorHandler errorHandler) {

        try {
            MessageHolderZeroGC message = queue.take();

            int messageType = message.type;
            switch (messageType) {
                case MessageType.ONE: {
                    try {
                        dispatch.publish(message.message1);
                    } catch (Exception e) {
                        errorHandler.handlePublicationError(new PublicationError().setMessage("Exception during message dequeue.")
                                                                                  .setCause(e)
                                                                                  .setPublishedObject(message.message1));
                    } finally {
                        pool.restore(message);
                        message.pool1.restore(message.message1);
                    }
                    return;
                }
                case MessageType.TWO: {
                    try {
                        dispatch.publish(message.message1, message.message2);
                    } catch (Exception e) {
                        errorHandler.handlePublicationError(new PublicationError().setMessage("Exception during message dequeue.")
                                                                                  .setCause(e)
                                                                                  .setPublishedObject(message.message1, message.message2));
                    } finally {
                        pool.restore(message);
                        message.pool1.restore(message.message1);
                        message.pool2.restore(message.message2);
                    }

                    return;
                }
                case MessageType.THREE: {
                    try {
                        dispatch.publish(message.message1, message.message2, message.message3);
                    } catch (Exception e) {
                        errorHandler.handlePublicationError(new PublicationError().setMessage("Exception during message dequeue.")
                                                                                  .setCause(e)
                                                                                  .setPublishedObject(message.message1, message.message2, message.message3));
                    } finally {
                        pool.restore(message);
                        message.pool1.restore(message.message1);
                        message.pool2.restore(message.message2);
                        message.pool3.restore(message.message3);
                    }

                    //noinspection UnnecessaryReturnStatement
                    return;
                }
            }
        } catch (InterruptedException e) {
            if (!this.shuttingDown) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted exception during message dequeue.")
                                                                          .setCause(e)
                                                                          .setNoPublishedObject());
            }
        }
    }

    @Override
    public
    <T> void publish(final PoolService<T> pool, final T message) {
        try {
            MessageHolderZeroGC job = this.pool.take();

            job.type = MessageType.ONE;

            job.pool1 = pool;
            job.message1 = message;

            this.dispatchQueue.put(job);
        } catch (InterruptedException e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message queue.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message));
        }
    }

    @Override
    public
    <T1, T2> void publish(final PoolService<T1> pool1, final PoolService<T2> pool2,
                          final T1 message1, final T2 message2) {
        try {
            MessageHolderZeroGC job = pool.take();

            job.type = MessageType.TWO;

            job.pool1 = pool1;
            job.pool2 = pool2;

            job.message1 = message1;
            job.message2 = message2;

            this.dispatchQueue.put(job);
        } catch (InterruptedException e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message queue.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2));
        }
    }

    @Override
    public
    <T1, T2, T3> void publish(final PoolService<T1> pool1, final PoolService<T2> pool2, final PoolService<T3> pool3,
                              final T1 message1, final T2 message2, final T3 message3) {
        try {
            MessageHolderZeroGC job = pool.take();

            job.type = MessageType.THREE;

            job.pool1 = pool1;
            job.pool2 = pool2;
            job.pool3 = pool3;

            job.message1 = message1;
            job.message2 = message2;
            job.message3 = message3;

            this.dispatchQueue.put(job);
        } catch (InterruptedException e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message queue.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2, message3));
        }
    }

    @Override
    public
    boolean hasPendingMessages() {
        return !this.dispatchQueue.isEmpty();
    }

    @SuppressWarnings("Duplicates")
    @Override
    public
    void shutdown() {
        this.shuttingDown = true;

        for (Thread t : this.threads) {
            t.interrupt();
        }

        while (true) {
            synchronized (shutdown) {
                if (shutdown.size() == threads.size()) {
                    return;
                }
                LockSupport.parkNanos(100L); // wait 100ms for threads to quit
            }
        }
    }
}
