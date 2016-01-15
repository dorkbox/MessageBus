package dorkbox.util.messagebus.synchrony;

import dorkbox.util.messagebus.common.thread.NamedThreadFactory;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.publication.Publisher;
import dorkbox.util.messagebus.subscription.Subscription;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.concurrent.LinkedTransferQueue;

/**
 *
 */
public
class AsyncLTQ implements Synchrony {
    private final ErrorHandlingSupport errorHandler;
    private final LinkedTransferQueue<Object> dispatchQueue;
    private final Collection<Thread> threads;
    /**
     * Notifies the consumers during shutdown, that it's on purpose.
     */
    private volatile boolean shuttingDown = false;


    public
    AsyncLTQ(final int numberOfThreads,
             final ErrorHandlingSupport errorHandler,
             final Publisher publisher,
             final Synchrony syncPublication) {

        this.errorHandler = errorHandler;
        this.dispatchQueue = new LinkedTransferQueue<Object>();

        this.threads = new ArrayDeque<Thread>(numberOfThreads);
        final NamedThreadFactory threadFactory = new NamedThreadFactory("MessageBus");
        for (int i = 0; i < numberOfThreads; i++) {

            // each thread will run forever and process incoming message publication requests
            Runnable runnable = new Runnable() {
                @Override
                public
                void run() {
                    LinkedTransferQueue<?> IN_QUEUE = AsyncLTQ.this.dispatchQueue;
                    final Publisher publisher1 = publisher;
                    final Synchrony syncPublication1 = syncPublication;

                    while (!AsyncLTQ.this.shuttingDown) {
                        try {
                            //noinspection InfiniteLoopStatement
                            while (true) {
                                final Object take = IN_QUEUE.take();
                                publisher1.publish(syncPublication1, take);
                            }
                        } catch (InterruptedException e) {
                            if (!AsyncLTQ.this.shuttingDown) {
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
                            }
                        }
                    }
                }
            };

            Thread runner = threadFactory.newThread(runnable);
            this.threads.add(runner);
        }
    }

    public
    void publish(final Subscription[] subscriptions, final Object message1) throws Throwable {
        this.dispatchQueue.transfer(message1);
    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1, final Object message2) throws Throwable {

    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1, final Object message2, final Object message3) throws Throwable {

    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object[] messages) throws Throwable {

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
