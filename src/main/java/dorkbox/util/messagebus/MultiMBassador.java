package dorkbox.util.messagebus;

import dorkbox.util.messagebus.common.NamedThreadFactory;
import dorkbox.util.messagebus.common.simpleq.MessageType;
import dorkbox.util.messagebus.common.simpleq.MpmcMultiTransferArrayQueue;
import dorkbox.util.messagebus.common.simpleq.MultiNode;
import dorkbox.util.messagebus.error.IPublicationErrorHandler;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.subscription.Matcher;
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
public class MultiMBassador implements IMessageBus {
    public static final String ERROR_HANDLER_MSG = "INFO: No error handler has been configured to handle exceptions during publication.\n" +
                                                   "Publication error handlers can be added by bus.addErrorHandler()\n" +
                                                   "Falling back to console logger.";

    // this handler will receive all errors that occur during message dispatch or message handling
    private final Collection<IPublicationErrorHandler> errorHandlers = new ArrayDeque<IPublicationErrorHandler>();

    private final MpmcMultiTransferArrayQueue dispatchQueue;

    private final SubscriptionManager subscriptionManager;

    private final Collection<Thread> threads;

    private final Matcher subscriptionMatcher;

    /**
     * Notifies the consumers during shutdown, that it's on purpose.
     */
    private volatile boolean shuttingDown;

    /**
     * By default, will permit subTypes and VarArg matching, and will use half of CPUs available for dispatching async messages
     */
    public MultiMBassador() {
        this(Runtime.getRuntime().availableProcessors() / 2);
    }

    /**
     * @param numberOfThreads how many threads to have for dispatching async messages
     */
    public MultiMBassador(int numberOfThreads) {
        this(Mode.ExactWithSuperTypes, numberOfThreads);
    }

