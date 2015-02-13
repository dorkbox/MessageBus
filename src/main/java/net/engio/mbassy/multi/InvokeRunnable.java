package net.engio.mbassy.multi;

import java.lang.reflect.Array;
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
        Object[] vararg = null;

        for (Subscription sub : subs) {
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
}
