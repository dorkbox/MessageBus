package net.engio.mbassy.multi;

import java.util.Collection;

import net.engio.mbassy.multi.error.ErrorHandlingSupport;
import net.engio.mbassy.multi.subscription.Subscription;


/**
 * @author dorkbox, llc Date: 2/2/15
 */
public class InvokeRunnable implements Runnable {

    private ErrorHandlingSupport errorHandler;
    private Collection<Subscription> subscriptions;
    private Object message;

    public InvokeRunnable(ErrorHandlingSupport errorHandler, Collection<Subscription> subscriptions, Object message) {
        this.errorHandler = errorHandler;
        this.subscriptions = subscriptions;
        this.message = message;
    }

    @Override
    public void run() {
        ErrorHandlingSupport errorHandler = this.errorHandler;
        Collection<Subscription> subs = this.subscriptions;
        Object message = this.message;

//        for (Subscription sub : subs) {
//            sub.publishToSubscriptionSingle(errorHandler, message);
//        }
    }
}
