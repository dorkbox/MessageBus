package dorkbox.util.messagebus;

import dorkbox.util.messagebus.subscription.Matcher;
import dorkbox.util.messagebus.subscription.SubscriptionManager;

public class MatcherAll implements Matcher {
    @Override
    public void publish(final SubscriptionManager subscriptionManager, final Object message1) throws Throwable {
        subscriptionManager.publishAll(message1);
    }

    @Override
    public void publish(final SubscriptionManager subscriptionManager, final Object message1, final Object message2) throws Throwable {
        subscriptionManager.publishAll(message1, message2);
    }

    @Override
    public void publish(final SubscriptionManager subscriptionManager, final Object message1, final Object message2, final Object message3)
                    throws Throwable {
        subscriptionManager.publishAll(message1, message2, message3);
    }

    @Override
    public void publish(final SubscriptionManager subscriptionManager, final Object[] messages) throws Throwable {
        subscriptionManager.publishAll(messages);
    }
}
