package net.engio.mbassy.multi;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.concurrent.locks.LockSupport;

import net.engio.mbassy.multi.common.DeadMessage;
import net.engio.mbassy.multi.common.TransferQueue;
import net.engio.mbassy.multi.error.ErrorHandlingSupport;
import net.engio.mbassy.multi.error.PublicationError;
import net.engio.mbassy.multi.subscription.Subscription;
import net.engio.mbassy.multi.subscription.SubscriptionManager;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class DispatchRunnable implements Runnable {

    private ErrorHandlingSupport errorHandler;
    private TransferQueue<Object> dispatchQueue;
    private TransferQueue<SubRunnable> invokeQueue;
    private SubscriptionManager manager;

    public DispatchRunnable(ErrorHandlingSupport errorHandler, SubscriptionManager subscriptionManager,
                            TransferQueue<Object> dispatchQueue, TransferQueue<SubRunnable> invokeQueue) {

        this.errorHandler = errorHandler;
        this.manager = subscriptionManager;
        this.dispatchQueue = dispatchQueue;
        this.invokeQueue = invokeQueue;
    }

    @Override
    public void run() {
        final SubscriptionManager manager = this.manager;
        final ErrorHandlingSupport errorHandler = this.errorHandler;
        final TransferQueue<Object> IN_queue = this.dispatchQueue;
        final TransferQueue<SubRunnable> OUT_queue = this.invokeQueue;

        final Runnable dummyRunnable = new Runnable() {
            @Override
            public void run() {
            }
        };

        Object message = null;
        int counter;

        while (true) {
            try {
                counter = 200;
                while ((message = IN_queue.poll()) == null) {
                    if (counter > 0) {
                        --counter;
                        LockSupport.parkNanos(1L);
                    } else {
                        message = IN_queue.take();
                        break;
                    }
                }

                @SuppressWarnings("null")
                Class<?> messageClass = message.getClass();

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
                        sub.publishToSubscriptionSingle(OUT_queue, errorHandler, message);
                    }

//                    OUT_queue.put(new InvokeRunnable(errorHandler, subscriptions, message));
                } else if (deadSubscriptions != null) {
                    if (!deadSubscriptions.isEmpty()) {
                        DeadMessage deadMessage = new DeadMessage(message);

                        for (Subscription sub : deadSubscriptions) {
                            sub.publishToSubscriptionSingle(OUT_queue, errorHandler, deadMessage);
                        }

//                        OUT_queue.put(new InvokeRunnable(errorHandler, deadSubscriptions, deadMessage));
                    }
                }

                // now get superClasses
                for (Class<?> superClass : superClasses) {
                    subscriptions = manager.getSubscriptionsByMessageType(superClass);

                    if (!subscriptions.isEmpty()) {
                        for (Subscription sub : subscriptions) {
                            sub.publishToSubscriptionSingle(OUT_queue, errorHandler, message);
                        }

//                        OUT_queue.put(new InvokeRunnable(errorHandler, subscriptions, message));
                    }
                }

                // now get varargs
                if (!varArgs.isEmpty()) {
                    // messy, but the ONLY way to do it.
                    Object[] vararg = (Object[]) Array.newInstance(message.getClass(), 1);
                    vararg[0] = message;

                    Object[] newInstance =  new Object[1];
                    newInstance[0] = vararg;
                    vararg = newInstance;

                    for (Subscription sub : varArgs) {
                        sub.publishToSubscription(OUT_queue, errorHandler, vararg);
                    }

//                    OUT_queue.put(new InvokeRunnable(errorHandler, varArgs, vararg));
                }

                // make sure it's synced at this point
//                OUT_queue.transfer(dummyRunnable);

            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message").setCause(e).setPublishedObject(message));
            }
        }
    }
}