    /**
     * @param mode Specifies which mode to operate the publication of messages.
     * @param numberOfThreads   how many threads to have for dispatching async messages
     */
    public MultiMBassador(Mode mode, int numberOfThreads) {
        if (numberOfThreads < 2) {
            numberOfThreads = 2; // at LEAST 2 threads
        }
        numberOfThreads = Pow2.roundToPowerOfTwo(numberOfThreads);
        this.dispatchQueue = new MpmcMultiTransferArrayQueue(numberOfThreads);
        this.subscriptionManager = new SubscriptionManager(numberOfThreads);

        switch (mode) {
            case Exact:
                subscriptionMatcher = new Matcher() {
                    @Override
                    public void publish(final Object message1) throws Throwable {
                        subscriptionManager.publishExact(message1);
                    }

                    @Override
                    public void publish(final Object message1, final Object message2) throws Throwable {
                        subscriptionManager.publishExact(message1, message2);
                    }

                    @Override
                    public void publish(final Object message1, final Object message2, final Object message3) throws Throwable {
                        subscriptionManager.publishExact(message1, message2, message3);
                    }

                    @Override
                    public void publish(final Object[] messages) throws Throwable {
                        subscriptionManager.publishExact(messages);
                    }

                };
                break;
            case ExactWithSuperTypes:
                subscriptionMatcher = new Matcher() {
                    @Override
                    public void publish(final Object message1) throws Throwable {
                        subscriptionManager.publishExactAndSuper(message1);
                    }

                    @Override
                    public void publish(final Object message1, final Object message2) throws Throwable {
                        subscriptionManager.publishExactAndSuper(message1, message2);
                    }

                    @Override
                    public void publish(final Object message1, final Object message2, final Object message3) throws Throwable {
                        subscriptionManager.publishExactAndSuper(message1, message2, message3);
                    }

                    @Override
                    public void publish(final Object[] messages) throws Throwable {
                        subscriptionManager.publishExactAndSuper(messages);
                    }
                };
                break;
            case ExactWithSuperTypesAndVarArgs:
            default:
                subscriptionMatcher = new Matcher() {
                    @Override
                    public void publish(final Object message1) throws Throwable {
                        subscriptionManager.publishAll(message1);
                    }

                    @Override
                    public void publish(final Object message1, final Object message2) throws Throwable {
                        // we don't support var-args for multiple messages (var-args can only be a single type)
                        subscriptionManager.publishExactAndSuper(message1, message2);
                    }

                    @Override
                    public void publish(final Object message1, final Object message2, final Object message3) throws Throwable {
                        // we don't support var-args for multiple messages (var-args can only be a single type)
                        subscriptionManager.publishExactAndSuper(message1, message2, message3);
                    }

                    @Override
                    public void publish(final Object[] messages) throws Throwable {
                        // we don't support var-args for multiple messages (var-args can only be a single type)
                        subscriptionManager.publishExactAndSuper(messages);
                    }
                };
        }

        this.threads = new ArrayDeque<Thread>(numberOfThreads);

        NamedThreadFactory dispatchThreadFactory = new NamedThreadFactory("MessageBus");
        for (int i = 0; i < numberOfThreads; i++) {
            // each thread will run forever and process incoming message publication requests
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    MpmcMultiTransferArrayQueue IN_QUEUE = MultiMBassador.this.dispatchQueue;

                    MultiNode node = new MultiNode();
                    while (!MultiMBassador.this.shuttingDown) {
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
                            if (!MultiMBassador.this.shuttingDown) {
                                switch (node.messageType) {
                                    case 1: {
                                        handlePublicationError(
                                                        new PublicationError().setMessage("Thread interrupted while processing message")
                                                                        .setCause(e).setPublishedObject(node.item1));
                                        break;
                                    }
                                    case 2: {
                                        handlePublicationError(
                                                        new PublicationError().setMessage("Thread interrupted while processing message")
                                                                        .setCause(e).setPublishedObject(node.item1, node.item2));
                                        break;
                                    }
                                    default: {
                                        handlePublicationError(
                                                        new PublicationError().setMessage("Thread interrupted while processing message")
                                                                        .setCause(e)
                                                                        .setPublishedObject(node.item1, node.item2, node.item3));
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

    @Override
    public final void addErrorHandler(IPublicationErrorHandler handler) {
        synchronized (this.errorHandlers) {
            this.errorHandlers.add(handler);
        }
    }

    @Override
    public final void handlePublicationError(PublicationError error) {
        synchronized (this.errorHandlers) {
            for (IPublicationErrorHandler errorHandler : this.errorHandlers) {
                errorHandler.handleError(error);
            }
        }
    }

    @Override
    public void start() {
        for (Thread t : this.threads) {
            t.start();
        }
        synchronized (this.errorHandlers) {
            if (this.errorHandlers.isEmpty()) {
                this.errorHandlers.add(new IPublicationErrorHandler.ConsoleLogger());
                System.out.println(ERROR_HANDLER_MSG);
            }
        }
    }

    @Override
    public void shutdown() {
        this.shuttingDown = true;
        for (Thread t : this.threads) {
            t.interrupt();
        }
        this.subscriptionManager.shutdown();
    }

    @Override
    public void subscribe(final Object listener) {
        MultiMBassador.this.subscriptionManager.subscribe(listener);
    }

    @Override
    public void unsubscribe(final Object listener) {
        MultiMBassador.this.subscriptionManager.unsubscribe(listener);
    }

    @Override
    public final boolean hasPendingMessages() {
        return this.dispatchQueue.hasPendingMessages();
    }

    @Override
    public void publish(final Object message) {
        try {
            subscriptionMatcher.publish(message);
        } catch (Throwable e) {
            handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(e)
                                                   .setPublishedObject(message));
        }
    }

    @Override
    public void publish(final Object message1, final Object message2) {
        try {
            subscriptionMatcher.publish(message1, message2);
        } catch (Throwable e) {
            handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(e)
                                                   .setPublishedObject(message1, message2));
        }
    }

    @Override
    public void publish(final Object message1, final Object message2, final Object message3) {
        try {
            subscriptionMatcher.publish(message1, message2, message3);
        } catch (Throwable e) {
            handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(e)
                                                   .setPublishedObject(message1, message2, message3));
        }
    }

    @Override
    public void publish(final Object[] messages) {
        try {
            subscriptionMatcher.publish(messages);
        } catch (Throwable e) {
            handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(e)
                                                   .setPublishedObject(messages));
        }
    }

    @Override
    public void publishAsync(final Object message) {
        if (message != null) {
            try {
                this.dispatchQueue.transfer(message, MessageType.ONE);
            } catch (Exception e) {
                handlePublicationError(new PublicationError().setMessage("Error while adding an asynchronous message").setCause(e)
                                                       .setPublishedObject(message));
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
                handlePublicationError(new PublicationError().setMessage("Error while adding an asynchronous message").setCause(e)
                                                       .setPublishedObject(message1, message2));
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
                handlePublicationError(new PublicationError().setMessage("Error while adding an asynchronous message").setCause(e)
                                                       .setPublishedObject(message1, message2, message3));
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
                handlePublicationError(new PublicationError().setMessage("Error while adding an asynchronous message").setCause(e)
                                                       .setPublishedObject(messages));
            }
        }
        else {
            throw new NullPointerException("Message cannot be null.");
        }
    }

}
