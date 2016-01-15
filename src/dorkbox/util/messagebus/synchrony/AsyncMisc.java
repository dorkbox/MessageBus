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
class AsyncMisc implements Synchrony {
    /**
     * Notifies the consumers during shutdown, that it's on purpose.
     */
    private volatile boolean shuttingDown = false;


    //    private final LinkedBlockingQueue<Object> dispatchQueue;
//    private final ArrayBlockingQueue<Object> dispatchQueue;
    private final LinkedTransferQueue<Object> dispatchQueue;
    private final Collection<Thread> threads;


    public
    AsyncMisc(final int numberOfThreads,
              final ErrorHandlingSupport errorHandler,
              final Publisher publisher,
              final Synchrony syncPublication) {

//        this.dispatchQueue = new LinkedBlockingQueue<Object>(1024);
//        this.dispatchQueue = new ArrayBlockingQueue<Object>(1024);
        this.dispatchQueue = new LinkedTransferQueue<Object>();

        this.threads = new ArrayDeque<Thread>(numberOfThreads);
        final NamedThreadFactory threadFactory = new NamedThreadFactory("MessageBus");
        for (int i = 0; i < numberOfThreads; i++) {

            // each thread will run forever and process incoming message publication requests
            Runnable runnable = new Runnable() {
                @Override
                public
                void run() {
//                    LinkedBlockingQueue<?> IN_QUEUE = MessageBus.this.dispatchQueue;
//                    ArrayBlockingQueue<?> IN_QUEUE = MessageBus.this.dispatchQueue;
                    LinkedTransferQueue<?> IN_QUEUE = AsyncMisc.this.dispatchQueue;

//                    MultiNode node = new MultiNode();
                    while (!AsyncMisc.this.shuttingDown) {
                        try {
                            //noinspection InfiniteLoopStatement
                            while (true) {
//                                IN_QUEUE.take(node);
                                final Object take = IN_QUEUE.take();
//                                Integer type = (Integer) MultiNode.lpMessageType(node);
//                                switch (type) {
//                                    case 1: {
//                                publish(take);
//                                        break;
//                                    }
//                                    case 2: {
//                                        publish(MultiNode.lpItem1(node), MultiNode.lpItem2(node));
//                                        break;
//                                    }
//                                    case 3: {
//                                        publish(MultiNode.lpItem1(node), MultiNode.lpItem2(node), MultiNode.lpItem3(node));
//                                        break;
//                                    }
//                                    default: {
//                                        publish(MultiNode.lpItem1(node));
//                                    }
//                                }
                            }
                        } catch (InterruptedException e) {
                            if (!AsyncMisc.this.shuttingDown) {
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

//        try {
//            this.dispatchQueue.transfer(message);
////            this.dispatchQueue.put(message);
//        } catch (Exception e) {
//            errorHandler.handlePublicationError(new PublicationError().setMessage(
//                            "Error while adding an asynchronous message").setCause(e).setPublishedObject(message));
//        }

        Subscription sub;
        for (int i = 0; i < subscriptions.length; i++) {
            sub = subscriptions[i];
            sub.publish(message1);
        }
    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1, final Object message2) throws Throwable  {

    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1, final Object message2, final Object message3) throws Throwable  {

    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object[] messages) throws Throwable  {

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
