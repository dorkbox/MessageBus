package net.engio.mbassy.multi;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import net.engio.mbassy.multi.common.DeadMessage;
import net.engio.mbassy.multi.common.DisruptorThreadFactory;
import net.engio.mbassy.multi.common.LinkedTransferQueue;
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

    private final TransferQueue<Runnable> dispatchQueue;


    // all threads that are available for asynchronous message dispatching
    private List<Thread> threads;

    public MultiMBassador() {
        this(Runtime.getRuntime().availableProcessors());
    }


    public MultiMBassador(int numberOfThreads) {
        if (numberOfThreads < 1) {
            numberOfThreads = 1; // at LEAST 1 threads
        }


        this.subscriptionManager = new SubscriptionManager();
        this.dispatchQueue = new LinkedTransferQueue<Runnable>();


        int dispatchSize = numberOfThreads;
        this.threads = new ArrayList<Thread>();


        DisruptorThreadFactory dispatchThreadFactory = new DisruptorThreadFactory("MB_Dispatch");
        for (int i = 0; i < dispatchSize; i++) {
            // each thread will run forever and process incoming message publication requests
            Runnable runnable = new Runnable() {
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
        Class<?> messageClass = message.getClass();

        SubscriptionManager manager = this.subscriptionManager;
//        Collection<Subscription> subscriptions = subscriptionManager.getSubscriptionsByMessageType(messageClass);

        manager.readLock();

        Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass);
        boolean empty = subscriptions.isEmpty();

        Collection<Subscription> deadSubscriptions = null;
        if (empty) {
            // Dead Event. must EXACTLY MATCH (no subclasses or varargs)
            deadSubscriptions  = manager.getSubscriptionsByMessageType(DeadMessage.class);
        }
        Collection<Class<?>> superClasses = manager.getSuperClasses(messageClass);
        Collection<Subscription> varArgs = manager.getVarArgs(messageClass);

        manager.readUnLock();

        if (!empty) {
            for (Subscription sub : subscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, message);
            }
        } else if (deadSubscriptions != null && !deadSubscriptions.isEmpty()) {
            DeadMessage deadMessage = new DeadMessage(message);

            for (Subscription sub : deadSubscriptions) {
                // this catches all exception types
                sub.publishToSubscription(this, deadMessage);
            }
        }










//        if (subscriptions.isEmpty()) {
//            // Dead Event. only matches EXACT handlers (no vararg, no subclasses)
//            subscriptions = this.subscriptionManager.getSubscriptionsByMessageType(DeadMessage.class);
//
//            DeadMessage deadMessage = new DeadMessage(message);
//            if (!subscriptions.isEmpty()) {
//                for (Subscription sub : subscriptions) {
//                    // this catches all exception types
//                    sub.publishToSubscription(this, deadMessage);
//                }
//            }
//        }
//        else {
////            Object[] vararg = null;
//            for (Subscription sub : subscriptions) {
//                // this catches all exception types
//                sub.publishToSubscription(this, message);
//
////                if (sub.isVarArg()) {
////                    // messageClass will NEVER be an array to begin with, since that will call the multi-arg method
////                    if (vararg == null) {
////                        // messy, but the ONLY way to do it.
////                        vararg = (Object[]) Array.newInstance(message.getClass(), 1);
////                        vararg[0] = message;
////
////                        Object[] newInstance =  new Object[1];
////                        newInstance[0] = vararg;
////                        vararg = newInstance;
////                    }
////
////                    // this catches all exception types
////                    sub.publishToSubscription(this, vararg);
////                    continue;
////                }
//            }
//        }
    }


    @Override
    public void publish(Object message1, Object message2) {
        try {
            Class<?> messageClass1 = message1.getClass();
            Class<?> messageClass2 = message2.getClass();

            SubscriptionManager manager = this.subscriptionManager;
            Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass1, messageClass2);

            if (subscriptions == null || subscriptions.isEmpty()) {
                subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);

                DeadMessage deadMessage = new DeadMessage(message1, message2);

                for (Subscription sub : subscriptions) {
                    sub.publishToSubscription(this, deadMessage);
                }
            }
            else {
                Object[] vararg = null;

                for (Subscription sub : subscriptions) {
                    if (sub.isVarArg()) {
                        Class<?> class1 = message1.getClass();
                        Class<?> class2 = message2.getClass();
                        if (!class1.isArray() && class1 == class2) {
                            if (vararg == null) {
                                // messy, but the ONLY way to do it.
                                vararg = (Object[]) Array.newInstance(class1, 2);
                                vararg[0] = message1;
                                vararg[1] = message2;

                                Object[] newInstance =  (Object[]) Array.newInstance(vararg.getClass(), 1);
                                newInstance[0] = vararg;
                                vararg = newInstance;
                            }

                            sub.publishToSubscription(this, vararg);
                            continue;
                        }
                    }

                    sub.publishToSubscription(this, message1, message2);
                }
            }
        } catch (Throwable e) {
            handlePublicationError(new PublicationError()
                    .setMessage("Error during publication of message")
                    .setCause(e)
                    .setPublishedObject(message1, message2));
        }
    }

    @Override
    public void publish(Object message1, Object message2, Object message3) {
//        try {
//            Class<?> messageClass1 = message1.getClass();
//            Class<?> messageClass2 = message2.getClass();
//            Class<?> messageClass3 = message3.getClass();
//
//            SubscriptionManager manager = this.subscriptionManager;
//            Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass1, messageClass2, messageClass3);
//
//            if (subscriptions == null || subscriptions.isEmpty()) {
//                // Dead Event
//                subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//                DeadMessage deadMessage = new DeadMessage(message1, message2, message3);
//
//                for (Subscription sub : subscriptions) {
//                    sub.publishToSubscription(this, deadMessage);
//                }
//            } else {
//                Object[] vararg = null;
//
//                for (Subscription sub : subscriptions) {
//                    boolean handled = false;
//                    if (sub.isVarArg()) {
//                        Class<?> class1 = message1.getClass();
//                        Class<?> class2 = message2.getClass();
//                        Class<?> class3 = message3.getClass();
//                        if (!class1.isArray() && class1 == class2 && class2 == class3) {
//                            // messy, but the ONLY way to do it.
//                            if (vararg == null) {
//                                vararg = (Object[]) Array.newInstance(class1, 3);
//                                vararg[0] = message1;
//                                vararg[1] = message2;
//                                vararg[2] = message3;
//
//                                Object[] newInstance =  (Object[]) Array.newInstance(vararg.getClass(), 1);
//                                newInstance[0] = vararg;
//                                vararg = newInstance;
//                            }
//
//                            handled = true;
//                            sub.publishToSubscription(this, vararg);
//                        }
//                    }
//
//                    if (!handled) {
//                        sub.publishToSubscription(this, message1, message2, message3);
//                    }
//                }
//
//                // if the message did not have any listener/handler accept it
//                if (subscriptions.isEmpty()) {
//                    // cannot have DeadMessage published to this, so no extra check necessary
//                    // Dead Event
//                    subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//                    DeadMessage deadMessage = new DeadMessage(message1, message2, message3);
//
//                    for (Subscription sub : subscriptions) {
//                        sub.publishToSubscription(this, deadMessage);
//                    }
//
//                    // cleanup
//                    deadMessage = null;
//                }
//            }
//        } catch (Throwable e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error during publication of message")
//                    .setCause(e)
//                    .setPublishedObject(message1, message2, message3));
//        }
    }

    @Override
    public void publish(Object... messages) {
//        try {
//            // cannot have DeadMessage published to this!
//            int size = messages.length;
//            boolean allSameType = true;
//
//            Class<?>[] messageClasses = new Class[size];
//            Class<?> first = null;
//            if (size > 0) {
//                first = messageClasses[0] = messages[0].getClass();
//            }
//
//            for (int i=1;i<size;i++) {
//                messageClasses[i] = messages[i].getClass();
//                if (first != messageClasses[i]) {
//                    allSameType = false;
//                }
//            }
//
//            SubscriptionManager manager = this.subscriptionManager;
//            Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClasses);
//
//            if (subscriptions == null || subscriptions.isEmpty()) {
//                // Dead Event
//                subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//                DeadMessage deadMessage = new DeadMessage(messages);
//
//                for (Subscription sub : subscriptions) {
//                    sub.publishToSubscription(this, deadMessage);
//                }
//            } else {
//                Object[] vararg = null;
//
//                for (Subscription sub : subscriptions) {
//                    boolean handled = false;
//                    if (first != null && allSameType && sub.isVarArg()) {
//                        if (vararg == null) {
//                            // messy, but the ONLY way to do it.
//                            vararg = (Object[]) Array.newInstance(first, size);
//
//                            for (int i=0;i<size;i++) {
//                                vararg[i] = messages[i];
//                            }
//
//                            Object[] newInstance =  (Object[]) Array.newInstance(vararg.getClass(), 1);
//                            newInstance[0] = vararg;
//                            vararg = newInstance;
//                        }
//
//                        handled = true;
//                        sub.publishToSubscription(this, vararg);
//                    }
//
//                    if (!handled) {
//                        sub.publishToSubscription(this, messages);
//                    }
//                }
//
//                // if the message did not have any listener/handler accept it
//                if (subscriptions.isEmpty()) {
//                    // cannot have DeadMessage published to this, so no extra check necessary
//                    // Dead Event
//
//                    subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);
//                    DeadMessage deadMessage = new DeadMessage(messages);
//
//                    for (Subscription sub : subscriptions) {
//                        sub.publishToSubscription(this, deadMessage);
//                    }
//                }
//            }
//        } catch (Throwable e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error during publication of message")
//                    .setCause(e)
//                    .setPublishedObject(messages));
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



            // faster if we can skip locking
//            int counter = 200;
//            while (!this.dispatchQueue.offer(message)) {
////                if (counter > 100) {
////                    --counter;
////                    Thread.yield();
////                } else
//                    if (counter > 0) {
//                    --counter;
//                    LockSupport.parkNanos(1L);
//                } else {
                    try {
                        this.dispatchQueue.transfer(runnable);
                        return;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        // log.error(e);

                        handlePublicationError(new PublicationError()
                        .setMessage("Error while adding an asynchronous message")
                        .setCause(e)
                        .setPublishedObject(message));
                    }
                }
