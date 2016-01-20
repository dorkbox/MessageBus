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
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.subscription.Subscription;
import dorkbox.util.messagebus.subscription.SubscriptionManager;
import dorkbox.util.messagebus.synchrony.Synchrony;

@SuppressWarnings("Duplicates")
public
class PublisherExactWithSuperTypes implements Publisher {

    private final ErrorHandlingSupport errorHandler;
    private final SubscriptionManager subManager;

    public
    PublisherExactWithSuperTypes(final ErrorHandlingSupport errorHandler, final SubscriptionManager subManager) {
        this.errorHandler = errorHandler;
        this.subManager = subManager;
    }

    @Override
    public
    void publish(final Synchrony synchrony, final Object message1) {
        try {
            final SubscriptionManager subManager = this.subManager;
            final Class<?> message1Class = message1.getClass();
            boolean hasSubs = false;


            // Run subscriptions
            final Subscription[] subscriptions = subManager.getExactAsArray(message1Class); // can return null
            if (subscriptions != null) {
                hasSubs = true;
                synchrony.publish(subscriptions, message1);
            }

            // Run superClasses
            final Subscription[] superSubscriptions = subManager.getSuperExactAsArray(message1Class); // can return null
            if (superSubscriptions != null) {
                hasSubs = true;
                synchrony.publish(superSubscriptions, message1);
            }


            // Run dead message subscriptions
            if (!hasSubs) {
                // Dead Event must EXACTLY MATCH (no subclasses)
                final Subscription[] deadSubscriptions = subManager.getExactAsArray(DeadMessage.class); // can return null
                if (deadSubscriptions != null) {
                    synchrony.publish(deadSubscriptions, new DeadMessage(message1));
                }
            }
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1));
        }
    }

    @Override
    public
    void publish(final Synchrony synchrony, final Object message1, final Object message2) {
        try {
            final Class<?> messageClass1 = message1.getClass();
            final Class<?> messageClass2 = message2.getClass();

//            final StampedLock lock = this.lock;
//            long stamp = lock.readLock();
            final Subscription[] subscriptions = subManager.getExactAndSuper(messageClass1, messageClass2); // can return null
//            lock.unlockRead(stamp);

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
//                stamp = lock.readLock();
                final Subscription[] deadSubscriptions = subManager.getExact(DeadMessage.class); // can return null
//                lock.unlockRead(stamp);

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
    void publish(final Synchrony synchrony, final Object message1, final Object message2, final Object message3) {
        try {
            final Class<?> messageClass1 = message1.getClass();
            final Class<?> messageClass2 = message2.getClass();
            final Class<?> messageClass3 = message3.getClass();


//            final StampedLock lock = this.lock;
//            long stamp = lock.readLock();
            final Subscription[] subscriptions = subManager.getExactAndSuper(messageClass1, messageClass2,
                                                                             messageClass3); // can return null
//            lock.unlockRead(stamp);

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
//                stamp = lock.readLock();
                final Subscription[] deadSubscriptions = subManager.getExact(DeadMessage.class); // can return null
//                lock.unlockRead(stamp);

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
    void publish(final Synchrony synchrony, final Object[] messages) {
        publish(synchrony, (Object) messages);
    }
}
