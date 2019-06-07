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

import dorkbox.messageBus.common.MessageType;
import dorkbox.messageBus.dispatch.Dispatch;
import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.error.PublicationError;
import dorkbox.util.NamedThreadFactory;

/**
 * By default, it is the calling thread that has to get the subscriptions, which the sync/async logic then uses.
 *
 * The exception to this rule is when checking/calling DeadMessage publication.
 *
 * @author dorkbox, llc Date: 2/3/16
 */
public
class Async implements Synchrony {

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

    private final Dispatch dispatch;
    private final BlockingQueue<MessageHolder> dispatchQueue;

    private final Collection<Thread> threads;
    private final Collection<Boolean> shutdown;
    private final ErrorHandler errorHandler;

    /**
     * Notifies the consumers during shutdown that it's on purpose.
     */
    private volatile boolean shuttingDown = false;


    public
    Async(int numberOfThreads, final Dispatch dispatch, final BlockingQueue<MessageHolder> dispatchQueue, final ErrorHandler errorHandler) {
        this.dispatch = dispatch;

        // ALWAYS round to the nearest power of 2
        numberOfThreads = 1 << (32 - Integer.numberOfLeadingZeros(getMinNumberOfThreads(numberOfThreads) - 1));

        this.errorHandler = errorHandler;
        this.dispatchQueue = dispatchQueue;

        // each thread will run forever and process incoming message publication requests
        Runnable runnable = new Runnable() {
            @Override
            public
            void run() {
                final Async outsideThis = Async.this;

                final Dispatch dispatch = outsideThis.dispatch;
                final BlockingQueue<MessageHolder> queue = outsideThis.dispatchQueue;
                final ErrorHandler errorHandler = outsideThis.errorHandler;

                while (!outsideThis.shuttingDown) {
                    process(dispatch, queue, errorHandler);
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

    @SuppressWarnings("Duplicates")
    private
    void process(final Dispatch dispatch, final BlockingQueue<MessageHolder> queue, final ErrorHandler errorHandler) {

        try {
            MessageHolder message = queue.take();

            int messageType = message.type;
            switch (messageType) {
                case MessageType.ONE: {
                    try {
                        dispatch.publish(message.message1);
                    } catch (Exception e) {
                        errorHandler.handlePublicationError(new PublicationError().setMessage("Exception during message dequeue.")
                                                                                  .setCause(e)
                                                                                  .setPublishedObject(message.message1));
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
    void publish(final Object message1) {
        MessageHolder job = new MessageHolder();

        job.type = MessageType.ONE;

        job.message1 = message1;

        try {
            this.dispatchQueue.put(job);
        } catch (InterruptedException e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message queue.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1));
        }
    }

    @Override
    public
    void publish(final Object message1, final Object message2) {
        MessageHolder job = new MessageHolder();

        job.type = MessageType.TWO;

        job.message1 = message1;
        job.message2 = message2;

        try {
            this.dispatchQueue.put(job);
        } catch (InterruptedException e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Interrupted error during message queue.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2));
        }
    }

    @Override
    public
    void publish(final Object message1, final Object message2, final Object message3) {
        MessageHolder job = new MessageHolder();

        job.type = MessageType.THREE;

        job.message1 = message1;
        job.message2 = message2;
        job.message3 = message3;

        try {
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