//            }
//        }
    }

    @Override
    public void publishAsync(Object message1, Object message2) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.TWO;
//            eventJob.message1 = message1;
//            eventJob.message2 = message2;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                                        .setMessage("Error while adding an asynchronous message")
//                                        .setCause(e)
//                                        .setPublishedObject(message1, message2));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }

    @Override
    public void publishAsync(Object message1, Object message2, Object message3) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.THREE;
//            eventJob.message1 = message1;
//            eventJob.message2 = message2;
//            eventJob.message3 = message3;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//            .setMessage("Error while adding an asynchronous message")
//            .setCause(e)
//            .setPublishedObject(new Object[] {message1, message2, message3}));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }

    @Override
    public void publishAsync(Object... messages) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.ARRAY;
//            eventJob.messages = messages;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error while adding an asynchronous message")
//                    .setCause(e)
//                    .setPublishedObject(messages));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }

    @Override
    public void publishAsync(long timeout, TimeUnit unit, Object message) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//        final long expireTimestamp = TimeUnit.MILLISECONDS.convert(timeout, unit) + System.currentTimeMillis();
//
//        // Inserts the specified element into this buffer, waiting up to the specified wait time if necessary for space
//        // to become available.
//        while (!ringBuffer.hasAvailableCapacity(1)) {
//            LockSupport.parkNanos(10L);
//            if (expireTimestamp <= System.currentTimeMillis()) {
//                handlePublicationError(new PublicationError()
//                                            .setMessage("Error while adding an asynchronous message")
//                                            .setCause(new Exception("Timeout"))
//                                            .setPublishedObject(message));
//                return;
//            }
//        }
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.ONE;
//            eventJob.message1 = message;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                                        .setMessage("Error while adding an asynchronous message")
//                                        .setCause(e)
//                                        .setPublishedObject(message));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }
    @Override
    public void publishAsync(long timeout, TimeUnit unit, Object message1, Object message2) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//        final long expireTimestamp = TimeUnit.MILLISECONDS.convert(timeout, unit) + System.currentTimeMillis();
