package dorkbox.util.messagebus;

import dorkbox.util.messagebus.common.simpleq.MessageType;
import dorkbox.util.messagebus.common.simpleq.MpmcMultiTransferArrayQueue;
import dorkbox.util.messagebus.common.simpleq.MultiNode;
import dorkbox.util.messagebus.common.thread.NamedThreadFactory;
import dorkbox.util.messagebus.error.DefaultErrorHandler;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.publication.PublisherAll;
import dorkbox.util.messagebus.publication.PublisherExact;
import dorkbox.util.messagebus.publication.PublisherExactWithSuperTypes;
import dorkbox.util.messagebus.subscription.Publisher;
import dorkbox.util.messagebus.subscription.SubscriptionManager;
import org.jctools.util.Pow2;

import java.util.ArrayDeque;
import java.util.Collection;

/**
 * The base class for all message bus implementations with support for asynchronous message dispatch
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class MessageBus implements IMessageBus {
    private final ErrorHandlingSupport errorHandler;
    private final MpmcMultiTransferArrayQueue dispatchQueue;
    private final SubscriptionManager subscriptionManager;
    private final Collection<Thread> threads;
    private final Publisher subscriptionPublisher;

    /**
     * Notifies the consumers during shutdown, that it's on purpose.
     */
    private volatile boolean shuttingDown;

    /**
     * By default, will permit subTypes and VarArg matching, and will use half of CPUs available for dispatching async messages
     */
    public MessageBus() {
        this(Runtime.getRuntime().availableProcessors() / 2);
    }

    /**
     * @param numberOfThreads how many threads to have for dispatching async messages
     */
    public MessageBus(int numberOfThreads) {
        this(PublishMode.ExactWithSuperTypes, numberOfThreads);
    }

    /**
     * @param publishMode     Specifies which publishMode to operate the publication of messages.
     * @param numberOfThreads how many threads to have for dispatching async messages
     */
    public MessageBus(final PublishMode publishMode, int numberOfThreads) {
        numberOfThreads = Pow2.roundToPowerOfTwo(getMinNumberOfThreads(numberOfThreads));

        this.errorHandler = new DefaultErrorHandler();
        this.dispatchQueue = new MpmcMultiTransferArrayQueue(numberOfThreads);
        this.subscriptionManager = new SubscriptionManager(numberOfThreads, errorHandler, true);

        switch (publishMode) {
            case Exact:
                subscriptionPublisher = new PublisherExact(errorHandler);
                break;
            case ExactWithSuperTypes:
                subscriptionPublisher = new PublisherExactWithSuperTypes(errorHandler);
                break;
            case ExactWithSuperTypesAndVarArgs:
            default:
                subscriptionPublisher = new PublisherAll(errorHandler);
        }

        this.threads = new ArrayDeque<Thread>(numberOfThreads);

        NamedThreadFactory dispatchThreadFactory = new NamedThreadFactory("MessageBus");
        for (int i = 0; i < numberOfThreads; i++) {
            // each thread will run forever and process incoming message publication requests
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    MpmcMultiTransferArrayQueue IN_QUEUE = MessageBus.this.dispatchQueue;

                    MultiNode node = new MultiNode();
                    while (!MessageBus.this.shuttingDown) {
                        try {
                            //noinspection InfiniteLoopStatement
                            while (true) {
                                IN_QUEUE.take(node);
                                switch (node.messageType) {
                                    case 1: {
                                        publish(node.item1);
                                        break;
                                    }
                                    case 2: {
                                        publish(node.item1, node.item2);
                                        break;
                                    }
                                    case 3: {
                                        publish(node.item1, node.item2, node.item3);
                                        break;
                                    }
                                    default: {
                                        publish(node.item1);
                                    }
                                }
                            }
                        } catch (InterruptedException e) {
                            if (!MessageBus.this.shuttingDown) {
                                switch (node.messageType) {
                                    case 1: {
                                        errorHandler.handlePublicationError(new PublicationError().setMessage(
                                                        "Thread interrupted while processing message").setCause(e).setPublishedObject(
                                                        node.item1));
                                        break;
                                    }
                                    case 2: {
                                        errorHandler.handlePublicationError(new PublicationError().setMessage(
                                                        "Thread interrupted while processing message").setCause(e).setPublishedObject(
                                                        node.item1, node.item2));
                                        break;
                                    }
                                    case 3: {
                                        errorHandler.handlePublicationError(new PublicationError().setMessage(
                                                        "Thread interrupted while processing message").setCause(e).setPublishedObject(
                                                        node.item1, node.item2, node.item3));
                                        break;
                                    }
                                    default: {
                                        errorHandler.handlePublicationError(new PublicationError().setMessage(
                                                        "Thread interrupted while processing message").setCause(e).setPublishedObject(
                                                        node.item1));
                                    }
                                }
                            }
                        }
                    }
                }
            };

            Thread runner = dispatchThreadFactory.newThread(runnable);
            this.threads.add(runner);
        }
    }

    /**
     * Always return at least 2 threads
     */
    private static int getMinNumberOfThreads(final int numberOfThreads) {
        if (numberOfThreads < 2) {
            return 2;
        }
        return numberOfThreads;
    }

    @Override
    public void subscribe(final Object listener) {
        MessageBus.this.subscriptionManager.subscribe(listener);
        MessageBus.this.subscriptionManager.subscribe(listener);
    }

    @Override
    public void unsubscribe(final Object listener) {
        MessageBus.this.subscriptionManager.unsubscribe(listener);
    }

    @Override
    public void publish(final Object message) {
        subscriptionPublisher.publish(subscriptionManager, message);
    }

    @Override
    public void publish(final Object message1, final Object message2) {
        subscriptionPublisher.publish(subscriptionManager, message1, message2);
    }

    @Override
    public void publish(final Object message1, final Object message2, final Object message3) {
        subscriptionPublisher.publish(subscriptionManager, message1, message2, message3);
    }

    @Override
    public void publish(final Object[] messages) {
        subscriptionPublisher.publish(subscriptionManager, messages);
    }

    @Override
    public void publishAsync(final Object message) {
        if (message != null) {
            try {
                this.dispatchQueue.transfer(message, MessageType.ONE);
            } catch (Exception e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error while adding an asynchronous message")
                                                                          .setCause(e).setPublishedObject(message));
            }
        }
        else {
            throw new NullPointerException("Message cannot be null.");
        }
    }

    @Override
    public void publishAsync(final Object message1, final Object message2) {
        if (message1 != null && message2 != null) {
            try {
                this.dispatchQueue.transfer(message1, message2);
            } catch (Exception e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error while adding an asynchronous message")
                                                                          .setCause(e).setPublishedObject(message1, message2));
            }
        }
        else {
            throw new NullPointerException("Messages cannot be null.");
        }
    }

    @Override
    public void publishAsync(final Object message1, final Object message2, final Object message3) {
        if (message1 != null || message2 != null | message3 != null) {
            try {
                this.dispatchQueue.transfer(message1, message2, message3);
            } catch (Exception e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error while adding an asynchronous message")
                                                                          .setCause(e).setPublishedObject(message1, message2, message3));
            }
        }
        else {
            throw new NullPointerException("Messages cannot be null.");
        }
    }

    @Override
    public void publishAsync(final Object[] messages) {
        if (messages != null) {
            try {
                this.dispatchQueue.transfer(messages, MessageType.ARRAY);
            } catch (Exception e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error while adding an asynchronous message")
                                                                          .setCause(e).setPublishedObject(messages));
            }
        }
        else {
            throw new NullPointerException("Message cannot be null.");
        }
    }

    @Override
    public final boolean hasPendingMessages() {
        return this.dispatchQueue.hasPendingMessages();
    }

    @Override
    public final ErrorHandlingSupport getErrorHandler() {
        return errorHandler;
    }

    @Override
    public void start() {
        for (Thread t : this.threads) {
            t.start();
        }

        errorHandler.start();
    }

    @Override
    public void shutdown() {
        this.shuttingDown = true;
        for (Thread t : this.threads) {
            t.interrupt();
        }
        this.subscriptionManager.shutdown();
    }
}
