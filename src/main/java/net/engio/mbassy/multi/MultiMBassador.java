package net.engio.mbassy.multi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import net.engio.mbassy.multi.common.DeadMessage;
import net.engio.mbassy.multi.common.LinkedTransferQueue;
import net.engio.mbassy.multi.common.NamedThreadFactory;
import net.engio.mbassy.multi.common.TransferQueue;
import net.engio.mbassy.multi.error.IPublicationErrorHandler;
import net.engio.mbassy.multi.error.PublicationError;
import net.engio.mbassy.multi.subscription.Subscription;
import net.engio.mbassy.multi.subscription.SubscriptionManager;

/**
 * The base class for all message bus implementations with support for asynchronous message dispatch
 *
 * @Author bennidi
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class MultiMBassador implements IMessageBus {

    // error handling is first-class functionality
    // this handler will receive all errors that occur during message dispatch or message handling
    private final List<IPublicationErrorHandler> errorHandlers = new ArrayList<IPublicationErrorHandler>();

    private final SubscriptionManager subscriptionManager;
    private final TransferQueue<Runnable> dispatchQueue = new LinkedTransferQueue<Runnable>();


    // all threads that are available for asynchronous message dispatching
    private List<Thread> threads;

    public MultiMBassador() {
        this(Runtime.getRuntime().availableProcessors());
//        this(2);
    }


    public MultiMBassador(int numberOfThreads) {
        if (numberOfThreads < 1) {
            numberOfThreads = 1; // at LEAST 1 thread
        }

        this.subscriptionManager = new SubscriptionManager(numberOfThreads);
        this.threads = new ArrayList<Thread>(numberOfThreads);

        NamedThreadFactory dispatchThreadFactory = new NamedThreadFactory("MessageBus");
        for (int i = 0; i < numberOfThreads; i++) {
            // each thread will run forever and process incoming message publication requests
            Runnable runnable = new Runnable() {
                @SuppressWarnings("null")
                @Override
                public void run() {
                    TransferQueue<Runnable> IN_QUEUE= MultiMBassador.this.dispatchQueue;
                    Runnable event = null;
                    int counter;

                    while (true) {
                        try {
                            counter = 200;
                            while ((event = IN_QUEUE.poll()) == null) {
                                if (counter > 0) {
                                    --counter;
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
        for (IPublicationErrorHandler errorHandler : this.errorHandlers) {
            errorHandler.handleError(error);
        }
    }

    @Override
    public void unsubscribe(Object listener) {
        this.subscriptionManager.unsubscribe(listener);
    }


    @Override
    public void subscribe(Object listener) {
        this.subscriptionManager.subscribe(listener);
    }

    @Override
    public boolean hasPendingMessages() {
        return !this.dispatchQueue.isEmpty();
    }

    @Override
    public void shutdown() {
        for (Thread t : this.threads) {
            t.interrupt();
        }
    }


    @Override
    public void publish(Object message) {
        SubscriptionManager manager = this.subscriptionManager;

        Class<?> messageClass = message.getClass();


        Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass);
        // Run subscriptions
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (Subscription sub : subscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, message);
            }
        } else {
            // Dead Event must EXACTLY MATCH (no subclasses or varargs permitted)
            Collection<Subscription> deadSubscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);

            if (deadSubscriptions != null && !deadSubscriptions.isEmpty())  {
                DeadMessage deadMessage = new DeadMessage(message);
                for (Subscription sub : deadSubscriptions) {
                    // this catches all exception types
                    sub.publishToSubscription(this, deadMessage);
                }
            }
        }

        Collection<Subscription> superSubscriptions = manager.getSuperSubscriptions(messageClass);
        // now get superClasses
        if (superSubscriptions != null) {
            for (Subscription sub : superSubscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, message);
            }
        }
    }

    @SuppressWarnings("null")
    @Override
    public void publish(Object message1, Object message2) {
        SubscriptionManager manager = this.subscriptionManager;

        Class<?> messageClass1 = message1.getClass();
        Class<?> messageClass2 = message2.getClass();
            Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass1, messageClass2);
            boolean validSubs = subscriptions != null && !subscriptions.isEmpty();

            Collection<Subscription> deadSubscriptions = null;
            if (!validSubs) {
                // Dead Event. must EXACTLY MATCH (no subclasses or varargs)
                deadSubscriptions  = manager.getSubscriptionsByMessageType(DeadMessage.class);
            }

            Collection<Subscription> superSubscriptions = manager.getSuperSubscriptions(messageClass1, messageClass2);


        // Run subscriptions
        if (validSubs) {
            for (Subscription sub : subscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, message1, message2);
            }
        } else if (deadSubscriptions != null && !deadSubscriptions.isEmpty()) {
            DeadMessage deadMessage = new DeadMessage(message1, message2);

            for (Subscription sub : deadSubscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, deadMessage);
            }
            // Dead Event. only matches EXACT handlers (no vararg, no subclasses)
            return;
        }


        // now get superClasses
        if (superSubscriptions != null) {
            for (Subscription sub : superSubscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, message1, message2);
            }
        }
    }

    @SuppressWarnings("null")
    @Override
    public void publish(Object message1, Object message2, Object message3) {
        SubscriptionManager manager = this.subscriptionManager;

        Class<?> messageClass1 = message1.getClass();
        Class<?> messageClass2 = message2.getClass();
        Class<?> messageClass3 = message3.getClass();
            Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass1, messageClass2, messageClass3);
            boolean validSubs = subscriptions != null && !subscriptions.isEmpty();

            Collection<Subscription> deadSubscriptions = null;
            if (!validSubs) {
                // Dead Event. must EXACTLY MATCH (no subclasses or varargs)
                deadSubscriptions  = manager.getSubscriptionsByMessageType(DeadMessage.class);
            }

            Collection<Subscription> superSubscriptions = manager.getSuperSubscriptions(messageClass1, messageClass2, messageClass3);


        // Run subscriptions
        if (validSubs) {
            for (Subscription sub : subscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, message1, message2, message3);
            }
        } else if (deadSubscriptions != null && !deadSubscriptions.isEmpty()) {
            DeadMessage deadMessage = new DeadMessage(message1, message2, message3);

            for (Subscription sub : deadSubscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, deadMessage);
            }
            // Dead Event. only matches EXACT handlers (no vararg, no subclasses)
            return;
        }


        // now get superClasses
        if (superSubscriptions != null) {
            for (Subscription sub : superSubscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, message1, message2, message3);
            }
        }

    }

    @SuppressWarnings("null")
    @Override
    public void publish(Object... messages) {
        SubscriptionManager manager = this.subscriptionManager;

        int size = messages.length;
        boolean allSameType = true;

        Class<?>[] messageClasses = new Class[size];
        Class<?> first = null;
        if (size > 0) {
            first = messageClasses[0] = messages[0].getClass();
        }

        for (int i=1;i<size;i++) {
            messageClasses[i] = messages[i].getClass();
            if (first != messageClasses[i]) {
                allSameType = false;
            }
        }

            Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClasses);
            boolean validSubs = subscriptions != null && !subscriptions.isEmpty();

            Collection<Subscription> deadSubscriptions = null;
            if (!validSubs) {
                // Dead Event. must EXACTLY MATCH (no subclasses or varargs)
                deadSubscriptions  = manager.getSubscriptionsByMessageType(DeadMessage.class);
            }


        // Run subscriptions
        if (validSubs) {
            for (Subscription sub : subscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, messages);
            }
        } else if (deadSubscriptions != null && !deadSubscriptions.isEmpty()) {
            DeadMessage deadMessage = new DeadMessage(messages);

            for (Subscription sub : deadSubscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, deadMessage);
            }
            // Dead Event. only matches EXACT handlers (no vararg, no subclasses)
            return;
        }


        // now get superClasses  (not supported)
//        if (superSubscriptions != null) {
//            for (Subscription sub : superSubscriptions) {
//                // this catches all exception types
//                sub.publishToSubscription(this, message);
//            }
//        }

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

//            try {
//                this.dispatchQueue.put(runnable);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//                // log.error(e);
//
//                handlePublicationError(new PublicationError()
//                .setMessage("Error while adding an asynchronous message")
//                .setCause(e)
//                .setPublishedObject(message));
//            }



//            int counter = 200;
//            while (!this.dispatchQueue.offer(runnable)) {
//                if (counter > 0) {
//                    --counter;
//                    LockSupport.parkNanos(1L);
//                } else {
                    try {
                        this.dispatchQueue.transfer(runnable);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        // log.error(e);

                        handlePublicationError(new PublicationError()
                        .setMessage("Error while adding an asynchronous message")
                        .setCause(e)
                        .setPublishedObject(message));
                    }
//                    break;
//                }
//            }
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
                e.printStackTrace();
                // log.error(e);

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
                e.printStackTrace();
                // log.error(e);

                handlePublicationError(new PublicationError()
                .setMessage("Error while adding an asynchronous message")
                .setCause(e)
                .setPublishedObject(message1, message2, message3));
            }
        }
    }

    @Override
    public void publishAsync(final Object... messages) {
        if (messages != null) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    MultiMBassador.this.publish(messages);
                }
            };

            try {
                this.dispatchQueue.transfer(runnable);
            } catch (InterruptedException e) {
                e.printStackTrace();
                // log.error(e);

                handlePublicationError(new PublicationError()
                .setMessage("Error while adding an asynchronous message")
                .setCause(e)
                .setPublishedObject(messages));
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
                e.printStackTrace();
                // log.error(e);

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
                e.printStackTrace();
                // log.error(e);

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
                e.printStackTrace();
                // log.error(e);

                handlePublicationError(new PublicationError()
                .setMessage("Error while adding an asynchronous message")
                .setCause(e)
                .setPublishedObject(message1, message2, message3));
            }
        }
    }

    @Override
    public void publishAsync(long timeout, TimeUnit unit, final Object... messages) {
        if (messages != null) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    MultiMBassador.this.publish(messages);
                }
            };

            try {
                this.dispatchQueue.tryTransfer(runnable, timeout, unit);
            } catch (InterruptedException e) {
                e.printStackTrace();
                // log.error(e);

                handlePublicationError(new PublicationError()
                .setMessage("Error while adding an asynchronous message")
                .setCause(e)
                .setPublishedObject(messages));
            }
        }
    }
}
