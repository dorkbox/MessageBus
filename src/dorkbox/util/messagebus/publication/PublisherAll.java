package dorkbox.util.messagebus.publication;

import dorkbox.util.messagebus.common.DeadMessage;
import dorkbox.util.messagebus.common.adapter.StampedLock;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.subscription.Publisher;
import dorkbox.util.messagebus.subscription.Subscription;
import dorkbox.util.messagebus.subscription.SubscriptionManager;
import dorkbox.util.messagebus.utils.VarArgUtils;

import java.lang.reflect.Array;

public class PublisherAll implements Publisher {
    private final ErrorHandlingSupport errorHandler;

    public PublisherAll(final ErrorHandlingSupport errorHandler) {
        this.errorHandler = errorHandler;
    }

    @Override
    public void publish(final SubscriptionManager subscriptionManager, final Object message1) {
        try {
            final Class<?> messageClass = message1.getClass();
            final boolean isArray = messageClass.isArray();

            final StampedLock lock = subscriptionManager.getLock();
            final Subscription[] subscriptions = subscriptionManager.getSubscriptionsExactAndSuper_NoLock(messageClass); // can return null

            boolean hasSubs = false;
            // Run subscriptions
            if (subscriptions != null) {
                hasSubs = true;

                Subscription sub;
                for (int i = 0; i < subscriptions.length; i++) {
                    sub = subscriptions[i];
                    sub.publish(message1);
                }
            }


            // publish to var arg, only if not already an array (because that would be unnecessary)
            if (subscriptionManager.canPublishVarArg() && !isArray) {
                final VarArgUtils varArgUtils = subscriptionManager.getVarArgUtils();

                long stamp = lock.readLock();
                final Subscription[] varArgSubs = varArgUtils.getVarArgSubscriptions(messageClass); // CAN NOT RETURN NULL
                lock.unlockRead(stamp);

                Subscription sub;
                int length = varArgSubs.length;
                Object[] asArray = null;

                if (length > 1) {
                    hasSubs = true;

                    asArray = (Object[]) Array.newInstance(messageClass, 1);
                    asArray[0] = message1;

                    for (int i = 0; i < length; i++) {
                        sub = varArgSubs[i];
                        sub.publish(asArray);
                    }
                }


                // now publish array based superClasses (but only if those ALSO accept vararg)
                stamp = lock.readLock();
                final Subscription[] varArgSuperSubs = varArgUtils.getVarArgSuperSubscriptions(messageClass); // CAN NOT RETURN NULL
                lock.unlockRead(stamp);

                length = varArgSuperSubs.length;

                if (length > 1) {
                    hasSubs = true;

                    if (asArray == null) {
                        asArray = (Object[]) Array.newInstance(messageClass, 1);
                        asArray[0] = message1;
                    }

                    for (int i = 0; i < length; i++) {
                        sub = varArgSuperSubs[i];
                        sub.publish(asArray);
                    }
                }
            }

            // only get here if there were no other subscriptions
            // Dead Event must EXACTLY MATCH (no subclasses)
            if (!hasSubs) {
                final Subscription[] deadSubscriptions = subscriptionManager.getSubscriptionsExact(DeadMessage.class);
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

            final StampedLock lock = subscriptionManager.getLock();
            long stamp = lock.readLock();
            final Subscription[] subscriptions = subscriptionManager.getSubscriptionsExactAndSuper_NoLock(messageClass1,
                                                                                                          messageClass2); // can return null
            lock.unlockRead(stamp);

            boolean hasSubs = false;
            // Run subscriptions
            if (subscriptions != null) {
                hasSubs = true;

                Subscription sub;
                for (int i = 0; i < subscriptions.length; i++) {
                    sub = subscriptions[i];
                    sub.publish(message1, message2);
                }
            }

            // publish to var arg, only if not already an array AND we are all of the same type
            if (subscriptionManager.canPublishVarArg() && !messageClass1.isArray() && !messageClass2.isArray()) {

                final VarArgUtils varArgUtils = subscriptionManager.getVarArgUtils();

                // vararg can ONLY work if all types are the same
                if (messageClass1 == messageClass2) {
                    stamp = lock.readLock();
                    final Subscription[] varArgSubs = varArgUtils.getVarArgSubscriptions(messageClass1); // can NOT return null
                    lock.unlockRead(stamp);

                    final int length = varArgSubs.length;
                    if (length > 0) {
                        hasSubs = true;

                        Object[] asArray = (Object[]) Array.newInstance(messageClass1, 2);
                        asArray[0] = message1;
                        asArray[1] = message2;

                        Subscription sub;
                        for (int i = 0; i < length; i++) {
                            sub = varArgSubs[i];
                            sub.publish(asArray);
                        }
                    }
                }

                // now publish array based superClasses (but only if those ALSO accept vararg)
                stamp = lock.readLock();
                final Subscription[] varArgSuperSubs = varArgUtils.getVarArgSuperSubscriptions(messageClass1,
                                                                                               messageClass2); // CAN NOT RETURN NULL
                lock.unlockRead(stamp);


                final int length = varArgSuperSubs.length;
                if (length > 0) {
                    hasSubs = true;

                    Class<?> arrayType;
                    Object[] asArray;

                    Subscription sub;
                    for (int i = 0; i < length; i++) {
                        sub = varArgSuperSubs[i];
                        arrayType = sub.getHandler().getVarArgClass();

                        asArray = (Object[]) Array.newInstance(arrayType, 2);
                        asArray[0] = message1;
                        asArray[1] = message2;

                        sub.publish(asArray);
                    }
                }
            }

            if (!hasSubs) {
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

            final StampedLock lock = subscriptionManager.getLock();
            long stamp = lock.readLock();
            final Subscription[] subs = subscriptionManager.getSubscriptionsExactAndSuper_NoLock(messageClass1, messageClass2,
                                                                                                 messageClass3); // can return null
            lock.unlockRead(stamp);


            boolean hasSubs = false;
            // Run subscriptions
            if (subs != null) {
                hasSubs = true;

                Subscription sub;
                for (int i = 0; i < subs.length; i++) {
                    sub = subs[i];
                    sub.publish(message1, message2, message3);
                }
            }

            // publish to var arg, only if not already an array AND we are all of the same type
            if (subscriptionManager.canPublishVarArg() && !messageClass1.isArray() && !messageClass2.isArray() &&
                !messageClass3.isArray()) {

                final VarArgUtils varArgUtils = subscriptionManager.getVarArgUtils();

                // vararg can ONLY work if all types are the same
                if (messageClass1 == messageClass2 && messageClass1 == messageClass3) {
                    stamp = lock.readLock();
                    final Subscription[] varArgSubs = varArgUtils.getVarArgSubscriptions(messageClass1); // can NOT return null
                    lock.unlockRead(stamp);

                    final int length = varArgSubs.length;
                    if (length > 0) {
                        hasSubs = true;

                        Object[] asArray = (Object[]) Array.newInstance(messageClass1, 3);
                        asArray[0] = message1;
                        asArray[1] = message2;
                        asArray[2] = message3;

                        Subscription sub;
                        for (int i = 0; i < length; i++) {
                            sub = varArgSubs[i];
                            sub.publish(asArray);
                        }
                    }
                }


                // now publish array based superClasses (but only if those ALSO accept vararg)
                stamp = lock.readLock();
                final Subscription[] varArgSuperSubs = varArgUtils.getVarArgSuperSubscriptions(messageClass1, messageClass2,
                                                                                               messageClass3); // CAN NOT RETURN NULL
                lock.unlockRead(stamp);


                final int length = varArgSuperSubs.length;
                if (length > 0) {
                    hasSubs = true;

                    Class<?> arrayType;
                    Object[] asArray;

                    Subscription sub;
                    for (int i = 0; i < length; i++) {
                        sub = varArgSuperSubs[i];
                        arrayType = sub.getHandler().getVarArgClass();

                        asArray = (Object[]) Array.newInstance(arrayType, 3);
                        asArray[0] = message1;
                        asArray[1] = message2;
                        asArray[2] = message3;

                        sub.publish(asArray);
                    }
                }
            }

            if (!hasSubs) {
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
