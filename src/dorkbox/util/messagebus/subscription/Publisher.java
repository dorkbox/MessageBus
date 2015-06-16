package dorkbox.util.messagebus.subscription;

public interface Publisher {
    void publish(SubscriptionManager subscriptionManager, Object message1);

    void publish(SubscriptionManager subscriptionManager, Object message1, Object message2);

    void publish(SubscriptionManager subscriptionManager, Object message1, Object message2, Object message3);

    void publish(SubscriptionManager subscriptionManager, Object[] messages);
}
