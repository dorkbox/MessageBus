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
package dorkbox.messagebus.publication;

import dorkbox.messagebus.error.ErrorHandler;
import dorkbox.messagebus.subscription.Subscription;
import dorkbox.messagebus.subscription.SubscriptionManager;
import dorkbox.messagebus.synchrony.Synchrony;

@SuppressWarnings("Duplicates")
public
class PublisherExactWithSuperTypes implements Publisher {

    private final ErrorHandler errorHandler;
    private final SubscriptionManager subManager;

    public
    PublisherExactWithSuperTypes(final ErrorHandler errorHandler, final SubscriptionManager subManager) {
        this.errorHandler = errorHandler;
        this.subManager = subManager;
    }

    @Override
    public
    void publish(final Synchrony synchrony, final Object message1) {
        final SubscriptionManager subManager = this.subManager;
        final Class<?> messageClass1 = message1.getClass();

        final Subscription[] subscriptions = subManager.getSubs(messageClass1); // can return null
        final Subscription[] superSubscriptions = subManager.getSuperSubs(messageClass1); // NOT return null

        synchrony.publish(subscriptions, superSubscriptions, message1);
    }

    @Override
    public
    void publish(final Synchrony synchrony, final Object message1, final Object message2) {
        final SubscriptionManager subManager = this.subManager;
        final Class<?> messageClass1 = message1.getClass();
        final Class<?> messageClass2 = message2.getClass();

        final Subscription[] subscriptions = subManager.getSubs(messageClass1, messageClass2); // can return null
        final Subscription[] superSubscriptions = subManager.getSuperSubs(messageClass1, messageClass2); // NOT return null
        synchrony.publish(subscriptions, superSubscriptions, message1, message2);
    }

    @Override
    public
    void publish(final Synchrony synchrony, final Object message1, final Object message2, final Object message3) {
        final SubscriptionManager subManager = this.subManager;
        final Class<?> messageClass1 = message1.getClass();
        final Class<?> messageClass2 = message2.getClass();
        final Class<?> messageClass3 = message3.getClass();

        final Subscription[] subscriptions = subManager.getSubs(messageClass1, messageClass2, messageClass3); // can return null
        final Subscription[] superSubscriptions = subManager.getSuperSubs(messageClass1, messageClass2, messageClass3); // NOT return null
        synchrony.publish(subscriptions, superSubscriptions, message1, message2, message3);
    }
}
