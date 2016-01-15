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
package dorkbox.util.messagebus.subscription;

import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.common.adapter.JavaVersionAdapter;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The subscription managers responsibility is to consistently handle and synchronize the message listener subscription process.
 * It provides fast lookup of existing subscriptions when another instance of an already known
 * listener is subscribed and takes care of creating new set of subscriptions for any unknown class that defines
 * message handlers.
 *
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public final
class SubscriptionManager {
    // remember already processed classes that do not contain any message handlers
    private final Map<Class<?>, Boolean> nonListeners;

    // all subscriptions per messageHandler type
    // this map provides fast access for subscribing and unsubscribing
    // write access is synchronized and happens very infrequently
    // once a collection of subscriptions is stored it does not change
    private final ConcurrentMap<Class<?>, Subscription[]> subscriptionsPerListener;


    private final Subscriber subscriber;


    public
    SubscriptionManager(final int numberOfThreads, final Subscriber subscriber) {
        this.subscriber = subscriber;


        // modified ONLY during SUB/UNSUB
        this.nonListeners = JavaVersionAdapter.concurrentMap(4, Subscriber.LOAD_FACTOR, numberOfThreads);

        // only used during SUB/UNSUB, in a rw lock
        this.subscriptionsPerListener = JavaVersionAdapter.concurrentMap(32, Subscriber.LOAD_FACTOR, 1);
    }

    public
    void shutdown() {
        this.nonListeners.clear();

        subscriber.shutdown();
        this.subscriptionsPerListener.clear();
    }

    public
    void subscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        final Class<?> listenerClass = listener.getClass();

        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        // these are concurrent collections
        subscriber.clear();

        // this is an array, because subscriptions for a specific listener CANNOT change, either they exist or do not exist.
        // ONCE subscriptions are in THIS map, they are considered AVAILABLE.
        Subscription[] subscriptions = this.subscriptionsPerListener.get(listenerClass);

        // the subscriptions from the map were null, so create them
        if (subscriptions == null) {
            // it is important to note that this section CAN be repeated.
            // anything 'permanent' is saved. This is so the time spent inside the writelock is minimized.

            final MessageHandler[] messageHandlers = MessageHandler.get(listenerClass);
            final int handlersSize = messageHandlers.length;

            // remember the class as non listening class if no handlers are found
            if (handlersSize == 0) {
                this.nonListeners.put(listenerClass, Boolean.TRUE);
                return;
            }



            final AtomicBoolean varArgPossibility = subscriber.varArgPossibility;
            Subscription subscription;

            MessageHandler messageHandler;
            Class<?>[] messageHandlerTypes;
            Class<?> handlerType;

            // create the subscriptions
            final ConcurrentMap<Class<?>, ArrayList<Subscription>> subsPerMessageSingle = subscriber.subscriptionsPerMessageSingle;
            subscriptions = new Subscription[handlersSize];

            for (int i = 0; i < handlersSize; i++) {
                // THE HANDLER IS THE SAME FOR ALL SUBSCRIPTIONS OF THE SAME TYPE!
                messageHandler = messageHandlers[i];

                // is this handler able to accept var args?
                if (messageHandler.getVarArgClass() != null) {
                    varArgPossibility.lazySet(true);
                }

                // now create a list of subscriptions for this specific handlerType (but don't add anything yet).
                // we only store things based on the FIRST type (for lookup) then parse the rest of the types during publication
                messageHandlerTypes = messageHandler.getHandledMessages();
                handlerType = messageHandlerTypes[0];

                // using ThreadLocal cache's is SIGNIFICANTLY faster for subscribing to new types
                final ArrayList<Subscription> cachedSubs = subscriber.listCache.get();
                ArrayList<Subscription> subs = subsPerMessageSingle.putIfAbsent(handlerType, cachedSubs);
                if (subs == null) {
                    subscriber.listCache.set(new ArrayList<Subscription>(8));
                }

                // create the subscription. This can be thrown away if the subscription succeeds in another thread
                subscription = new Subscription(messageHandler);
                subscriptions[i] = subscription;

                // now add this subscription to each of the handled types
            }

            // now subsPerMessageSingle has a unique list of subscriptions for a specific handlerType, and MAY already have subscriptions

            // putIfAbsent
            final Subscription[] previousSubs = subscriptionsPerListener.putIfAbsent(listenerClass, subscriptions); // activates this sub for sub/unsub
            if (previousSubs != null) {
                // another thread beat us to creating subs (for this exact listenerClass). Since another thread won, we have to make sure
                // all of the subscriptions are correct for a specific handler type, so we have to RECONSTRUT the correct list again.
                // This is to make sure that "invalid" subscriptions don't exist in subsPerMessageSingle.

                // since nothing is yet "subscribed" we can assign the correct values for everything now
                subscriptions = previousSubs;
            } else {
                // we can now safely add for publication AND subscribe since the data structures are consistent
                for (int i = 0; i < handlersSize; i++) {
                    subscription = subscriptions[i];
                    subscription.subscribe(listener);  // register this callback listener to this subscription

                    // THE HANDLER IS THE SAME FOR ALL SUBSCRIPTIONS OF THE SAME TYPE!
                    messageHandler = messageHandlers[i];

                    // register for publication
                    messageHandlerTypes = messageHandler.getHandledMessages();
                    handlerType = messageHandlerTypes[0];

                    // makes this subscription visible for publication
                    subsPerMessageSingle.get(handlerType).add(subscription);
                }

                return;
            }
        }

        // subscriptions already exist and must only be updated
        Subscription subscription;
        for (int i = 0; i < subscriptions.length; i++) {
            subscription = subscriptions[i];
            subscription.subscribe(listener);
        }
    }

    public
    void unsubscribe(final Object listener) {
        if (listener == null) {
            return;
        }

        final Class<?> listenerClass = listener.getClass();
        if (this.nonListeners.containsKey(listenerClass)) {
            // early reject of known classes that do not define message handlers
            return;
        }

        // these are concurrent collections
        subscriber.clear();

        final Subscription[] subscriptions = this.subscriptionsPerListener.get(listenerClass);
        if (subscriptions != null) {
            Subscription subscription;

            for (int i = 0; i < subscriptions.length; i++) {
                subscription = subscriptions[i];
                subscription.unsubscribe(listener);
            }
        }
    }
}
