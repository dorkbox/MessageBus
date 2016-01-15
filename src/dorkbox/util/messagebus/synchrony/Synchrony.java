package dorkbox.util.messagebus.synchrony;

import dorkbox.util.messagebus.subscription.Subscription;

/**
 *
 */
public
interface Synchrony {
    void publish(final Subscription[] subscriptions, Object message1) throws Throwable;
    void publish(final Subscription[] subscriptions, Object message1, Object message2) throws Throwable ;
    void publish(final Subscription[] subscriptions, Object message1, Object message2, Object message3) throws Throwable ;
    void publish(final Subscription[] subscriptions, Object[] messages) throws Throwable ;

    void start();
    void shutdown();
    boolean hasPendingMessages();
}
