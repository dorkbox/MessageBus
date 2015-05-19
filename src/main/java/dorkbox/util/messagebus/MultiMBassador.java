package dorkbox.util.messagebus;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.Collection;

import org.jctools.util.Pow2;

import dorkbox.util.messagebus.common.DeadMessage;
import dorkbox.util.messagebus.common.ISetEntry;
import dorkbox.util.messagebus.common.NamedThreadFactory;
import dorkbox.util.messagebus.common.StrongConcurrentSet;
import dorkbox.util.messagebus.common.simpleq.MpmcMultiTransferArrayQueue;
import dorkbox.util.messagebus.common.simpleq.MultiNode;
import dorkbox.util.messagebus.common.thread.BooleanHolder;
import dorkbox.util.messagebus.common.thread.BooleanThreadHolder;
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

    public static final String ERROR_HANDLER_MSG =
                    "INFO: No error handler has been configured to handle exceptions during publication.\n" +
                    "Publication error handlers can be added by bus.addErrorHandler()\n" +
                    "Falling back to console logger.";

    // this handler will receive all errors that occur during message dispatch or message handling
    private final Collection<IPublicationErrorHandler> errorHandlers = new ArrayDeque<IPublicationErrorHandler>();

    private final MpmcMultiTransferArrayQueue dispatchQueue;

    private final SubscriptionManager subscriptionManager;

    private final Collection<Thread> threads;

    /**
     * if true, only exact matching will be performed on classes. Setting this to true
     * removes the ability to have subTypes and VarArg matching, and doing so doubles the speed of the
     * system. By default, this is FALSE, to support subTypes and VarArg matching.
     */
    private final boolean forceExactMatches;

    /**
     * Notifies the consumers during shutdown, that it's on purpose.
     */
    private volatile boolean shuttingDown;

    /**
     * By default, will permit subTypes and VarArg matching, and will use half of CPUs available for dispatching async messages
     */
    public MultiMBassador() {
        this(Runtime.getRuntime().availableProcessors()/2);
    }

    /**
     * @param numberOfThreads how many threads to have for dispatching async messages
     */
    public MultiMBassador(int numberOfThreads) {
        this(false, numberOfThreads);
    }

    /**
     * @param forceExactMatches if TRUE, only exact matching will be performed on classes. Setting this to true
     *          removes the ability to have subTypes and VarArg matching, and doing so doubles the speed of the
     *          system. By default, this is FALSE, to support subTypes and VarArg matching.
     *
     * @param numberOfThreads how many threads to have for dispatching async messages
     */
    public MultiMBassador(boolean forceExactMatches, int numberOfThreads) {
        if (numberOfThreads < 2) {
            numberOfThreads = 2; // at LEAST 2 threads
        }
        numberOfThreads = Pow2.roundToPowerOfTwo(numberOfThreads);
        this.forceExactMatches = forceExactMatches;

        this.dispatchQueue = new MpmcMultiTransferArrayQueue(numberOfThreads);

        this.subscriptionManager = new SubscriptionManager(numberOfThreads);
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
                            while (true) {
                                IN_QUEUE.take(node);
                                switch (node.messageType) {
                                    case 1: publish(node.item1); continue;
                                    case 2: publish(node.item1, node.item2); continue;
                                    case 3: publish(node.item1, node.item2, node.item3); continue;
                                }
                            }
                        } catch (InterruptedException e) {
                            if (!MultiMBassador.this.shuttingDown) {
                                switch (node.messageType) {
                                    case 1: {
                                        handlePublicationError(new PublicationError()
                                            .setMessage("Thread interupted while processing message")
                                            .setCause(e)
                                            .setPublishedObject(node.item1));
                                        continue;
                                    }
                                    case 2: {
                                        handlePublicationError(new PublicationError()
                                            .setMessage("Thread interupted while processing message")
                                            .setCause(e)
                                            .setPublishedObject(node.item1, node.item2));
                                        continue;
                                    }
                                    case 3: {
                                        handlePublicationError(new PublicationError()
                                            .setMessage("Thread interupted while processing message")
                                            .setCause(e)
                                            .setPublishedObject(node.item1, node.item2, node.item3));
                                        continue;
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

    private final BooleanThreadHolder booleanThreadLocal = new BooleanThreadHolder();

    @Override
    public void publish(final Object message) {
        SubscriptionManager manager = this.subscriptionManager;

        Class<?> messageClass = message.getClass();
        StrongConcurrentSet<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass);

        BooleanHolder subsPublished = this.booleanThreadLocal.get();
        subsPublished.bool = false;

        ISetEntry<Subscription> current;
        Subscription sub;

        // Run subscriptions
        if (subscriptions != null) {
            current = subscriptions.head;
            while (current != null) {
                sub = current.getValue();
                current = current.next();

                // this catches all exception types
                sub.publishToSubscription(this, subsPublished, message);
            }
        }

        if (!this.forceExactMatches) {
            StrongConcurrentSet<Subscription> superSubscriptions = manager.getSuperSubscriptions(messageClass);
            // now get superClasses
            if (superSubscriptions != null) {
                current = superSubscriptions.head;
                while (current != null) {
                    sub = current.getValue();
                    current = current.next();

                    // this catches all exception types
                    sub.publishToSubscription(this, subsPublished, message);
                }
            }


            // publish to var arg, only if not already an array
            if (manager.hasVarArgPossibility() && !manager.utils.isArray(messageClass)) {
                Object[] asArray = null;

                StrongConcurrentSet<Subscription> varargSubscriptions = manager.getVarArgSubscriptions(messageClass);
                if (varargSubscriptions != null && !varargSubscriptions.isEmpty()) {
                    asArray = (Object[]) Array.newInstance(messageClass, 1);
                    asArray[0] = message;

                    current = varargSubscriptions.head;
                    while (current != null) {
                        sub = current.getValue();
                        current = current.next();

                        // this catches all exception types
                        sub.publishToSubscription(this, subsPublished, asArray);
                    }
                }

                StrongConcurrentSet<Subscription> varargSuperSubscriptions = manager.getVarArgSuperSubscriptions(messageClass);
                // now get array based superClasses (but only if those ALSO accept vararg)
                if (varargSuperSubscriptions != null && !varargSuperSubscriptions.isEmpty()) {
                    if (asArray == null) {
                        asArray = (Object[]) Array.newInstance(messageClass, 1);
                        asArray[0] = message;
                    }

                    current = varargSuperSubscriptions.head;
                    while (current != null) {
                        sub = current.getValue();
                        current = current.next();

                        // this catches all exception types
                        sub.publishToSubscription(this, subsPublished, asArray);
                    }
                }
            }
        }

        if (!subsPublished.bool) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            StrongConcurrentSet<Subscription> deadSubscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
            if (deadSubscriptions != null && !deadSubscriptions.isEmpty())  {
                DeadMessage deadMessage = new DeadMessage(message);

                current = deadSubscriptions.head;
                while (current != null) {
                    sub = current.getValue();
                    current = current.next();

                    // this catches all exception types
                    sub.publishToSubscription(this, subsPublished, deadMessage);
                }
            }
        }
    }

    @Override
    public void publish(final Object message1, final Object message2) {
        SubscriptionManager manager = this.subscriptionManager;

        Class<?> messageClass1 = message1.getClass();
        Class<?> messageClass2 = message2.getClass();

        StrongConcurrentSet<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass1, messageClass2);
        BooleanHolder subsPublished = this.booleanThreadLocal.get();
        subsPublished.bool = false;

        ISetEntry<Subscription> current;
        Subscription sub;

        // Run subscriptions
        if (subscriptions != null) {
            current = subscriptions.head;
            while (current != null) {
                sub = current.getValue();
                current = current.next();

                // this catches all exception types
                sub.publishToSubscription(this, subsPublished, message1, message2);
            }
        }

        if (!this.forceExactMatches) {
            StrongConcurrentSet<Subscription> superSubscriptions = manager.getSuperSubscriptions(messageClass1, messageClass2);
            // now get superClasses
            if (superSubscriptions != null) {
                current = superSubscriptions.head;
                while (current != null) {
                    sub = current.getValue();
                    current = current.next();

                    // this catches all exception types
                    sub.publishToSubscription(this, subsPublished, message1, message2);
                }
            }

            // publish to var arg, only if not already an array
            if (manager.hasVarArgPossibility()) {
                if (messageClass1 == messageClass2) {
                    Object[] asArray = null;

                    StrongConcurrentSet<Subscription> varargSubscriptions = manager.getVarArgSubscriptions(messageClass1);
                    if (varargSubscriptions != null && !varargSubscriptions.isEmpty()) {
                        asArray = (Object[]) Array.newInstance(messageClass1, 2);
                        asArray[0] = message1;
                        asArray[1] = message2;

                        current = varargSubscriptions.head;
                        while (current != null) {
                            sub = current.getValue();
                            current = current.next();

                            // this catches all exception types
                            sub.publishToSubscription(this, subsPublished, asArray);
                        }
                    }

                    StrongConcurrentSet<Subscription> varargSuperSubscriptions = manager.getVarArgSuperSubscriptions(messageClass1);
                    // now get array based superClasses (but only if those ALSO accept vararg)
                    if (varargSuperSubscriptions != null && !varargSuperSubscriptions.isEmpty()) {
                        if (asArray == null) {
                            asArray = (Object[]) Array.newInstance(messageClass1, 2);
                            asArray[0] = message1;
                            asArray[1] = message2;
                        }

                        current = varargSuperSubscriptions.head;
                        while (current != null) {
                            sub = current.getValue();
                            current = current.next();

                            // this catches all exception types
                            sub.publishToSubscription(this, subsPublished, asArray);
                        }
                    }
                } else {
                    StrongConcurrentSet<Subscription> varargSuperMultiSubscriptions = manager.getVarArgSuperSubscriptions(messageClass1, messageClass2);

                    // now get array based superClasses (but only if those ALSO accept vararg)
                    if (varargSuperMultiSubscriptions != null && !varargSuperMultiSubscriptions.isEmpty()) {
                        current = varargSuperMultiSubscriptions.head;
                        while (current != null) {
                            sub = current.getValue();
                            current = current.next();

                            // since each sub will be for the "lowest common demoninator", we have to re-create
                            // this array from the componentType every time -- since it will be different
                            Class<?> componentType = sub.getHandledMessageTypes()[0].getComponentType();
                            Object[] asArray = (Object[]) Array.newInstance(componentType, 2);
                            asArray[0] = message1;
                            asArray[1] = message2;

                            // this catches all exception types
                            sub.publishToSubscription(this, subsPublished, asArray);
                        }
                    }
                }
            }
        }


        if (!subsPublished.bool) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            StrongConcurrentSet<Subscription> deadSubscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
            if (deadSubscriptions != null && !deadSubscriptions.isEmpty())  {
                DeadMessage deadMessage = new DeadMessage(message1, message2);

                current = deadSubscriptions.head;
                while (current != null) {
                    sub = current.getValue();
                    current = current.next();

                    // this catches all exception types
                    sub.publishToSubscription(this, subsPublished, deadMessage);
                }
            }
        }
    }

    @Override
    public void publish(final Object message1, final Object message2, final Object message3) {
        SubscriptionManager manager = this.subscriptionManager;

        Class<?> messageClass1 = message1.getClass();
        Class<?> messageClass2 = message2.getClass();
        Class<?> messageClass3 = message3.getClass();

        StrongConcurrentSet<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass1, messageClass2, messageClass3);
        BooleanHolder subsPublished = this.booleanThreadLocal.get();
        subsPublished.bool = false;

        ISetEntry<Subscription> current;
        Subscription sub;

        // Run subscriptions
        if (subscriptions != null) {
            current = subscriptions.head;
            while (current != null) {
                sub = current.getValue();
                current = current.next();

                // this catches all exception types
                sub.publishToSubscription(this, subsPublished, message1, message2, message3);
            }
        }


        if (!this.forceExactMatches) {
            StrongConcurrentSet<Subscription> superSubscriptions = manager.getSuperSubscriptions(messageClass1, messageClass2, messageClass3);
            // now get superClasses
            if (superSubscriptions != null) {
                current = superSubscriptions.head;
                while (current != null) {
                    sub = current.getValue();
                    current = current.next();

                    // this catches all exception types
                    sub.publishToSubscription(this, subsPublished, message1, message2, message3);
                }
            }

            // publish to var arg, only if not already an array, and all of the same type
            if (manager.hasVarArgPossibility()) {
                if (messageClass1 == messageClass2 && messageClass1 == messageClass3) {
                    Object[] asArray = null;
                    StrongConcurrentSet<Subscription> varargSubscriptions = manager.getVarArgSubscriptions(messageClass1);
                    if (varargSubscriptions != null && !varargSubscriptions.isEmpty()) {
                        asArray = (Object[]) Array.newInstance(messageClass1, 3);
                        asArray[0] = message1;
                        asArray[1] = message2;
                        asArray[2] = message3;

                        current = varargSubscriptions.head;
                        while (current != null) {
                            sub = current.getValue();
                            current = current.next();

                            // this catches all exception types
                            sub.publishToSubscription(this, subsPublished, asArray);
                        }
                    }

                    StrongConcurrentSet<Subscription> varargSuperSubscriptions = manager.getVarArgSuperSubscriptions(messageClass1);
                    // now get array based superClasses (but only if those ALSO accept vararg)
                    if (varargSuperSubscriptions != null && !varargSuperSubscriptions.isEmpty()) {
                        if (asArray == null) {
                            asArray = (Object[]) Array.newInstance(messageClass1, 3);
                            asArray[0] = message1;
                            asArray[1] = message2;
                            asArray[2] = message3;
                        }

                        current = varargSuperSubscriptions.head;
                        while (current != null) {
                            sub = current.getValue();
                            current = current.next();

                            // this catches all exception types
                            sub.publishToSubscription(this, subsPublished, asArray);
                        }
                    }
                } else {
                    StrongConcurrentSet<Subscription> varargSuperMultiSubscriptions = manager.getVarArgSuperSubscriptions(messageClass1, messageClass2, messageClass3);

                    // now get array based superClasses (but only if those ALSO accept vararg)
                    if (varargSuperMultiSubscriptions != null && !varargSuperMultiSubscriptions.isEmpty()) {
                        current = varargSuperMultiSubscriptions.head;
                        while (current != null) {
                            sub = current.getValue();
                            current = current.next();

                            // since each sub will be for the "lowest common demoninator", we have to re-create
                            // this array from the componentType every time -- since it will be different
                            Class<?> componentType = sub.getHandledMessageTypes()[0].getComponentType();
                            Object[] asArray = (Object[]) Array.newInstance(componentType, 3);
                            asArray[0] = message1;
                            asArray[1] = message2;
                            asArray[2] = message3;

                            // this catches all exception types
                            sub.publishToSubscription(this, subsPublished, asArray);
                        }
                    }
                }
            }
        }


        if (!subsPublished.bool) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            StrongConcurrentSet<Subscription> deadSubscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
            if (deadSubscriptions != null && !deadSubscriptions.isEmpty())  {
                DeadMessage deadMessage = new DeadMessage(message1, message2, message3);

                current = deadSubscriptions.head;
                while (current != null) {
                    sub = current.getValue();
                    current = current.next();

                    // this catches all exception types
                    sub.publishToSubscription(this, subsPublished, deadMessage);
                }
            }
        }
    }

    @Override
    public void publishAsync(final Object message) {
        if (message != null) {
            try {
                this.dispatchQueue.transfer(message);
            } catch (Exception e) {
                handlePublicationError(new PublicationError()
                    .setMessage("Error while adding an asynchronous message")
                    .setCause(e)
                    .setPublishedObject(message));
            }
        } else {
            throw new NullPointerException("Message cannot be null.");
        }
    }

    @Override
    public void publishAsync(final Object message1, final Object message2) {
        if (message1 != null && message2 != null) {
            try {
                this.dispatchQueue.transfer(message1, message2);
            } catch (Exception e) {
                handlePublicationError(new PublicationError()
                    .setMessage("Error while adding an asynchronous message")
                    .setCause(e)
                    .setPublishedObject(message1, message2));
            }
        } else {
            throw new NullPointerException("Messages cannot be null.");
        }
    }

    @Override
    public void publishAsync(final Object message1, final Object message2, final Object message3) {
        if (message1 != null || message2 != null | message3 != null) {
            try {
                this.dispatchQueue.transfer(message1, message2, message3);
            } catch (Exception e) {
                handlePublicationError(new PublicationError()
                    .setMessage("Error while adding an asynchronous message")
                    .setCause(e)
                    .setPublishedObject(message1, message2, message3));
            }
        } else {
            throw new NullPointerException("Messages cannot be null.");
        }
    }
}
