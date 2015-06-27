package dorkbox.util.messagebus.publication;

import dorkbox.util.messagebus.common.DeadMessage;
import dorkbox.util.messagebus.common.adapter.StampedLock;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.subscription.Subscriber;
import dorkbox.util.messagebus.subscription.Subscription;

public
class PublisherExact_FirstArg implements Publisher {
    private final ErrorHandlingSupport errorHandler;
    private final Subscriber subscriber;
    private final StampedLock lock;

    public
    PublisherExact_FirstArg(final ErrorHandlingSupport errorHandler, final Subscriber subscriber, final StampedLock lock) {
        this.errorHandler = errorHandler;
        this.subscriber = subscriber;
        this.lock = lock;
    }

    @Override
    public
    void publish(final Object message1) {
        try {
            final Class<?> messageClass = message1.getClass();

            final StampedLock lock = this.lock;
            long stamp = lock.readLock();
            final Subscription[] subscriptions = subscriber.getExact(messageClass); // can return null
            lock.unlockRead(stamp);

            // Run subscriptions
            if (subscriptions != null) {
                Class<?>[] handledMessages;
                Subscription sub;
                for (int i = 0; i < subscriptions.length; i++) {
                    sub = subscriptions[i];

                    handledMessages = sub.getHandler().getHandledMessages();
                    if (handledMessages.length == 1) {
                        sub.publish(message1);
                    }
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
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(
                            e).setPublishedObject(message1));
        }
    }

    @Override
    public
    void publish(final Object message1, final Object message2) {
        try {
            final Class<?> messageClass = message1.getClass();

            final StampedLock lock = this.lock;
            long stamp = lock.readLock();
            final Subscription[] subscriptions = subscriber.getExact(messageClass); // can return null
            lock.unlockRead(stamp);

            // Run subscriptions
            if (subscriptions != null) {
                Class<?>[] handledMessages;
                Subscription sub;
                for (int i = 0; i < subscriptions.length; i++) {
                    sub = subscriptions[i];

                    handledMessages = sub.getHandler().getHandledMessages();
                    if (handledMessages.length == 2) {
                        sub.publish(message1, message2);
                    }
                }
            }
            else {
                // Dead Event must EXACTLY MATCH (no subclasses)
                lock.unlockRead(stamp);
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
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(
                            e).setPublishedObject(message1, message2));
        }
    }

    @Override
    public
    void publish(final Object message1, final Object message2, final Object message3) {
        try {
            final Class<?> messageClass = message1.getClass();

            final StampedLock lock = this.lock;
            long stamp = lock.readLock();
            final Subscription[] subscriptions = subscriber.getExact(messageClass); // can return null
            lock.unlockRead(stamp);

            // Run subscriptions
            if (subscriptions != null) {
                Class<?>[] handledMessages;
                Subscription sub;
                for (int i = 0; i < subscriptions.length; i++) {
                    sub = subscriptions[i];

                    handledMessages = sub.getHandler().getHandledMessages();
                    if (handledMessages.length == 3) {
                        sub.publish(message1, message2, message3);
                    }
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
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(
                            e).setPublishedObject(message1, message2, message3));
        }
    }

    @Override
    public
    void publish(final Object[] messages) {
        try {
            final Class<?> messageClass = messages[0].getClass();
            final int length = messages.length;

            final StampedLock lock = this.lock;
            long stamp = lock.readLock();
            final Subscription[] subscriptions = subscriber.getExact(messageClass); // can return null
            lock.unlockRead(stamp);

            // Run subscriptions
            if (subscriptions != null) {
                Class<?>[] handledMessages;
                Subscription sub;
                for (int i = 0; i < subscriptions.length; i++) {
                    sub = subscriptions[i];

                    handledMessages = sub.getHandler().getHandledMessages();
                    if (handledMessages.length == length) {
                        sub.publish(messages);
                    }
                }
            }
            else {
                // Dead Event must EXACTLY MATCH (no subclasses)
                stamp = lock.readLock();
                final Subscription[] deadSubscriptions = subscriber.getExact(DeadMessage.class); // can return null
                lock.unlockRead(stamp);

                if (deadSubscriptions != null) {
                    final DeadMessage deadMessage = new DeadMessage(messages);

                    Subscription sub;
                    for (int i = 0; i < deadSubscriptions.length; i++) {
                        sub = deadSubscriptions[i];
                        sub.publish(deadMessage);
                    }
                }
            }
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.").setCause(
                            e).setPublishedObject(messages));
        }
    }
}
