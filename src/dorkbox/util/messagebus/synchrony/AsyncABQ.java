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
package dorkbox.util.messagebus.synchrony;

import dorkbox.util.messagebus.common.NamedThreadFactory;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.publication.Publisher;
import dorkbox.util.messagebus.subscription.Subscription;
import dorkbox.util.messagebus.synchrony.disruptor.MessageType;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * This is similar to the disruptor, however the downside of this implementation is that, while faster than the no-gc version, it
 * generates garbage (while the disruptor version does not).
 *
 * Basically, the disruptor is fast + noGC.
 *
 * @author dorkbox, llc Date: 2/3/16
 */
public final
class AsyncABQ implements Synchrony {

    private final ArrayBlockingQueue<MessageHolder> dispatchQueue;
    private final Collection<Thread> threads;

    /**
     * Notifies the consumers during shutdown, that it's on purpose.
     */
    private volatile boolean shuttingDown = false;


    public
    AsyncABQ(final int numberOfThreads,
             final ErrorHandlingSupport errorHandler,
             final Publisher publisher,
             final Synchrony syncPublication) {

        this.dispatchQueue = new ArrayBlockingQueue<MessageHolder>(1024);

        this.threads = new ArrayDeque<Thread>(numberOfThreads);
        final NamedThreadFactory threadFactory = new NamedThreadFactory("MessageBus");
        for (int i = 0; i < numberOfThreads; i++) {

            // each thread will run forever and process incoming message publication requests
            Runnable runnable = new Runnable() {
                @Override
                public
                void run() {
                    final ArrayBlockingQueue<MessageHolder> IN_QUEUE = AsyncABQ.this.dispatchQueue;
                    final Publisher publisher1 = publisher;
                    final Synchrony syncPublication1 = syncPublication;
                    final ErrorHandlingSupport errorHandler1 = errorHandler;

                    MessageHolder event = null;
                    int messageType = MessageType.ONE;
                    Object message1 = null;
                    Object message2 = null;
                    Object message3 = null;

                    while (!AsyncABQ.this.shuttingDown) {
                        try {
                            event = IN_QUEUE.take();
                            messageType = event.type;
                            message1 = event.message1;
                            message2 = event.message2;
                            message3 = event.message3;

                            switch (messageType) {
                                case MessageType.ONE: {
                                    publisher1.publish(syncPublication1, message1);
                                    break;
                                }
                                case MessageType.TWO: {
                                    publisher1.publish(syncPublication1, message1, message2);
                                    break;
                                }
                                case MessageType.THREE: {
                                    publisher1.publish(syncPublication1, message3, message1, message2);
                                    break;
                                }
                            }
                        } catch (InterruptedException e) {
                            if (!AsyncABQ.this.shuttingDown) {
                                switch (messageType) {
                                    case MessageType.ONE: {
                                        PublicationError publicationError = new PublicationError()
                                                        .setMessage("Thread interrupted while processing message")
                                                        .setCause(e);

                                        if (event != null) {
                                            publicationError.setPublishedObject(message1);
                                        }

                                        errorHandler1.handlePublicationError(publicationError);
                                        break;
                                    }
                                    case MessageType.TWO: {
                                        PublicationError publicationError = new PublicationError()
                                                        .setMessage("Thread interrupted while processing message")
                                                        .setCause(e);

                                        if (event != null) {
                                            publicationError.setPublishedObject(message1, message2);
                                        }

                                        errorHandler1.handlePublicationError(publicationError);
                                        break;
                                    }
                                    case MessageType.THREE: {
                                        PublicationError publicationError = new PublicationError()
                                                        .setMessage("Thread interrupted while processing message")
                                                        .setCause(e);

                                        if (event != null) {
                                            publicationError.setPublishedObject(message1, message2, message3);
                                        }

                                        errorHandler1.handlePublicationError(publicationError);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            };

            Thread runner = threadFactory.newThread(runnable);
            this.threads.add(runner);
        }
    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1) throws Throwable {
        MessageHolder take = new MessageHolder();
        take.type = MessageType.ONE;
        take.subscriptions = subscriptions;
        take.message1 = message1;

        this.dispatchQueue.put(take);
    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1, final Object message2) throws Throwable {
        MessageHolder take = new MessageHolder();
        take.type = MessageType.TWO;
        take.subscriptions = subscriptions;
        take.message1 = message1;
        take.message2 = message2;

        this.dispatchQueue.put(take);
    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1, final Object message2, final Object message3) throws Throwable {
        MessageHolder take = new MessageHolder();
        take.type = MessageType.THREE;
        take.subscriptions = subscriptions;
        take.message1 = message1;
        take.message2 = message2;
        take.message3 = message3;

        this.dispatchQueue.put(take);
    }

    public
    void start() {
        if (shuttingDown) {
            throw new Error("Unable to restart the MessageBus");
        }

        for (Thread t : this.threads) {
            t.start();
        }
    }

    public
    void shutdown() {
        this.shuttingDown = true;

        for (Thread t : this.threads) {
            t.interrupt();
        }
    }

    public
    boolean hasPendingMessages() {
        return !this.dispatchQueue.isEmpty();
    }
}