//
//        // Inserts the specified element into this buffer, waiting up to the specified wait time if necessary for space
//        // to become available.
//        while (!ringBuffer.hasAvailableCapacity(1)) {
//            LockSupport.parkNanos(10L);
//            if (expireTimestamp <= System.currentTimeMillis()) {
//                handlePublicationError(new PublicationError()
//                        .setMessage("Error while adding an asynchronous message")
//                        .setCause(new Exception("Timeout"))
//                        .setPublishedObject(message1, message2));
//                return;
//            }
//        }
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.TWO;
//            eventJob.message1 = message1;
//            eventJob.message2 = message2;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error while adding an asynchronous message")
//                    .setCause(e)
//                    .setPublishedObject(message1, message2));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }
    @Override
    public void publishAsync(long timeout, TimeUnit unit, Object message1, Object message2, Object message3) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//        final long expireTimestamp = TimeUnit.MILLISECONDS.convert(timeout, unit) + System.currentTimeMillis();
//
//        // Inserts the specified element into this buffer, waiting up to the specified wait time if necessary for space
//        // to become available.
//        while (!ringBuffer.hasAvailableCapacity(1)) {
//            LockSupport.parkNanos(10L);
//            if (expireTimestamp <= System.currentTimeMillis()) {
//                handlePublicationError(new PublicationError()
//                    .setMessage("Error while adding an asynchronous message")
//                    .setCause(new Exception("Timeout"))
//                    .setPublishedObject(message1, message2, message3));
//                return;
//            }
//        }
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.THREE;
//            eventJob.message1 = message1;
//            eventJob.message2 = message2;
//            eventJob.message3 = message3;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error while adding an asynchronous message")
//                    .setCause(e)
//                    .setPublishedObject(message1, message2, message3));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }

    @Override
    public void publishAsync(long timeout, TimeUnit unit, Object... messages) {
//        // put this on the disruptor ring buffer
//        final RingBuffer<MessageHolder> ringBuffer = this.ringBuffer;
//        final long expireTimestamp = TimeUnit.MILLISECONDS.convert(timeout, unit) + System.currentTimeMillis();
//
//        // Inserts the specified element into this buffer, waiting up to the specified wait time if necessary for space
//        // to become available.
//        while (!ringBuffer.hasAvailableCapacity(1)) {
//            LockSupport.parkNanos(10L);
//            if (expireTimestamp <= System.currentTimeMillis()) {
//                handlePublicationError(new PublicationError()
//                        .setMessage("Error while adding an asynchronous message")
//                        .setCause(new Exception("Timeout"))
//                        .setPublishedObject(messages));
//                return;
//            }
//        }
//
//        // setup the job
//        final long seq = ringBuffer.next();
//        try {
//            MessageHolder eventJob = ringBuffer.get(seq);
//            eventJob.messageType = MessageType.ARRAY;
//            eventJob.messages = messages;
//        } catch (Exception e) {
//            handlePublicationError(new PublicationError()
//                    .setMessage("Error while adding an asynchronous message")
//                    .setCause(e)
//                    .setPublishedObject(messages));
//        } finally {
//            // always publish the job
//            ringBuffer.publish(seq);
//        }
    }
}
