package dorkbox.util.messagebus;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import dorkbox.util.messagebus.common.DeadMessage;
import dorkbox.util.messagebus.common.LinkedTransferQueue;
import dorkbox.util.messagebus.common.NamedThreadFactory;
import dorkbox.util.messagebus.common.TransferQueue;
import dorkbox.util.messagebus.error.IPublicationErrorHandler;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.subscription.Subscription;

/**
 * The base class for all message bus implementations with support for asynchronous message dispatch
 *
 * @Author bennidi
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class MultiMBassador implements IMessageBus {

    /** The number of CPUs, for spin control */
    private static final int NCPUS = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin before blocking.
     *
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    private static final int maxSpins = NCPUS < 2 ? 0 : 32*16;

    // error handling is first-class functionality
    // this handler will receive all errors that occur during message dispatch or message handling
    private final Collection<IPublicationErrorHandler> errorHandlers = new ArrayDeque<IPublicationErrorHandler>();

    private final TransferQueue<Runnable> dispatchQueue = new LinkedTransferQueue<Runnable>();

    private final SubscriptionManager subscriptionManager;

    // all threads that are available for asynchronous message dispatching
    private final int numberOfThreads;
    private final Collection<Thread> threads;

    /**
     * if true, only exact matching will be performed on classes. Setting this to true
     * removes the ability to have subTypes and VarArg matching, and doing so doubles the speed of the
     * system. By default, this is FALSE, to support subTypes and VarArg matching.
     */
    private final boolean forceExactMatches = false;


    /**
     * By default, will permit subTypes and VarArg matching, and will use all CPUs available for dispatching async messages
     */
    public MultiMBassador() {
        this(Runtime.getRuntime().availableProcessors());
//        this(4);
    }

    /**
     * @param numberOfThreads how many threads to have for dispatching async messages
     */
    public MultiMBassador(int numberOfThreads) {
        this(false, numberOfThreads);
    }

    /**
     * @param forceExactMatches if true, only exact matching will be performed on classes. Setting this to true
     *          removes the ability to have subTypes and VarArg matching, and doing so doubles the speed of the
     *          system. By default, this is FALSE, to support subTypes and VarArg matching.
     *
     * @param numberOfThreads how many threads to have for dispatching async messages
     */
    public MultiMBassador(boolean forceExactMatches, int numberOfThreads) {
        if (numberOfThreads < 1) {
            numberOfThreads = 1; // at LEAST 1 thread
        }
        this.numberOfThreads = numberOfThreads;

        this.subscriptionManager = new SubscriptionManager(numberOfThreads);
        this.threads = new ArrayDeque<Thread>(numberOfThreads);

        NamedThreadFactory dispatchThreadFactory = new NamedThreadFactory("MessageBus");
        for (int i = 0; i < numberOfThreads; i++) {
            // each thread will run forever and process incoming message publication requests
            Runnable runnable = new Runnable() {
                @SuppressWarnings("null")
                @Override
                public void run() {
                    TransferQueue<Runnable> IN_QUEUE = MultiMBassador.this.dispatchQueue;
                    Runnable event = null;
                    int spins;

                    while (true) {
                        try {
                            spins = maxSpins;
                            while ((event = IN_QUEUE.poll()) == null) {
                                if (spins > 100) {
                                    --spins;
                                } else if (spins > 0) {
                                    --spins;
                                    LockSupport.parkNanos(1L);
                                } else {
                                    event = IN_QUEUE.take();
                                    break;
                                }
                            }

                            event.run();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            };

            Thread runner = dispatchThreadFactory.newThread(runnable);
            this.threads.add(runner);
            runner.start();
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
    public void subscribe(final Object listener) {
        MultiMBassador.this.subscriptionManager.subscribe(listener);
    }

    @Override
    public void unsubscribe(final Object listener) {
        MultiMBassador.this.subscriptionManager.unsubscribe(listener);
    }

    @Override
    public boolean hasPendingMessages() {
        return this.dispatchQueue.getWaitingConsumerCount() != this.numberOfThreads;
    }

    @Override
    public void shutdown() {
        for (Thread t : this.threads) {
            t.interrupt();
        }
        this.subscriptionManager.shutdown();
    }

    @Override
    public void publish(Object message) {
        SubscriptionManager manager = this.subscriptionManager;

        Class<?> messageClass = message.getClass();
        Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass);
        boolean subsPublished = false;

        // Run subscriptions
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (Subscription sub : subscriptions) {
                // this catches all exception types
                subsPublished |= sub.publishToSubscription(this, message);
            }
        }

        if (!this.forceExactMatches) {
            Collection<Subscription> superSubscriptions = manager.getSuperSubscriptions(messageClass);
            // now get superClasses
            if (superSubscriptions != null && !superSubscriptions.isEmpty()) {
                for (Subscription sub : superSubscriptions) {
                    // this catches all exception types
                    subsPublished |= sub.publishToSubscription(this, message);
                }
            }


            // publish to var arg, only if not already an array
            if (!messageClass.isArray()) {
                Object[] asArray = null;

                Collection<Subscription> varargSubscriptions = manager.getVarArgSubscriptions(messageClass);
                if (varargSubscriptions != null && !varargSubscriptions.isEmpty()) {
                    asArray = (Object[]) Array.newInstance(messageClass, 1);
                    asArray[0] = message;

                    for (Subscription sub : varargSubscriptions) {
                        // this catches all exception types
                        subsPublished |= sub.publishToSubscription(this, asArray);
                    }
                }

                Collection<Subscription> varargSuperSubscriptions = manager.getVarArgSuperSubscriptions(messageClass);
                // now get array based superClasses (but only if those ALSO accept vararg)
                if (varargSuperSubscriptions != null && !varargSuperSubscriptions.isEmpty()) {
                    if (asArray == null) {
                        asArray = (Object[]) Array.newInstance(messageClass, 1);
                        asArray[0] = message;
                    }

                    for (Subscription sub : varargSuperSubscriptions) {
                        // this catches all exception types
                        subsPublished |= sub.publishToSubscription(this, asArray);
                    }
                }
            }
        }

        if (!subsPublished) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            Collection<Subscription> deadSubscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
            if (deadSubscriptions != null && !deadSubscriptions.isEmpty())  {
                DeadMessage deadMessage = new DeadMessage(message);
                for (Subscription sub : deadSubscriptions) {
                    // this catches all exception types
                    sub.publishToSubscription(this, deadMessage);
                }
            }
        }
    }

    @Override
    public void publish(Object message1, Object message2) {
        SubscriptionManager manager = this.subscriptionManager;

        Class<?> messageClass1 = message1.getClass();
        Class<?> messageClass2 = message2.getClass();

        Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass1, messageClass2);
        boolean subsPublished = false;


        // Run subscriptions
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (Subscription sub : subscriptions) {
                // this catches all exception types
                subsPublished |= sub.publishToSubscription(this, message1, message2);
            }
        }

        if (!this.forceExactMatches) {
            Collection<Subscription> superSubscriptions = manager.getSuperSubscriptions(messageClass1, messageClass2);
            // now get superClasses
            if (superSubscriptions != null && !superSubscriptions.isEmpty()) {
                for (Subscription sub : superSubscriptions) {
                    // this catches all exception types
                    subsPublished |= sub.publishToSubscription(this, message1, message2);
                }
            }

            // publish to var arg, only if not already an array
            if (messageClass1 == messageClass2 && !messageClass1.isArray()) {
                Object[] asArray = null;

                Collection<Subscription> varargSubscriptions = manager.getVarArgSubscriptions(messageClass1);
                if (varargSubscriptions != null && !varargSubscriptions.isEmpty()) {
                    asArray = (Object[]) Array.newInstance(messageClass1, 2);
                    asArray[0] = message1;
                    asArray[1] = message2;

                    for (Subscription sub : varargSubscriptions) {
                        // this catches all exception types
                        subsPublished |= sub.publishToSubscription(this, asArray);
                    }
                }

                Collection<Subscription> varargSuperSubscriptions = manager.getVarArgSuperSubscriptions(messageClass1);
                // now get array based superClasses (but only if those ALSO accept vararg)
                if (varargSuperSubscriptions != null && !varargSuperSubscriptions.isEmpty()) {
                    if (asArray == null) {
                        asArray = (Object[]) Array.newInstance(messageClass1, 2);
                        asArray[0] = message1;
                        asArray[1] = message2;
                    }

                    for (Subscription sub : varargSuperSubscriptions) {
                        // this catches all exception types
                        subsPublished |= sub.publishToSubscription(this, asArray);
                    }
                }
            }
        }


        if (!subsPublished) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            Collection<Subscription> deadSubscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
            if (deadSubscriptions != null && !deadSubscriptions.isEmpty())  {
                DeadMessage deadMessage = new DeadMessage(message1, message2);
                for (Subscription sub : deadSubscriptions) {
                    // this catches all exception types
                    sub.publishToSubscription(this, deadMessage);
                }
            }
        }
    }

    @Override
    public void publish(Object message1, Object message2, Object message3) {
        SubscriptionManager manager = this.subscriptionManager;

        Class<?> messageClass1 = message1.getClass();
        Class<?> messageClass2 = message2.getClass();
        Class<?> messageClass3 = message3.getClass();

        Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass1, messageClass2, messageClass3);
        boolean subsPublished = false;

        // Run subscriptions
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (Subscription sub : subscriptions) {
                // this catches all exception types
                subsPublished |= sub.publishToSubscription(this, message1, message2, message3);
            }
        }


        if (!this.forceExactMatches) {
            Collection<Subscription> superSubscriptions = manager.getSuperSubscriptions(messageClass1, messageClass2, messageClass3);
            // now get superClasses
            if (superSubscriptions != null && !superSubscriptions.isEmpty()) {
                for (Subscription sub : superSubscriptions) {
                    // this catches all exception types
                    sub.publishToSubscription(this, message1, message2, message3);
                }
            }

            // publish to var arg, only if not already an array
            if (messageClass1 == messageClass2 && messageClass1 == messageClass3 && !messageClass1.isArray()) {
                Object[] asArray = null;
                Collection<Subscription> varargSubscriptions = manager.getVarArgSubscriptions(messageClass1);
                if (varargSubscriptions != null && !varargSubscriptions.isEmpty()) {
                    asArray = (Object[]) Array.newInstance(messageClass1, 3);
                    asArray[0] = message1;
                    asArray[1] = message2;
                    asArray[2] = message3;

                    for (Subscription sub : varargSubscriptions) {
                        // this catches all exception types
                        subsPublished |= sub.publishToSubscription(this, asArray);
                    }
                }

                Collection<Subscription> varargSuperSubscriptions = manager.getVarArgSuperSubscriptions(messageClass1);
                // now get array based superClasses (but only if those ALSO accept vararg)
                if (varargSuperSubscriptions != null && !varargSuperSubscriptions.isEmpty()) {
                    if (asArray == null) {
                        asArray = (Object[]) Array.newInstance(messageClass1, 3);
                        asArray[0] = message1;
                        asArray[1] = message2;
                        asArray[2] = message3;
                    }

                    for (Subscription sub : varargSuperSubscriptions) {
                        // this catches all exception types
                        subsPublished |= sub.publishToSubscription(this, asArray);
                    }
                }
            }
        }


        if (!subsPublished) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            Collection<Subscription> deadSubscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
            if (deadSubscriptions != null && !deadSubscriptions.isEmpty())  {
                DeadMessage deadMessage = new DeadMessage(message1, message2, message3);
                for (Subscription sub : deadSubscriptions) {
                    // this catches all exception types
                    sub.publishToSubscription(this, deadMessage);
                }
            }
        }
    }

    @Override
    public void publishAsync(final Object message) {
        if (message != null) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    MultiMBassador.this.publish(message);
                }
            };

            try {
                this.dispatchQueue.transfer(runnable);
            } catch (InterruptedException e) {
                handlePublicationError(new PublicationError()
                    .setMessage("Error while adding an asynchronous message")
                    .setCause(e)
                    .setPublishedObject(message));
            }
        }
    }

    @Override
    public void publishAsync(final Object message1, final Object message2) {
        if (message1 != null && message2 != null) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    MultiMBassador.this.publish(message1, message2);
                }
            };

            try {
                this.dispatchQueue.transfer(runnable);
            } catch (InterruptedException e) {
                handlePublicationError(new PublicationError()
                    .setMessage("Error while adding an asynchronous message")
                    .setCause(e)
                    .setPublishedObject(message1, message2));
            }
        }
    }

    @Override
    public void publishAsync(final Object message1, final Object message2, final Object message3) {
        if (message1 != null || message2 != null | message3 != null) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    MultiMBassador.this.publish(message1, message2, message3);
                }
            };


            try {
                this.dispatchQueue.transfer(runnable);
            } catch (InterruptedException e) {
                handlePublicationError(new PublicationError()
                    .setMessage("Error while adding an asynchronous message")
                    .setCause(e)
                    .setPublishedObject(message1, message2, message3));
            }
        }
    }

    @Override
    public void publishAsync(long timeout, TimeUnit unit, final Object message) {
        if (message != null) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    MultiMBassador.this.publish(message);
                }
            };

            try {
                this.dispatchQueue.tryTransfer(runnable, timeout, unit);
            } catch (InterruptedException e) {
                handlePublicationError(new PublicationError()
                    .setMessage("Error while adding an asynchronous message")
                    .setCause(e)
                    .setPublishedObject(message));
            }
        }
    }
    @Override
    public void publishAsync(long timeout, TimeUnit unit, final Object message1, final Object message2) {
        if (message1 != null && message2 != null) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    MultiMBassador.this.publish(message1, message2);
                }
            };

            try {
                this.dispatchQueue.tryTransfer(runnable, timeout, unit);
            } catch (InterruptedException e) {
                handlePublicationError(new PublicationError()
                    .setMessage("Error while adding an asynchronous message")
                    .setCause(e)
                    .setPublishedObject(message1, message2));
            }
        }
    }


    @Override
    public void publishAsync(long timeout, TimeUnit unit, final Object message1, final Object message2, final Object message3) {
        if (message1 != null && message2 != null && message3 != null) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    MultiMBassador.this.publish(message1, message2, message3);
                }
            };

            try {
                this.dispatchQueue.tryTransfer(runnable, timeout, unit);
            } catch (InterruptedException e) {
                handlePublicationError(new PublicationError()
                    .setMessage("Error while adding an asynchronous message")
                    .setCause(e)
                    .setPublishedObject(message1, message2, message3));
            }
        }
    }
}
