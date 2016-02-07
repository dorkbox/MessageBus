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
package dorkbox.messagebus.synchrony;

import dorkbox.messagebus.common.NamedThreadFactory;
import dorkbox.messagebus.error.ErrorHandler;
import dorkbox.messagebus.error.PublicationError;
import dorkbox.messagebus.subscription.Subscription;
import dorkbox.messagebus.synchrony.disruptor.MessageType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.LockSupport;

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
    private final Collection<Boolean> shutdown;

    /**
     * Notifies the consumers during shutdown that it's on purpose.
     */
    private volatile boolean shuttingDown = false;


    public
    AsyncABQ(final int numberOfThreads, final ErrorHandler errorHandler, final Synchrony syncPublication) {

        this.dispatchQueue = new ArrayBlockingQueue<MessageHolder>(1024);

        // each thread will run forever and process incoming message publication requests
        Runnable runnable = new Runnable() {
            @SuppressWarnings({"ConstantConditions", "UnnecessaryLocalVariable"})
            @Override
            public
            void run() {
                final ArrayBlockingQueue<MessageHolder> IN_QUEUE = AsyncABQ.this.dispatchQueue;
                final Synchrony syncPublication1 = syncPublication;
                final ErrorHandler errorHandler1 = errorHandler;

                while (!AsyncABQ.this.shuttingDown) {
                    process(IN_QUEUE, syncPublication1, errorHandler1);
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
    void process(final ArrayBlockingQueue<MessageHolder> queue, final Synchrony sync, final ErrorHandler errorHandler) {
        MessageHolder event = null;
        int messageType = MessageType.ONE;
        Subscription[] subscriptions;
        Object message1 = null;
        Object message2 = null;
        Object message3 = null;

        try {
            event = queue.take();
            messageType = event.type;
            subscriptions = event.subscriptions;
            message1 = event.message1;
            message2 = event.message2;
            message3 = event.message3;

            switch (messageType) {
                case MessageType.ONE: {
                    sync.publish(subscriptions, message1);
                    return;
                }
                case MessageType.TWO: {
                    sync.publish(subscriptions, message1, message2);
                    return;
                }
                case MessageType.THREE: {
                    sync.publish(subscriptions, message1, message2, message3);
                    //noinspection UnnecessaryReturnStatement
                    return;
                }
            }
        } catch (Throwable e) {
            if (event != null && !this.shuttingDown) {
                switch (messageType) {
                    case MessageType.ONE: {
                        errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.")
                                                                                  .setCause(e)
                                                                                  .setPublishedObject(message1));
                        return;
                    }
                    case MessageType.TWO: {
                        errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.")
                                                                                   .setCause(e)
                                                                                   .setPublishedObject(message1, message2));
                        return;
                    }
                    case MessageType.THREE: {
                        errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.")
                                                                                  .setCause(e)
                                                                                  .setPublishedObject(message1, message2, message3));
                        //noinspection UnnecessaryReturnStatement
                        return;
                    }
                }
            }
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
