package dorkbox.util.messagebus.synchrony;

import dorkbox.util.messagebus.subscription.Subscription;

public
class Sync implements Synchrony {
    public
    void publish(final Subscription[] subscriptions, final Object message1) throws Throwable {
        Subscription sub;
        for (int i = 0; i < subscriptions.length; i++) {
            sub = subscriptions[i];
            sub.publish(message1);
        }
    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1, final Object message2) throws Throwable  {

    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object message1, final Object message2, final Object message3) throws Throwable  {

    }

    @Override
    public
    void publish(final Subscription[] subscriptions, final Object[] messages) throws Throwable  {

    }

    @Override
    public
    void start() {

    }

    @Override
    public
    void shutdown() {

    }

    @Override
    public
    boolean hasPendingMessages() {
        return false;
    }
}
