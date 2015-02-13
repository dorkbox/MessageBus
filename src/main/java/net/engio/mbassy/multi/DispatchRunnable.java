package net.engio.mbassy.multi;

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
    private TransferQueue<Runnable> invokeQueue;
    private SubscriptionManager manager;

    public DispatchRunnable(ErrorHandlingSupport errorHandler, SubscriptionManager subscriptionManager,
                            TransferQueue<Object> dispatchQueue, TransferQueue<Runnable> invokeQueue) {

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
        final TransferQueue<Runnable> OUT_queue = this.invokeQueue;

        Object message = null;
        int counter;

        while (true) {
            try {
                counter = MultiMBassador.WORK_RUN_BLITZ;
                while ((message = IN_queue.poll()) == null) {
                    if (counter > MultiMBassador.WORK_RUN_BLITZ_DIV2) {
                        --counter;
                        Thread.yield();
                    } else if (counter > 0) {
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
                    Runnable e = new InvokeRunnable(errorHandler, subscriptions, message);
                    OUT_queue.transfer(e);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message").setCause(e).setPublishedObject(message));
            }
        }
    }
}