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

import dorkbox.util.messagebus.error.DeadMessage;
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
            final Subscription[] subscriptions = subManager.getSubs(message1Class); // can return null
            if (subscriptions != null) {
                hasSubs = true;
                synchrony.publish(subscriptions, message1);
            }

            // Run superSubscriptions
            final Subscription[] superSubscriptions = subManager.getSuperSubs(message1Class); // NOT return null
            if (superSubscriptions.length > 0) {
                hasSubs = true;
                synchrony.publish(superSubscriptions, message1);
            }


            // Run dead message subscriptions
            if (!hasSubs) {
                // Dead Event must EXACTLY MATCH (no subclasses)
                final Subscription[] deadSubscriptions = subManager.getSubs(DeadMessage.class); // can return null
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
            boolean hasSubs = false;


            // Run subscriptions
            final Subscription[] subscriptions = subManager.getSubs(messageClass1, messageClass2); // can return null
            if (subscriptions != null) {
                hasSubs = true;
                synchrony.publish(subscriptions, message1, message2);
            }


            // Run superSubscriptions
            final Subscription[] superSubscriptions = subManager.getSuperSubs(messageClass1, messageClass2); // can return null
            if (superSubscriptions != null) {
                hasSubs = true;
                synchrony.publish(superSubscriptions, message1, message2);
            }


            // Run dead message subscriptions
            if (!hasSubs) {
                // Dead Event must EXACTLY MATCH (no subclasses)
                final Subscription[] deadSubscriptions = subManager.getSubs(DeadMessage.class); // can return null
                if (deadSubscriptions != null) {
                    synchrony.publish(deadSubscriptions, new DeadMessage(message1, message2));
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
            boolean hasSubs = false;


            // Run subscriptions
            final Subscription[] subscriptions = subManager.getSubs(messageClass1, messageClass2, messageClass3); // can return null
            if (subscriptions != null) {
                hasSubs = true;
                synchrony.publish(subscriptions, message1, message2, message3);
            }


            // Run superSubscriptions
            final Subscription[] superSubscriptions = subManager.getSuperSubs(messageClass1, messageClass2, messageClass3); // can return null
            if (superSubscriptions != null) {
                hasSubs = true;
                synchrony.publish(superSubscriptions, message1, message2, message3);
            }


            // Run dead message subscriptions
            if (!hasSubs) {
                // Dead Event must EXACTLY MATCH (no subclasses)
                final Subscription[] deadSubscriptions = subManager.getSubs(DeadMessage.class); // can return null
                if (deadSubscriptions != null) {
                    synchrony.publish(deadSubscriptions, new DeadMessage(message1, message2, message3));
                }
            }
        } catch (Throwable e) {
            errorHandler.handlePublicationError(new PublicationError().setMessage("Error during invocation of message handler.")
                                                                      .setCause(e)
                                                                      .setPublishedObject(message1, message2, message3));
        }
    }
}
