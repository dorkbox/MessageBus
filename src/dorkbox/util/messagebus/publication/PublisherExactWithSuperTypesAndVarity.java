/*
 * Copyright 2015 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.util.messagebus.publication;

import dorkbox.util.messagebus.common.DeadMessage;
import dorkbox.util.messagebus.common.adapter.StampedLock;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.subscription.Subscriber;
import dorkbox.util.messagebus.subscription.Subscription;
import dorkbox.util.messagebus.utils.VarArgUtils;

import java.lang.reflect.Array;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("Duplicates")
public
class PublisherExactWithSuperTypesAndVarity implements Publisher {
    private final ErrorHandlingSupport errorHandler;

    private final Subscriber subscriber;
    private final StampedLock lock;

    private final AtomicBoolean varArgPossibility;
    final VarArgUtils varArgUtils;

    public
    PublisherExactWithSuperTypesAndVarity(final ErrorHandlingSupport errorHandler, final Subscriber subscriber, final StampedLock lock) {
        this.errorHandler = errorHandler;
        this.subscriber = subscriber;
        this.lock = lock;

        varArgPossibility = subscriber.getVarArgPossibility();
        varArgUtils = subscriber.getVarArgUtils();
    }

    @Override
    public
    void publish(final Object message1) {
        try {
            final Class<?> messageClass = message1.getClass();
            final boolean isArray = messageClass.isArray();

            final StampedLock lock = this.lock;
            long stamp = lock.readLock();
            final Subscription[] subscriptions = subscriber.getExactAndSuper(messageClass); // can return null
            lock.unlockRead(stamp);

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
            if (varArgPossibility.get() && !isArray) {
                stamp = lock.readLock();
                final Subscription[] varArgSubs = varArgUtils.getVarArgSubscriptions(messageClass, subscriber); // CAN NOT RETURN NULL
                lock.unlockRead(stamp);

                Subscription sub;
                int length = varArgSubs.length;
                Object[] asArray = null;

                if (length > 0) {
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
                final Subscription[] varArgSuperSubs = varArgUtils.getVarArgSuperSubscriptions(messageClass,
                                                                                               subscriber); // CAN NOT RETURN NULL
                lock.unlockRead(stamp);

                length = varArgSuperSubs.length;

                if (length > 0) {
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
                stamp = lock.readLock();
                final Subscription[] deadSubscriptions = subscriber.getExact(DeadMessage.class);
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
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1));
        }
    }

    @Override
    public
    void publish(final Object message1, final Object message2) {
        try {
            final Class<?> messageClass1 = message1.getClass();
            final Class<?> messageClass2 = message2.getClass();

            final StampedLock lock = this.lock;
            long stamp = lock.readLock();
            final Subscription[] subscriptions = subscriber.getExactAndSuper(messageClass1, messageClass2); // can return null
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
            if (varArgPossibility.get() && !messageClass1.isArray() && !messageClass2.isArray()) {

                // vararg can ONLY work if all types are the same
                if (messageClass1 == messageClass2) {
                    stamp = lock.readLock();
                    final Subscription[] varArgSubs = varArgUtils.getVarArgSubscriptions(messageClass1, subscriber); // can NOT return null
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
                final Subscription[] varArgSuperSubs = varArgUtils.getVarArgSuperSubscriptions(messageClass1, messageClass2,
                                                                                               subscriber); // CAN NOT RETURN NULL
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
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2));
        }
    }

    @Override
    public
    void publish(final Object message1, final Object message2, final Object message3) {
        try {
            final Class<?> messageClass1 = message1.getClass();
            final Class<?> messageClass2 = message2.getClass();
            final Class<?> messageClass3 = message3.getClass();

            final StampedLock lock = this.lock;
            long stamp = lock.readLock();
            final Subscription[] subs = subscriber.getExactAndSuper(messageClass1, messageClass2, messageClass3); // can return null
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
            if (varArgPossibility.get() && !messageClass1.isArray() && !messageClass2.isArray() &&
                !messageClass3.isArray()) {

                // vararg can ONLY work if all types are the same
                if (messageClass1 == messageClass2 && messageClass1 == messageClass3) {
                    stamp = lock.readLock();
                    final Subscription[] varArgSubs = varArgUtils.getVarArgSubscriptions(messageClass1, subscriber); // can NOT return null
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
                final Subscription[] varArgSuperSubs = varArgUtils.getVarArgSuperSubscriptions(messageClass1, messageClass2, messageClass3,
                                                                                               subscriber); // CAN NOT RETURN NULL
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
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2, message3));
        }
    }

    @Override
    public
    void publish(final Object[] messages) {
        publish((Object) messages);
    }
}
