package dorkbox.util.messagebus.publication;

import dorkbox.util.messagebus.common.DeadMessage;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.subscription.Publisher;
import dorkbox.util.messagebus.subscription.Subscription;
import dorkbox.util.messagebus.subscription.SubscriptionManager;

public class PublisherExactWithSuperTypes implements Publisher {
    private final ErrorHandlingSupport errorHandler;

    public PublisherExactWithSuperTypes(final ErrorHandlingSupport errorHandler) {
        this.errorHandler = errorHandler;
    }

    @Override
    public void publish(final SubscriptionManager subscriptionManager, final Object message1) {
        try {
            final Class<?> messageClass = message1.getClass();

            final Subscription[] subscriptions = subscriptionManager.getSubscriptionsExactAndSuper(messageClass); // can return null

            // Run subscriptions
            if (subscriptions != null) {
                Subscription sub;
                for (int i = 0; i < subscriptions.length; i++) {
                    sub = subscriptions[i];
                    sub.publish(message1);
                }
            }
            else {
                // Dead Event must EXACTLY MATCH (no subclasses)
                final Subscription[] deadSubscriptions = subscriptionManager.getSubscriptionsExact(DeadMessage.class); // can return null
                if (deadSubscriptions != null) {
                    final DeadMessage deadMessage = new DeadMessage(message1);

                    Subscription sub;
                    for (int i = 0; i < deadSubscriptions.length; i++) {
                        sub = deadSubscriptions[i];
                        sub.publish(deadMessage);
                    }
                }
            }
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(e)
                                                                      .setPublishedObject(message1));
        }
    }

    @Override
    public void publish(final SubscriptionManager subscriptionManager, final Object message1, final Object message2) {
        try {
            final Class<?> messageClass1 = message1.getClass();
            final Class<?> messageClass2 = message2.getClass();

            final Subscription[] subscriptions = subscriptionManager.getSubscriptionsExactAndSuper(messageClass1,
                                                                                                   messageClass2); // can return null

            // Run subscriptions
            if (subscriptions != null) {
                Subscription sub;
                for (int i = 0; i < subscriptions.length; i++) {
                    sub = subscriptions[i];
                    sub.publish(message1, message2);
                }
            }
            else {
                // Dead Event must EXACTLY MATCH (no subclasses)
                final Subscription[] deadSubscriptions = subscriptionManager.getSubscriptionsExact(DeadMessage.class); // can return null
                if (deadSubscriptions != null) {
                    final DeadMessage deadMessage = new DeadMessage(message1, message2);

                    Subscription sub;
                    for (int i = 0; i < deadSubscriptions.length; i++) {
                        sub = deadSubscriptions[i];
                        sub.publish(deadMessage);
                    }
                }
            }
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(e)
                                                                      .setPublishedObject(message1, message2));
        }
    }

    @Override
    public void publish(final SubscriptionManager subscriptionManager, final Object message1, final Object message2,
                        final Object message3) {
        try {
            final Class<?> messageClass1 = message1.getClass();
            final Class<?> messageClass2 = message2.getClass();
            final Class<?> messageClass3 = message3.getClass();

            final Subscription[] subscriptions = subscriptionManager.getSubscriptionsExactAndSuper(messageClass1, messageClass2,
                                                                                                   messageClass3); // can return null

            // Run subscriptions
            if (subscriptions != null) {
                Subscription sub;
                for (int i = 0; i < subscriptions.length; i++) {
                    sub = subscriptions[i];
                    sub.publish(message1, message2, message3);
                }
            }
            else {
                // Dead Event must EXACTLY MATCH (no subclasses)
                final Subscription[] deadSubscriptions = subscriptionManager.getSubscriptionsExact(DeadMessage.class); // can return null
                if (deadSubscriptions != null) {
                    final DeadMessage deadMessage = new DeadMessage(message1, message2, message3);

                    Subscription sub;
                    for (int i = 0; i < deadSubscriptions.length; i++) {
                        sub = deadSubscriptions[i];
                        sub.publish(deadMessage);
                    }
                }
            }
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(e)
                                                                      .setPublishedObject(message1, message2, message3));
        }
    }

    @Override
    public void publish(final SubscriptionManager subscriptionManager, final Object[] messages) {
        publish(subscriptionManager, (Object) messages);
    }
}
