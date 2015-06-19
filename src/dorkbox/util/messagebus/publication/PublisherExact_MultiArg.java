package dorkbox.util.messagebus.publication;

import dorkbox.util.messagebus.common.DeadMessage;
import dorkbox.util.messagebus.common.adapter.StampedLock;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.subscription.Publisher;
import dorkbox.util.messagebus.subscription.Subscriber;
import dorkbox.util.messagebus.subscription.Subscription;

public class PublisherExact_MultiArg implements Publisher {
    private final ErrorHandlingSupport errorHandler;
    private final Subscriber subscriber;
    private final StampedLock lock;

    public PublisherExact_MultiArg(final ErrorHandlingSupport errorHandler, final Subscriber subscriber, final StampedLock lock) {
        this.errorHandler = errorHandler;
        this.subscriber = subscriber;
        this.lock = lock;
    }

    @Override
    public void publish(final Object message1) {
        try {
            final Class<?> messageClass = message1.getClass();

            final StampedLock lock = this.lock;
            long stamp = lock.readLock();
            final Subscription[] subscriptions = subscriber.getExact(messageClass); // can return null
            lock.unlockRead(stamp);

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
                stamp = lock.readLock();
                final Subscription[] deadSubscriptions = subscriber.getExact(DeadMessage.class); // can return null
                lock.unlockRead(stamp);

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
    public void publish(final Object message1, final Object message2) {
        try {
            final Class<?> messageClass1 = message1.getClass();
            final Class<?> messageClass2 = message2.getClass();

            final StampedLock lock = this.lock;
            long stamp = lock.readLock();
            final Subscription[] subscriptions = subscriber.getExact(messageClass1, messageClass2); // can return null
            lock.unlockRead(stamp);

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
                stamp = lock.readLock();
                final Subscription[] deadSubscriptions = subscriber.getExact(DeadMessage.class); // can return null
                lock.unlockRead(stamp);

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
    public void publish(final Object message1, final Object message2, final Object message3) {
        try {
            final Class<?> messageClass1 = message1.getClass();
            final Class<?> messageClass2 = message2.getClass();
            final Class<?> messageClass3 = message3.getClass();

            final StampedLock lock = this.lock;
            long stamp = lock.readLock();
            final Subscription[] subscriptions = subscriber.getExact(messageClass1, messageClass2, messageClass3); // can return null
            lock.unlockRead(stamp);

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
                stamp = lock.readLock();
                final Subscription[] deadSubscriptions = subscriber.getExact(DeadMessage.class); // can return null
                lock.unlockRead(stamp);

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
    public void publish(final Object[] messages) {
        publish((Object) messages);
    }
}
