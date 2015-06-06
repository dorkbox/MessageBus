package dorkbox.util.messagebus.subscription;

public interface Matcher {
    void publish(SubscriptionManager subscriptionManager, Object message1) throws Throwable;

    void publish(SubscriptionManager subscriptionManager, Object message1, Object message2) throws Throwable;

    void publish(SubscriptionManager subscriptionManager, Object message1, Object message2, Object message3) throws Throwable;

    void publish(SubscriptionManager subscriptionManager, Object[] messages) throws Throwable;
}
