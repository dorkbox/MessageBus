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
package dorkbox.messageBus.dispatch;

import dorkbox.messageBus.error.DeadMessage;
import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.publication.Publisher;
import dorkbox.messageBus.subscription.Subscription;
import dorkbox.messageBus.subscription.SubscriptionManager;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
@SuppressWarnings("Duplicates")
public
class DispatchExactWithSuperTypes implements Dispatch {

    public
    DispatchExactWithSuperTypes() {
    }

    @Override
    public
    void publish(final Publisher publisher, final ErrorHandler errorHandler, final SubscriptionManager subManager,
                 final Object message1) {

        final Class<?> messageClass1 = message1.getClass();

        final Subscription[] subscriptions = subManager.getSubs(messageClass1); // can return null
        final Subscription[] superSubscriptions = subManager.getSuperSubs(messageClass1); // NOT return null

        Subscription sub;
        int subLength;
        boolean hasSubs = false;

        // Run subscriptions. if the subscriptions are NULL or length == 0, it means we don't have any that were ever subscribed.
        if (subscriptions != null && (subLength = subscriptions.length) > 0) {
            // even though they are non-null, and have length > 0 --- it is still possible the subscription was REMOVED at some point.
            // so there won't be any object/method this publishes to AND there won't be any "dead messages" triggered
            for (int i = 0; i < subLength; i++) {
                sub = subscriptions[i];
                hasSubs |= sub.publish(publisher, errorHandler, message1);
            }
        }

        if ((subLength = superSubscriptions.length) > 0) {
            // even though they are non-null, and have length > 0 --- it is still possible the subscription was REMOVED at some point.
            // so there won't be any object/method this publishes to AND there won't be any "dead messages" triggered
            for (int i = 0; i < subLength; i++) {
                sub = superSubscriptions[i];
                hasSubs |= sub.publish(publisher, errorHandler, message1);
            }
        }

        if (!hasSubs) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = subManager.getSubs(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message1);
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(publisher, errorHandler, deadMessage);
                }
            }
        }
    }


    @Override
    public
    void publish(final Publisher publisher, final ErrorHandler errorHandler, final SubscriptionManager subManager,
                 final Object message1, final Object message2) {

        final Class<?> messageClass1 = message1.getClass();
        final Class<?> messageClass2 = message2.getClass();

        final Subscription[] subscriptions = subManager.getSubs(messageClass1, messageClass2); // can return null
        final Subscription[] superSubscriptions = subManager.getSuperSubs(messageClass1, messageClass2); // NOT return null

        Subscription sub;
        int subLength;
        boolean hasSubs = false;

        // Run subscriptions. if the subscriptions are NULL or length == 0, it means we don't have any that were ever subscribed.
        if (subscriptions != null && (subLength = subscriptions.length) > 0) {
            // even though they are non-null, and have length > 0 --- it is still possible the subscription was REMOVED at some point.
            // so there won't be any object/method this publishes to AND there won't be any "dead messages" triggered
            for (int i = 0; i < subLength; i++) {
                sub = subscriptions[i];
                hasSubs |= sub.publish(publisher, errorHandler, message1, message2);
            }
        }

        if ((subLength = superSubscriptions.length) > 0) {
            // even though they are non-null, and have length > 0 --- it is still possible the subscription was REMOVED at some point.
            // so there won't be any object/method this publishes to AND there won't be any "dead messages" triggered
            for (int i = 0; i < subLength; i++) {
                sub = superSubscriptions[i];
                hasSubs |= sub.publish(publisher, errorHandler, message1, message2);
            }
        }

        if (!hasSubs) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = subManager.getSubs(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message1, message2);
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(publisher, errorHandler, deadMessage);
                }
            }
        }
    }

    @Override
    public
    void publish(final Publisher publisher, final ErrorHandler errorHandler, final SubscriptionManager subManager,
                 final Object message1, final Object message2, final Object message3) {

        final Class<?> messageClass1 = message1.getClass();
        final Class<?> messageClass2 = message2.getClass();
        final Class<?> messageClass3 = message3.getClass();

        final Subscription[] subscriptions = subManager.getSubs(messageClass1, messageClass2, messageClass3); // can return null
        final Subscription[] superSubscriptions = subManager.getSuperSubs(messageClass1, messageClass2, messageClass3); // NOT return null

        Subscription sub;
        int subLength;
        boolean hasSubs = false;

        // Run subscriptions. if the subscriptions are NULL or length == 0, it means we don't have any that were ever subscribed.
        if (subscriptions != null && (subLength = subscriptions.length) > 0) {
            // even though they are non-null, and have length > 0 --- it is still possible the subscription was REMOVED at some point.
            // so there won't be any object/method this publishes to AND there won't be any "dead messages" triggered
            for (int i = 0; i < subLength; i++) {
                sub = subscriptions[i];
                hasSubs |= sub.publish(publisher, errorHandler, message1, message2, message3);
            }
        }

        if ((subLength = superSubscriptions.length) > 0) {
            // even though they are non-null, and have length > 0 --- it is still possible the subscription was REMOVED at some point.
            // so there won't be any object/method this publishes to AND there won't be any "dead messages" triggered
            for (int i = 0; i < subLength; i++) {
                sub = superSubscriptions[i];
                hasSubs |= sub.publish(publisher, errorHandler, message1, message2, message3);
            }
        }

        if (!hasSubs) {
            // Dead Event must EXACTLY MATCH (no subclasses)
            final Subscription[] deadSubscriptions = subManager.getSubs(DeadMessage.class); // can return null
            if (deadSubscriptions != null) {
                final DeadMessage deadMessage = new DeadMessage(message1, message2, message3);
                for (int i = 0; i < deadSubscriptions.length; i++) {
                    sub = deadSubscriptions[i];
                    sub.publish(publisher, errorHandler, deadMessage);
                }
            }
        }
    }
}
