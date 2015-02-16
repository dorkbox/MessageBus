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
    private SubscriptionManager manager;

    public DispatchRunnable(ErrorHandlingSupport errorHandler, SubscriptionManager subscriptionManager,
                    TransferQueue<Object> dispatchQueue) {

        this.errorHandler = errorHandler;
        this.manager = subscriptionManager;
        this.dispatchQueue = dispatchQueue;
    }

    @Override
    public void run() {
        final SubscriptionManager manager = this.manager;
        final ErrorHandlingSupport errorHandler = this.errorHandler;
        final TransferQueue<Object> IN_queue = this.dispatchQueue;

        Object message = null;
        int counter;

        while (true) {
            try {
                counter = MultiMBassador.WORK_RUN_BLITZ;
                while ((message = IN_queue.poll()) == null) {
//                    if (counter > 100) {
//                        --counter;
//                        Thread.yield();
//                    } else
                        if (counter > 0) {
                        --counter;
                        LockSupport.parkNanos(1L);
                    } else {
                        message = IN_queue.take();
                        break;
                    }
                }

                Class<?> messageClass = message.getClass();
                Collection<Subscription> subscriptions = manager.getSubscriptionsByMessageType(messageClass);

                boolean empty = subscriptions.isEmpty();
                if (empty) {
                    // Dead Event
                    subscriptions = manager.getSubscriptionsByMessageType(DeadMessage.class);

                    DeadMessage deadMessage = new DeadMessage(message);
                    message = deadMessage;
                    empty = subscriptions.isEmpty();
                }

                if (!empty) {
                    Object[] vararg = null;
                    for (Subscription sub : subscriptions) {
                        boolean handled = false;
                        if (sub.isVarArg()) {
                            // messageClass will NEVER be an array to begin with, since that will call the multi-arg method
                            if (vararg == null) {
                                // messy, but the ONLY way to do it.
                                vararg = (Object[]) Array.newInstance(message.getClass(), 1);
                                vararg[0] = message;

                                Object[] newInstance =  new Object[1];
                                newInstance[0] = vararg;
                                vararg = newInstance;
                            }
                            handled = true;
                            sub.publishToSubscription(errorHandler, vararg);
                        }

                        if (!handled) {
                            sub.publishToSubscription(errorHandler, message);
                        }
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message").setCause(e).setPublishedObject(message));
            }
        }
    }
}