package dorkbox.util.messagebus.subscription;

import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.common.adapter.JavaVersionAdapter;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.utils.ClassUtils;
import dorkbox.util.messagebus.utils.VarArgUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Permits subscriptions that only use the first parameters as the signature. The publisher MUST provide the correct additional parameters,
 * and they must be of the correct type, otherwise it will throw an error.
 */
public class FirstArgSubscriber implements Subscriber {

    private final ErrorHandlingSupport errorHandler;

    // all subscriptions per message type. We perpetually KEEP the types, as this lowers the amount of locking required
    // this is the primary list for dispatching a specific message
    // write access is synchronized and happens only when a listener of a specific class is registered the first time

    // the following are used ONLY for FIRST ARG subscription/publication. (subscriptionsPerMessageMulti isn't used in this case)
    private final Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle_1;
    private final Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle_2;
    private final Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle_3;


    public FirstArgSubscriber(final ErrorHandlingSupport errorHandler, final ClassUtils classUtils) {
        this.errorHandler = errorHandler;

        // the following are used ONLY for FIRST ARG subscription/publication. (subscriptionsPerMessageMulti isn't used in this case)
        this.subscriptionsPerMessageSingle_1 = JavaVersionAdapter.get.concurrentMap(32, LOAD_FACTOR, 1);
        this.subscriptionsPerMessageSingle_2 = JavaVersionAdapter.get.concurrentMap(32, LOAD_FACTOR, 1);
        this.subscriptionsPerMessageSingle_3 = JavaVersionAdapter.get.concurrentMap(32, LOAD_FACTOR, 1);
    }

    // inside a write lock
    // add this subscription to each of the handled types
    // to activate this sub for publication
    private void registerFirst(final Subscription subscription, final Class<?> listenerClass,
                               final Map<Class<?>, ArrayList<Subscription>> subs_1, final Map<Class<?>, ArrayList<Subscription>> subs_2,
                               final Map<Class<?>, ArrayList<Subscription>> subs_3) {

        final MessageHandler handler = subscription.getHandler();
        final Class<?>[] messageHandlerTypes = handler.getHandledMessages();
        final int size = messageHandlerTypes.length;

        Class<?> type0 = messageHandlerTypes[0];

        switch (size) {
            case 1: {
                ArrayList<Subscription> subs = subs_1.get(type0);
                if (subs == null) {
                    subs = new ArrayList<Subscription>();

                    subs_1.put(type0, subs);
                }

                subs.add(subscription);
                return;
            }
            case 2: {
                ArrayList<Subscription> subs = subs_2.get(type0);
                if (subs == null) {
                    subs = new ArrayList<Subscription>();

                    subs_2.put(type0, subs);
                }

                subs.add(subscription);
                return;
            }
            case 3: {
                ArrayList<Subscription> subs = subs_3.get(type0);
                if (subs == null) {
                    subs = new ArrayList<Subscription>();

                    subs_3.put(type0, subs);
                }

                subs.add(subscription);
                return;
            }
            case 0:
            default: {
                errorHandler.handleError("Error while trying to subscribe class", listenerClass);
                return;
            }
        }
    }

    @Override
    public void register(final Class<?> listenerClass, final int handlersSize, final Subscription[] subsPerListener) {

        final Map<Class<?>, ArrayList<Subscription>> sub_1 = this.subscriptionsPerMessageSingle_1;
        final Map<Class<?>, ArrayList<Subscription>> sub_2 = this.subscriptionsPerMessageSingle_2;
        final Map<Class<?>, ArrayList<Subscription>> sub_3 = this.subscriptionsPerMessageSingle_3;


        Subscription subscription;

        for (int i = 0; i < handlersSize; i++) {
            subscription = subsPerListener[i];

            // activate this subscription for publication
            // now add this subscription to each of the handled types

            // only register based on the FIRST parameter
            registerFirst(subscription, listenerClass, sub_1, sub_2, sub_3);
        }
    }

    @Override
    public AtomicBoolean getVarArgPossibility() {
        return null;
    }

    @Override
    public VarArgUtils getVarArgUtils() {
        return null;
    }

    @Override
    public void shutdown() {
        this.subscriptionsPerMessageSingle_1.clear();
        this.subscriptionsPerMessageSingle_2.clear();
        this.subscriptionsPerMessageSingle_3.clear();
    }

    @Override
    public void clearConcurrentCollections() {

    }

    @Override
    public ArrayList<Subscription> getExactAsArray(final Class<?> messageClass) {
        return subscriptionsPerMessageSingle_1.get(messageClass);
    }

    @Override
    public ArrayList<Subscription> getExactAsArray(final Class<?> messageClass1, final Class<?> messageClass2) {
        return subscriptionsPerMessageSingle_2.get(messageClass1);
    }

    @Override
    public ArrayList<Subscription> getExactAsArray(final Class<?> messageClass1, final Class<?> messageClass2,
                                                   final Class<?> messageClass3) {
        return subscriptionsPerMessageSingle_3.get(messageClass1);
    }

    @Override
    public Subscription[] getExact(final Class<?> deadMessageClass) {
        return new Subscription[0];
    }

    @Override
    public Subscription[] getExact(final Class<?> messageClass1, final Class<?> messageClass2) {
        return new Subscription[0];
    }

    @Override
    public Subscription[] getExact(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        return new Subscription[0];
    }


    @Override
    public Subscription[] getExactAndSuper(final Class<?> messageClass) {
        return new Subscription[0];
    }

    @Override
    public Subscription[] getExactAndSuper(final Class<?> messageClass1, final Class<?> messageClass2) {
        return new Subscription[0];
    }

    @Override
    public Subscription[] getExactAndSuper(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        return new Subscription[0];
    }
}
